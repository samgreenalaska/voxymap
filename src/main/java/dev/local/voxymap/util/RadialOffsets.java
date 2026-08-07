package dev.local.voxymap.util;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * (dx, dz) offsets within a radius, ordered nearest first.
 *
 * <p>Shared by everything in this mod that expands outward from a player: chunk generation and LOD
 * streaming both want the same thing, and it is the same table that had to be patched into
 * VoxyServer, whose scan walked square rings instead. A ring walk in Chebyshev distance --
 * {@code max(|dx|,|dz|) == d} -- delivers a cell on the diagonal at the same time as one 41% nearer
 * on an axis, so the frontier grows as a square and "nearest first" only holds along the axes.
 * Sorting the whole disc by {@code dx² + dz²} makes every cell strictly nearer than the one after
 * it.
 *
 * <p>Offsets are packed two shorts to an int. Tables are built once per radius and shared, since
 * they depend on nothing else; the cost is quadratic in the radius, hence {@link #MAX_RADIUS}.
 */
public final class RadialOffsets {
   /**
    * The largest radius a table can be built for.
    *
    * <p>This is the ceiling of the Chunkgen distance setting, and it has to be, because
    * {@link #forRadius} silently truncates to it -- a control offering a number this cannot honour
    * would be a control that lies about what it does.
    *
    * <p>It is not free. The table is quadratic: at 512 it is 823k offsets and 3.3 MB, at 2048 it is
    * 13.2M offsets and 53 MB, built once and then cached for the lifetime of the game. The sort
    * behind it is the same shape, so choosing the maximum costs a visible pause the first time.
    * That is a real cost of a real choice, which is the point -- the previous arrangement reached
    * radius 500 without anyone choosing anything.
    */
   public static final int MAX_RADIUS = 2048;

   private static final int[] EMPTY = new int[0];
   private static final Map<Integer, int[]> CACHE = new ConcurrentHashMap<>();

   private RadialOffsets() {
   }

   /** @return offsets inside {@code radius}, ordered by true distance, centre first */
   public static int[] forRadius(int radius) {
      if (radius <= 0) {
         return EMPTY;
      }

      return CACHE.computeIfAbsent(Math.min(radius, MAX_RADIUS), RadialOffsets::build);
   }

   public static int dx(int packed) {
      return (short) (packed >> 16);
   }

   public static int dz(int packed) {
      return (short) packed;
   }

   private static int[] build(int radius) {
      final int r = radius;
      final long radiusSq = (long) r * (long) r;

      // Counted first rather than allocating the bounding square and copying down to size. The disc
      // is only π/4 of the square, so the old way peaked at 134 MB of long[] at MAX_RADIUS to keep
      // 105 MB, on top of the copy -- worth two cheap passes to avoid on the heap this runs on.
      int n = 0;

      for (int dx = -r; dx <= r; dx++) {
         for (int dz = -r; dz <= r; dz++) {
            if ((long) dx * (long) dx + (long) dz * (long) dz <= radiusSq) {
               n++;
            }
         }
      }

      // Key is (distanceSquared << 32) | packedOffset. distanceSquared is at most 2r², well inside
      // 31 bits at any radius accepted here, so every key is non-negative and an ascending sort of
      // the longs is an ascending sort by distance.
      long[] ordered = new long[n];
      int i = 0;

      for (int dx = -r; dx <= r; dx++) {
         for (int dz = -r; dz <= r; dz++) {
            long distSq = (long) dx * (long) dx + (long) dz * (long) dz;

            if (distSq > radiusSq) {
               continue;
            }

            int packed = ((dx & 0xFFFF) << 16) | (dz & 0xFFFF);
            ordered[i++] = (distSq << 32) | (packed & 0xFFFFFFFFL);
         }
      }

      Arrays.sort(ordered);

      int[] offsets = new int[n];

      for (int j = 0; j < n; j++) {
         offsets[j] = (int) ordered[j];
      }

      return offsets;
   }
}
