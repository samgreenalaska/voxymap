package dev.local.voxymap.voxy;

import dev.local.voxymap.util.Log;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import java.util.function.BooleanSupplier;
import me.cortex.voxy.common.world.WorldEngine;

/**
 * Builds a {@link SectionIndex} by walking Voxy's storage.
 *
 * <p>This used to be two static methods on {@code SectionIndex} itself. They moved out when Distant
 * Horizons became a second backend: {@code SectionIndex} is on the path a Voxy-less client walks,
 * and a class whose method signatures name {@code WorldEngine} is a class that can fail to link
 * where Voxy is not installed. Nothing about the walk changed.
 *
 * <p>The storage backend holds a live iterator open for the whole callback, so the callback must
 * not call back into storage. Folding into the column map is pure arithmetic on a local hash map,
 * so it is safe to do inline -- and worth it: buffering first meant a 380k-entry
 * {@code LongArrayList} (~3 MB, repeatedly grown and copied) allocated on every rescan, plus a
 * second full pass over it. Positions arrive grouped by Y slice rather than by column, but the
 * fold is an OR into a bitset and does not care about order.
 */
public final class VoxyIndexScan {
   private VoxyIndexScan() {
   }

   /**
    * The cheap half of a rescan: per-region content hashes, without building the column map.
    *
    * @param abort see {@link #enumerate}
    * @return the hashes and how many sections Voxy is storing, or null if the walk was aborted
    */
   public static SectionIndex.FingerprintScan scanFingerprints(WorldEngine engine, BooleanSupplier abort) {
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
         if (sy < SectionIndex.Y_MIN || sy > SectionIndex.Y_MAX) {
            return;
         }

         long rk = SectionIndex.regionKey(WorldEngine.getX(pos) >> 4, WorldEngine.getZ(pos) >> 4);
         out.put(rk, out.get(rk) ^ SectionIndex.scramble(pos * 0x9E3779B97F4A7C15L));
      });

      return aborted[0] ? null : new SectionIndex.FingerprintScan(out, counter[0]);
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

         if (sy < SectionIndex.Y_MIN || sy > SectionIndex.Y_MAX) {
            counters[1]++;
            return;
         }

         long key = SectionIndex.columnKey(WorldEngine.getX(pos), WorldEngine.getZ(pos));
         columns.put(key, columns.get(key) | 1L << (sy + SectionIndex.Y_BIAS));
      });

      if (aborted[0]) {
         return null;
      }

      if (counters[1] > 0) {
         Log.warn(counters[1] + " LOD-0 sections sit outside the supported vertical range and were ignored");
      }

      return new SectionIndex(columns, counters[0], counters[1]);
   }
}
