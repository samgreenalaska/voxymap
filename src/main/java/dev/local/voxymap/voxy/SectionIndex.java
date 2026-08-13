package dev.local.voxymap.voxy;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * Which LOD-0 section columns the backing database has, and the key arithmetic everything shares.
 *
 * <p>The index itself is backend-neutral — a column key to a bitset of the heights present in it —
 * and so is every static here. What fills it is not: {@link VoxyIndexScan} walks Voxy's storage,
 * {@link dev.local.voxymap.dh.DhIndex} works from what Distant Horizons can be shown to hold. The
 * split is not tidiness. Voxy does not load at all on Windows-on-ARM, so anything the DH path
 * touches has to be free of Voxy's classes or the class loader takes the client down with it.
 */
public final class SectionIndex {
   /** sy is a signed byte in Voxy's key format; this bitset covers the part any real world uses. */
   public static final int Y_BIAS = 32;
   static final int Y_MIN = -32;
   static final int Y_MAX = 31;

   private final Long2LongOpenHashMap columns;
   private final int sectionCount;
   private final int outOfRange;

   SectionIndex(Long2LongOpenHashMap columns, int sectionCount, int outOfRange) {
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
    * Per-region content hashes: the cheap half of a rescan, without building the column map.
    *
    * <p>Building the map is what costs a second or two. The hashes need none of it: XOR of a
    * scrambled position is order-independent, so it folds into a map with one entry per Xaero
    * region, a few hundred at most, which stays in cache. In steady state the answer is "nothing
    * changed" and the column map is never built at all.
    *
    * <p>Detects sections appearing and disappearing, not a section's contents changing under a
    * position that already existed.
    */
   public record FingerprintScan(Long2LongOpenHashMap fingerprints, int sectionCount) {
   }

   /** Builds an index one section position at a time, for backends that cannot be iterated. */
   public static final class Builder {
      private final Long2LongOpenHashMap columns = new Long2LongOpenHashMap(1 << 12);
      private final Long2LongOpenHashMap fingerprints = new Long2LongOpenHashMap(512);
      private int sections;
      private int outOfRange;

      public Builder() {
         this.columns.defaultReturnValue(0L);
         this.fingerprints.defaultReturnValue(0L);
      }

      public void add(int sx, int sy, int sz) {
         if (sy < Y_MIN || sy > Y_MAX) {
            this.outOfRange++;
            return;
         }

         this.sections++;
         long key = columnKey(sx, sz);
         this.columns.put(key, this.columns.get(key) | 1L << (sy + Y_BIAS));

         long rk = regionKey(sx >> 4, sz >> 4);
         // The same scramble-and-XOR the Voxy scan uses, over the same section identity, so the
         // two backends produce fingerprints of the same shape and FingerprintStore cannot tell
         // them apart -- which is what lets one store serve both.
         this.fingerprints.put(rk, this.fingerprints.get(rk) ^ scramble(mix(sx, sy, sz) * 0x9E3779B97F4A7C15L));
      }

      /**
       * Folds an extra value into one region's fingerprint.
       *
       * <p>For a backend whose data can change without the index changing. Voxy needs none of this
       * -- a section appearing changes the position set, which changes the hash. Distant Horizons
       * never says when it has stored a chunk, so the regions it scans for itself are salted with
       * something that moves when they are worth re-reading. See {@code DhBackend}.
       */
      public void salt(long regionKey, long value) {
         this.fingerprints.put(regionKey, this.fingerprints.get(regionKey) ^ scramble(value));
      }

      public boolean isEmpty() {
         return this.sections == 0;
      }

      public SectionIndex build() {
         return new SectionIndex(this.columns, this.sections, this.outOfRange);
      }

      public FingerprintScan fingerprints() {
         return new FingerprintScan(this.fingerprints, this.sections);
      }

      private static long mix(int sx, int sy, int sz) {
         return ((long)sx & 0xFFFFFFL) << 40 | ((long)sz & 0xFFFFFFL) << 16 | (sy & 0xFFL);
      }
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

   static long scramble(long v) {
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
