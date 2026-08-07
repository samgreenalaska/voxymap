package dev.local.voxymap.voxy;

import dev.local.voxymap.util.Log;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.function.BooleanSupplier;
import me.cortex.voxy.common.world.WorldEngine;

/**
 * One-shot index of which LOD-0 sections Voxy actually has on disk.
 *
 * <p>The storage backend holds a live iterator open for the whole callback, so the callback must
 * not call back into storage. Folding into the column map is pure arithmetic on a local hash map,
 * so it is safe to do inline -- and worth it: buffering first meant a 380k-entry
 * {@code LongArrayList} (~3 MB, repeatedly grown and copied) allocated on every rescan, plus a
 * second full pass over it. Positions arrive grouped by Y slice rather than by column, but the
 * fold is an OR into a bitset and does not care about order.
 */
public final class SectionIndex {
   /** sy is a signed byte in Voxy's key format; this bitset covers the part any real world uses. */
   static final int Y_BIAS = 32;
   private static final int Y_MIN = -32;
   private static final int Y_MAX = 31;

   private final Long2LongOpenHashMap columns;
   private final int sectionCount;
   private final int outOfRange;

   private SectionIndex(Long2LongOpenHashMap columns, int sectionCount, int outOfRange) {
      this.columns = columns;
      this.sectionCount = sectionCount;
      this.outOfRange = outOfRange;
   }

   public static long columnKey(int sx, int sz) {
      return ((long)(sx & 0xFFFFFF) << 24) | (long)(sz & 0xFFFFFF);
   }

   public static int columnX(long key) {
      return (int)(key >> 24 & 0xFFFFFF) << 8 >> 8;
   }

   public static int columnZ(long key) {
      return (int)(key & 0xFFFFFF) << 8 >> 8;
   }

   public static long regionKey(int rx, int rz) {
      return ((long)rx << 32) | (rz & 0xFFFFFFFFL);
   }

   public static int regionX(long key) {
      return (int)(key >> 32);
   }

   public static int regionZ(long key) {
      return (int)key;
   }

   /**
    * The cheap half of a rescan: per-region content hashes, without building the column map.
    *
    * <p>Building the map is what costs a second or two -- roughly 480k inserts into an 80k-entry
    * hash map, allocated fresh each time. The hashes need none of it: XOR of a scrambled position
    * is order-independent, so it folds into a map with one entry per Xaero region, a few hundred at
    * most, which stays in cache. In steady state the answer is "nothing changed" and the column map
    * is never built at all.
    *
    * <p>Detects sections appearing and disappearing, not a section's contents changing under a
    * position that already existed -- exactly as the column-based hash it replaces did.
    *
    * @param abort see {@link #enumerate}
    * @return the hashes and how many sections Voxy is storing, or null if the walk was aborted
    */
   public record FingerprintScan(Long2LongOpenHashMap fingerprints, int sectionCount) {
   }

   public static FingerprintScan scanFingerprints(WorldEngine engine, BooleanSupplier abort) {
      Long2LongOpenHashMap out = new Long2LongOpenHashMap(512);
      out.defaultReturnValue(0L);
      int[] counter = new int[1];
      boolean[] aborted = new boolean[1];

      engine.storage.iteratePositions(0, pos -> {
         if (aborted[0]) {
            return;
         }

         if ((counter[0]++ & 0x1FFF) == 0 && abort.getAsBoolean()) {
            aborted[0] = true;
            return;
         }

         int sy = WorldEngine.getY(pos);
         if (sy < Y_MIN || sy > Y_MAX) {
            return;
         }

         long rk = regionKey(WorldEngine.getX(pos) >> 4, WorldEngine.getZ(pos) >> 4);
         out.put(rk, out.get(rk) ^ scramble(pos * 0x9E3779B97F4A7C15L));
      });

      return aborted[0] ? null : new FingerprintScan(out, counter[0]);
   }

   /**
    * Worker thread only.
    *
    * @param abort polled every few thousand positions; when it answers true the walk stops doing
    *     work and the result is discarded. The iterator itself cannot be broken out of without
    *     leaving Voxy's storage backend mid-iteration, so this drains it as cheaply as possible
    *     instead. That matters on disconnect: this is a second or two during which the worker is
    *     otherwise unable to notice the world has gone and let go of Voxy's engine.
    */
   public static SectionIndex enumerate(WorldEngine engine, BooleanSupplier abort) {
      Long2LongOpenHashMap columns = new Long2LongOpenHashMap(1 << 16);
      columns.defaultReturnValue(0L);
      int[] counters = new int[2];
      boolean[] aborted = new boolean[1];

      engine.storage.iteratePositions(0, pos -> {
         if (aborted[0]) {
            return;
         }

         if ((counters[0] & 0x1FFF) == 0 && abort.getAsBoolean()) {
            aborted[0] = true;
            return;
         }

         counters[0]++;
         int sy = WorldEngine.getY(pos);

         if (sy < Y_MIN || sy > Y_MAX) {
            counters[1]++;
            return;
         }

         long key = columnKey(WorldEngine.getX(pos), WorldEngine.getZ(pos));
         columns.put(key, columns.get(key) | 1L << (sy + Y_BIAS));
      });

      if (aborted[0]) {
         return null;
      }

      int count = counters[0];
      int outOfRange = counters[1];

      if (outOfRange > 0) {
         Log.warn(outOfRange + " LOD-0 sections sit outside the supported vertical range and were ignored");
      }

      return new SectionIndex(columns, count, outOfRange);
   }

   public int sectionCount() {
      return this.sectionCount;
   }

   public int columnCount() {
      return this.columns.size();
   }

   public int outOfRangeCount() {
      return this.outOfRange;
   }

   public long ySet(int sx, int sz) {
      return this.columns.get(columnKey(sx, sz));
   }

   public boolean hasColumn(int sx, int sz) {
      return this.columns.containsKey(columnKey(sx, sz));
   }

   /** Same question against an already-packed key, for callers that hold one. */
   public boolean hasColumnKey(long key) {
      return this.columns.containsKey(key);
   }

   /** The bitset of heights present in a column, against an already-packed key. */
   public long ySetKey(long key) {
      return this.columns.get(key);
   }

   public static int highestSy(long ySet) {
      return ySet == 0L ? Integer.MIN_VALUE : 63 - Long.numberOfLeadingZeros(ySet) - Y_BIAS;
   }

   public static int lowestSy(long ySet) {
      return ySet == 0L ? Integer.MAX_VALUE : Long.numberOfTrailingZeros(ySet) - Y_BIAS;
   }

   public static boolean hasSy(long ySet, int sy) {
      return sy >= Y_MIN && sy <= Y_MAX && (ySet & 1L << (sy + Y_BIAS)) != 0L;
   }

   private static long scramble(long v) {
      v ^= v >>> 33;
      v *= 0xFF51AFD7ED558CCDL;
      v ^= v >>> 33;
      v *= 0xC4CEB9FE1A85EC53L;
      return v ^ v >>> 33;
   }

   /** Distinct Xaero regions touched by the indexed columns. One region is 16x16 LOD-0 columns. */
   public LongOpenHashSet regions() {
      LongOpenHashSet out = new LongOpenHashSet();

      for (long key : this.columns.keySet()) {
         out.add(regionKey(columnX(key) >> 4, columnZ(key) >> 4));
      }

      return out;
   }

   /** Tile chunks (4x4 chunks == 2x2 LOD-0 columns) inside a region that have any Voxy data. */
   public LongOpenHashSet tileChunksIn(int rx, int rz) {
      LongOpenHashSet out = new LongOpenHashSet();

      for (int lsx = 0; lsx < 16; lsx++) {
         for (int lsz = 0; lsz < 16; lsz++) {
            int sx = (rx << 4) + lsx;
            int sz = (rz << 4) + lsz;
            if (this.hasColumn(sx, sz)) {
               // Two LOD-0 columns per tile chunk on each axis.
               out.add(regionKey(sx >> 1, sz >> 1));
            }
         }
      }

      return out;
   }
}
