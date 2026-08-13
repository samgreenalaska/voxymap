package dev.local.voxymap.lod;

import dev.local.voxymap.voxy.ColumnData;

/**
 * Reads one Xaero tile chunk's worth of columns out of whichever LOD database is in use.
 *
 * <p>The tile chunk is the unit because Xaero derives terrain slopes from the whole 64x64 height
 * field, so all sixteen tiles have to be written together. Implementations run on the sweep worker
 * thread and must not touch Minecraft or Xaero state — everything they produce is the plain arrays
 * of {@link ColumnData}.
 */
public interface TileScanner {
   /** @return the scanned tile chunk, or null if the database had nothing here at all */
   ColumnData scanTileChunk(int generation, int tileChunkX, int tileChunkZ);

   void resetCounters();

   /**
    * Why columns did not resolve, so {@code /voxymap debug} can say what the black specks are.
    *
    * <p>Not every backend can distinguish every case: Voxy knows the difference between a hole in
    * the middle of a stack and a stack that stopped short, because it can see which sections exist.
    * DH answers a run list and a short one is just short. Fields a backend cannot tell apart stay
    * zero rather than being guessed at.
    *
    * @param noData columns the database has never stored anything for
    * @param gap columns abandoned at a hole in the middle of stored data
    * @param shallow columns where the stored data stopped before any surface was found
    * @param estimated columns given an inferred surface rather than left undiscovered
    * @param filled columns copied from a resolved neighbour a few blocks away
    * @param voided columns written as genuine void
    * @param probed sections found by direct lookup that the index did not know about
    * @param unclassified columns left for a later pass because the palette did not know a block yet
    */
   record Counters(int noData, int gap, int shallow, int estimated, int filled, int voided, int probed, int unclassified) {
      public static final Counters ZERO = new Counters(0, 0, 0, 0, 0, 0, 0, 0);
   }

   Counters counters();
}
