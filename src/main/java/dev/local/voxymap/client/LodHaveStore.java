package dev.local.voxymap.client;

import dev.local.voxymap.net.LodProtocol;
import dev.local.voxymap.util.Log;
import dev.local.voxymap.voxy.SectionIndex;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * What this client already holds, per section column, persisted across sessions.
 *
 * <p>Same shape as {@link dev.local.voxymap.sweep.FingerprintStore} -- a {@code Long2LongOpenHashMap}
 * of key to content hash behind a MAGIC + VERSION header -- along a different axis: that one is
 * region to a hash of which sections exist, for deciding what to re-sweep; this one is column to a
 * hash of what those sections contain, for deciding what to ask the server not to send again.
 *
 * <h2>Where it lives, and why it lives there</h2>
 *
 * <p>Inside Voxy's own save folder for this world, beside the {@code storage/} directory it
 * describes: {@code <voxy saves>/<world id>/voxymap-have.bin}.
 *
 * <p>That is not filing convenience. This file is a claim about the contents of one specific Voxy
 * database, and a claim that outlives the thing it describes is the §7.16 bug: after a wipe the
 * client asked for nothing, the server sent nothing, and the map would not refill until a file was
 * deleted over SSH. Keeping it inside the database's own folder means every way that database can go
 * away -- {@code /voxymap wipe}, deleting the folder by hand, moving the profile -- takes this with
 * it, without anything having to remember to.
 *
 * <p>Two more guards stand behind that, because one mechanism is not enough for a failure that loses
 * ground permanently. The world id is written into the header and checked on load, so a file that
 * somehow ends up beside the wrong database is discarded. And {@link LodDeclareWorker} intersects
 * whatever is loaded here against Voxy's own section index before declaring any of it, so a store
 * that has outlived its data declares nothing.
 *
 * <h2>Not a cache of what the server sent</h2>
 *
 * <p>An entry means "I have ingested exactly this content for this column". It is written when the
 * last section of a column arrives and the whole column has been inserted, never when one is
 * requested or in flight. Inside view distance Voxy also voxelizes real blocks for itself, which can
 * leave the client's copy newer than the hash recorded here; that is harmless and deliberate. The
 * hash says what the client last took from the server, so an unchanged server sends nothing and a
 * changed one sends the new version -- which is what the client wants in both cases.
 */
public final class LodHaveStore {
   private static final int MAGIC = 0x564D4C48;

   /**
    * The file format's own version, separate from the protocol.
    *
    * <p>{@link LodProtocol#PROTOCOL} is checked as well and for a different reason: it fixes what
    * the stored numbers <em>mean</em>. A hash is only comparable against the definition that
    * produced it, so a protocol bump has to discard these as surely as a format change does.
    */
   private static final int VERSION = 1;

   /** Enough for a very large map; past it the file is not ours or not intact. */
   private static final int MAX_ENTRIES = 8_000_000;

   private final Path file;
   private final String worldId;
   private final Long2LongOpenHashMap columns;

   /**
    * Which heights of each column were actually inserted, as {@code SectionIndex}'s bitset.
    *
    * <p>Kept beside the hash so {@link #retainConfirmed} can check that the sections are still
    * there, not merely that the column is. The case it exists for is a client that crashed between
    * inserting a column and Voxy persisting all of it: the column survives, some of its stack does
    * not, and "the column exists" would happily declare the hash of a stack the client no longer
    * holds. The server would then never send the missing part, and a chunk missing a section below
    * the surface never draws at all (§7.19's completeness rule).
    */
   private final Long2LongOpenHashMap heights;

   private boolean dirty;

   private LodHaveStore(Path file, String worldId, Long2LongOpenHashMap columns, Long2LongOpenHashMap heights) {
      this.file = file;
      this.worldId = worldId;
      this.columns = columns;
      this.heights = heights;
      this.columns.defaultReturnValue(0L);
      this.heights.defaultReturnValue(0L);
   }

   /**
    * A store that is never written back.
    *
    * <p>For the sessions where the client could not verify what it holds. Recording receipts into a
    * file whose baseline could not be checked would be building next session's declaration out of
    * this session's guesses.
    */
   public static LodHaveStore detached() {
      return new LodHaveStore(null, "", new Long2LongOpenHashMap(), new Long2LongOpenHashMap());
   }

   /** {@code <voxy saves>/<world id>/voxymap-have.bin}, or null if Voxy's folder is unknown. */
   public static Path fileFor(Path voxySaveFolder, String worldId) {
      if (voxySaveFolder == null || worldId == null || worldId.isEmpty()) {
         return null;
      }

      return voxySaveFolder.resolve(worldId).resolve("voxymap-have.bin");
   }

   /**
    * Reads the store for one world.
    *
    * <p>Never fails: anything unreadable, mismatched or truncated comes back empty, which declares
    * nothing and costs a resend. The alternative -- guessing at a half-parsed file -- would be
    * claiming to hold content that cannot be shown to be there.
    */
   public static LodHaveStore load(Path voxySaveFolder, String worldId) {
      Path f = fileFor(voxySaveFolder, worldId);
      Long2LongOpenHashMap columns = new Long2LongOpenHashMap(1 << 14);
      Long2LongOpenHashMap heights = new Long2LongOpenHashMap(1 << 14);

      if (f == null || !Files.exists(f)) {
         return new LodHaveStore(f, worldId, columns, heights);
      }

      try (DataInputStream in = new DataInputStream(Files.newInputStream(f))) {
         int magic = in.readInt();
         int version = in.readInt();
         int protocol = in.readInt();
         String storedWorld = in.readUTF();

         if (magic != MAGIC || version != VERSION) {
            Log.info("the streamed-LOD store at " + f + " is from another format; starting from nothing");
            return new LodHaveStore(f, worldId, columns, heights);
         }

         if (protocol != LodProtocol.PROTOCOL) {
            Log.info(
               "the streamed-LOD store at " + f + " was written for protocol " + protocol
                  + " and this build speaks " + LodProtocol.PROTOCOL + "; starting from nothing"
            );
            return new LodHaveStore(f, worldId, columns, heights);
         }

         if (!storedWorld.equals(worldId)) {
            // Beside the wrong database. Trusting it would be declaring one world's ground while
            // holding another's.
            Log.warn("the streamed-LOD store at " + f + " belongs to world " + storedWorld + ", not " + worldId + "; ignoring it");
            return new LodHaveStore(f, worldId, columns, heights);
         }

         int count = in.readInt();

         if (count < 0 || count > MAX_ENTRIES) {
            return new LodHaveStore(f, worldId, columns, heights);
         }

         for (int i = 0; i < count; i++) {
            long key = in.readLong();
            columns.put(key, in.readLong());
            heights.put(key, in.readLong());
         }

         return new LodHaveStore(f, worldId, columns, heights);
      } catch (Throwable t) {
         Log.warn("could not read the streamed-LOD store at " + f + "; the server will re-send everything", t);
         return new LodHaveStore(f, worldId, new Long2LongOpenHashMap(1 << 14), new Long2LongOpenHashMap(1 << 14));
      }
   }

   /**
    * Records that the whole column has been inserted, at this content hash.
    *
    * <p>Synchronised against {@link #save}, which is the one thing that touches this map from
    * another thread: {@code ClientPlayConnectionEvents.DISCONNECT} fires on the Netty thread, and it
    * has to save there rather than deferring to the client thread -- the usual way a session ends is
    * the window closing, and a task deferred to a client thread that is shutting down never runs.
    * So the write happens off-thread and the map is locked instead. Uncontended in practice: a few
    * thousand receipts a session against one save.
    *
    * @param ySet which heights arrived, so a later session can check they are still there
    */
   public synchronized void put(long columnKey, long hash, long ySet) {
      boolean changed = this.columns.put(columnKey, hash) != hash;
      changed |= this.heights.put(columnKey, ySet) != ySet;

      if (changed) {
         this.dirty = true;
      }
   }

   public long get(long columnKey) {
      return this.columns.get(columnKey);
   }

   public synchronized int size() {
      return this.columns.size();
   }

   public synchronized boolean dirty() {
      return this.dirty;
   }

   public Long2LongOpenHashMap columns() {
      return this.columns;
   }

   /**
    * Drops every column Voxy's section index cannot confirm is actually there.
    *
    * <p>This is the guard that makes a lost database safe. The store could be intact while the
    * database beside it is gone -- restored from a backup, deleted by hand, or emptied by a wipe
    * that half-landed -- and every one of those cases ends with the client declaring ground it does
    * not have and the server declining to send it.
    *
    * <p>The index errs the same way this does. §7.15: a range scan over Voxy's storage returns only
    * what RocksDB has flushed, so on a young database it under-reports, and this prunes entries that
    * are really there. That costs a resend. The opposite error costs the ground.
    *
    * @return how many entries were dropped
    */
   public int retainConfirmed(SectionIndex index) {
      if (index == null) {
         return 0;
      }

      int before = this.columns.size();

      // Containment, not equality: Voxy voxelizes real blocks for itself inside view distance, so a
      // column can legitimately have grown heights the server never sent. What must not have
      // happened is one of the recorded heights going missing.
      boolean removed = this.columns.long2LongEntrySet().removeIf(e -> {
         long recorded = this.heights.get(e.getLongKey());
         return !index.hasColumnKey(e.getLongKey()) || (index.ySetKey(e.getLongKey()) & recorded) != recorded;
      });

      this.heights.keySet().retainAll(this.columns.keySet());

      if (removed) {
         this.dirty = true;
      }

      return before - this.columns.size();
   }

   /**
    * Writes the store back.
    *
    * <p>Through a temporary file and a move, so a crash mid-write leaves the previous store rather
    * than a half one. A half one would still be caught on load, but it would be caught by throwing
    * away everything, and there is no reason to lose a session's worth of receipts to a bad moment.
    */
   public synchronized void save() {
      if (this.file == null || !this.dirty) {
         return;
      }

      Path tmp = this.file.resolveSibling(this.file.getFileName() + ".tmp");

      try {
         Files.createDirectories(this.file.getParent());

         try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(tmp))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(LodProtocol.PROTOCOL);
            out.writeUTF(this.worldId == null ? "" : this.worldId);
            out.writeInt(this.columns.size());

            for (Long2LongOpenHashMap.Entry e : this.columns.long2LongEntrySet()) {
               out.writeLong(e.getLongKey());
               out.writeLong(e.getLongValue());
               out.writeLong(this.heights.get(e.getLongKey()));
            }
         }

         Files.move(tmp, this.file, StandardCopyOption.REPLACE_EXISTING);
         this.dirty = false;
      } catch (IOException e) {
         Log.warn("could not write the streamed-LOD store at " + this.file, e);
      }
   }

   @Override
   public String toString() {
      return this.columns.size() + " columns at " + this.file;
   }
}
