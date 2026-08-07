package dev.local.voxymap.server;

import dev.local.voxymap.net.LodHash;
import dev.local.voxymap.util.Log;
import dev.local.voxymap.voxy.SectionIndex;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;

/**
 * What the server's own LOD store contains, hashed per section column.
 *
 * <p>The streamer needs to answer "has this column changed since the client took a copy?" for every
 * column it walks past, several times a second, and the honest way to answer it is to hash the
 * content. Hashing means reading, and reading a column is eleven RocksDB lookups that each
 * decompress into a 256 KB array -- far too expensive to repeat on a walk that re-crosses the same
 * ground every lap. So the answer is cached here and only recomputed when the content actually
 * moves.
 *
 * <h2>Deliberately not persisted</h2>
 *
 * <p>A hash cache on disk would survive a restart and save the first lap's reads. It would also be a
 * server-side record that can be wrong, and a wrong entry here does not cost bandwidth -- it makes
 * the server certain that a column is unchanged when it is not, and the client never gets the
 * repair. That is §7.16 with the file moved to the other machine. Held in memory it is rebuilt from
 * the actual bytes every time the process starts, which cannot be stale by construction. The cost is
 * one re-read per column per restart, which is less work than the encode-and-send this replaces.
 *
 * <h2>How it learns the content moved</h2>
 *
 * <p>{@link WorldEngine#setDirtyCallback} fires from {@code WorldUpdater.insertUpdate} at the moment
 * a section's data actually changes -- not when a chunk is queued for ingest, which is the wrong
 * moment by however long the ingest queue happens to be. The callback runs on Voxy's ingest threads,
 * so it does the least possible: append a column key to a buffer that the server thread drains.
 *
 * <p>It is a single setter with no getter, so claiming it blindly would silently break whoever
 * claimed it first -- the same trap as §7.12. Voxy's own renderer claims it client-side, so this
 * only claims engines belonging to the instance {@link ServerVoxyInstance} installed, which on a
 * dedicated server is all of them and on an integrated server is none.
 */
public final class LodContentIndex {
   /** Column key to content hash. Zero means "not known", which is also what an absent column reads as. */
   private final Long2LongOpenHashMap columnHash = new Long2LongOpenHashMap(1 << 14);

   /**
    * Columns the server has nothing in at all.
    *
    * <p>Most of a stream radius is this: the disc reaches 96 sections and the pregenerator only
    * fills the inner third of it, so a lap spends most of its budget asking storage about ground
    * that does not exist. Eleven RocksDB misses per column, several thousand a second, for an answer
    * that was the same last lap.
    *
    * <p>Only used when {@link #attached}. A cached negative is a promise to notice when it stops
    * being true, and without the dirty callback there is nothing to notice it with -- a column that
    * gained data would stay invisible for the rest of the session, which is the failure that loses
    * ground rather than the one that wastes bandwidth.
    */
   private final it.unimi.dsi.fastutil.longs.LongOpenHashSet emptyColumns = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

   /** Column keys whose content changed, appended off-thread and drained on the server thread. */
   private final LongArrayList pending = new LongArrayList();
   private final Object pendingLock = new Object();

   /**
    * Past this many unprocessed invalidations, throw the whole cache away instead.
    *
    * <p>A pregeneration run touching a hundred thousand columns should not be remembered one key at
    * a time; re-hashing from empty is cheaper than the bookkeeping, and it is the same answer.
    */
   private static final int PENDING_LIMIT = 1 << 16;

   private boolean overflowed;
   private boolean attached;
   private boolean attachRefused;

   /**
    * The engine the callback was installed on.
    *
    * <p>Voxy frees a world engine once nothing has touched it for ten seconds and builds a fresh one
    * on the next access, so an index that only remembered "attached" would keep believing it was
    * being told about changes to an engine that no longer exists -- and would keep trusting
    * {@link #emptyColumns}, which is the cache whose staleness hides data rather than wasting
    * bandwidth.
    */
   private WorldEngine attachedTo;

   private long columnsHashed;
   private long columnsInvalidated;
   private long sectionsRead;

   /**
    * Claims the dirty callback, once, if this engine is ours to claim.
    *
    * <p>Called every pass because engines come and go with dimensions; everything after the first
    * call for a given index is a boolean test.
    */
   public void attach(WorldEngine engine) {
      if (engine == null) {
         return;
      }

      if (engine != this.attachedTo && this.attachedTo != null) {
         // A different engine for the same world. Everything learned about the old one is about a
         // database nobody was writing to while it was closed, so the hashes are still true -- but
         // starting clean is one lap of re-reading against a class of mistake that costs the player
         // ground, and that is not a trade worth making.
         this.attached = false;
         this.attachRefused = false;
         this.attachedTo = null;
         this.columnHash.clear();
         this.emptyColumns.clear();
         Log.info("the Voxy engine for this world was replaced; re-reading the LOD store's content hashes");
      }

      if (this.attached || this.attachRefused) {
         return;
      }

      if (!(engine.instanceIn instanceof ServerVoxyInstance)) {
         // Someone else's engine -- on an integrated server, Voxy's client instance, whose renderer
         // owns this callback and needs it. Without invalidation the cache still serves the delta
         // correctly for everything that existed when the server started; what it cannot do is
         // notice later changes. In singleplayer both ends share one database and stream nothing
         // meaningful anyway, so this costs nothing that matters.
         this.attachRefused = true;
         Log.info("this Voxy engine belongs to another instance; streamed LOD will not notice server-side changes to it");
         return;
      }

      try {
         engine.setDirtyCallback((section, changeState, neighbourMask) -> {
            if (section.lvl != 0) {
               return;
            }

            this.invalidate(SectionIndex.columnKey(section.x, section.z));
         });

         this.attached = true;
         this.attachedTo = engine;
      } catch (Throwable t) {
         this.attachRefused = true;
         Log.warn("could not watch the LOD store for changes; streamed LOD will not notice them", t);
      }
   }

   /** Any thread. Must stay cheap: this is on Voxy's ingest path. */
   private void invalidate(long columnKey) {
      synchronized (this.pendingLock) {
         if (this.overflowed) {
            return;
         }

         if (this.pending.size() >= PENDING_LIMIT) {
            this.overflowed = true;
            this.pending.clear();
            return;
         }

         // A chunk insert marks every section of one column in turn, so the same key arrives in
         // runs. One comparison removes most of the duplicates for nothing.
         if (!this.pending.isEmpty() && this.pending.getLong(this.pending.size() - 1) == columnKey) {
            return;
         }

         this.pending.add(columnKey);
      }
   }

   /** Server thread, once per pass, before anything is compared against the cache. */
   public void drainInvalidations() {
      long[] keys;
      boolean flush;

      synchronized (this.pendingLock) {
         if (!this.overflowed && this.pending.isEmpty()) {
            return;
         }

         flush = this.overflowed;
         keys = flush ? null : this.pending.toLongArray();
         this.pending.clear();
         this.overflowed = false;
      }

      if (flush) {
         this.columnsInvalidated += this.columnHash.size();
         this.columnHash.clear();
         this.emptyColumns.clear();
         Log.info("the LOD store changed faster than it could be tracked; re-hashing it from scratch");
         return;
      }

      for (long key : keys) {
         if (this.columnHash.remove(key) != 0L) {
            this.columnsInvalidated++;
         }

         // A column that has just been written is no longer empty, whatever it was before.
         this.emptyColumns.remove(key);
      }
   }

   /** @return the cached hash, or 0 if this column has not been hashed since it last changed */
   public long known(long columnKey) {
      return this.columnHash.get(columnKey);
   }

   /**
    * The content hash of one column, reading it if the cache does not have it.
    *
    * @return the hash, or 0 if the server has nothing stored in this column
    */
   public long hash(WorldEngine engine, long columnKey, int sx, int sz, int minSy, int maxSy, int[] blockIdToGlobalState, int[] biomeIdToNameHash) {
      long cached = this.columnHash.get(columnKey);

      if (cached != 0L) {
         return cached;
      }

      if (this.attached && this.emptyColumns.contains(columnKey)) {
         return 0L;
      }

      long acc = 0L;
      boolean any = false;

      for (int sy = maxSy; sy >= minSy; sy--) {
         WorldSection section = engine.acquireIfExists(0, sx, sy, sz);

         if (section == null) {
            continue;
         }

         try {
            this.sectionsRead++;
            any = true;
            acc = LodHash.column(acc, sy, LodHash.section(section._unsafeGetRawDataArray(), blockIdToGlobalState, biomeIdToNameHash));
         } finally {
            section.release();
         }
      }

      if (!any) {
         if (this.attached) {
            this.emptyColumns.add(columnKey);
         }

         return 0L;
      }

      this.emptyColumns.remove(columnKey);

      long hash = LodHash.nonZero(acc);
      this.columnHash.put(columnKey, hash);
      this.columnsHashed++;
      return hash;
   }

   /**
    * The roll-up of everything hashed so far in one region, folded the same way the client folds.
    *
    * <p>Deliberately over what is <em>known</em> rather than what exists. A cold cache folds to a
    * number that matches nothing, so the region falls through to the per-column path and is answered
    * correctly, just with a round trip. Once a lap has crossed the region every column in it is
    * known and the number is exact, which is what makes the second player -- or the same player
    * reconnecting -- cost nothing at all.
    *
    * @return the roll-up, or 0 if nothing in this region has been hashed
    */
   public long regionRollUp(long regionKey) {
      int rx = SectionIndex.regionX(regionKey);
      int rz = SectionIndex.regionZ(regionKey);
      long acc = 0L;
      boolean any = false;

      for (int lx = 0; lx < 16; lx++) {
         for (int lz = 0; lz < 16; lz++) {
            long col = SectionIndex.columnKey((rx << 4) + lx, (rz << 4) + lz);
            long h = this.columnHash.get(col);

            if (h != 0L) {
               any = true;
               acc = LodHash.region(acc, col, h);
            }
         }
      }

      return any ? LodHash.nonZero(acc) : 0L;
   }

   /**
    * Copies this region's known column hashes into a player's picture of what they hold.
    *
    * <p>Only correct to call once {@link #regionRollUp} has matched what the client declared, which
    * is exactly the statement "these are also your hashes".
    */
   public void adoptRegionInto(long regionKey, Long2LongOpenHashMap believed) {
      int rx = SectionIndex.regionX(regionKey);
      int rz = SectionIndex.regionZ(regionKey);

      for (int lx = 0; lx < 16; lx++) {
         for (int lz = 0; lz < 16; lz++) {
            long col = SectionIndex.columnKey((rx << 4) + lx, (rz << 4) + lz);
            long h = this.columnHash.get(col);

            if (h != 0L) {
               believed.put(col, h);
            }
         }
      }
   }

   public String status() {
      return "cached=" + this.columnHash.size()
         + " knownEmpty=" + this.emptyColumns.size()
         + " hashed=" + this.columnsHashed
         + " invalidated=" + this.columnsInvalidated
         + " sectionReads=" + this.sectionsRead
         + " watching=" + this.attached;
   }
}
