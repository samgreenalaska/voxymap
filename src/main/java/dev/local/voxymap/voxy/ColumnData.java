package dev.local.voxymap.voxy;

/**
 * Scan result for one Xaero tile chunk: 64x64 columns, laid out flat as {@code lx + lz * 64}.
 *
 * <p>A tile chunk is the atomic unit of this bridge because it is exactly 4x4 chunks == 2x2 LOD-0
 * section columns, and because Xaero computes terrain slopes from the tile chunk's 64x64 height
 * field. All 16 tiles have to be in place before the buffers are rebuilt or the slopes come out
 * wrong at the tile boundaries.
 *
 * <p>Everything here is plain arrays of primitives so the scanner thread can fill it without
 * touching a single Minecraft or Xaero object.
 */
public final class ColumnData {
   public static final int SIDE = 64;
   public static final int COLUMNS = SIDE * SIDE;

   public static final byte FLAG_INCOMPLETE = 1;
   public static final byte FLAG_VOID = 2;

   /** Surface inferred from the deepest see-through block, because the real floor is not stored. */
   public static final byte FLAG_ESTIMATED = 4;

   public final int generation;
   public final int tileChunkX;
   public final int tileChunkZ;
   public final int overlayStride;

   /** Voxy block id of the surface block; 0 when the column is void. */
   public final int[] opaqueBlockId = new int[COLUMNS];
   public final short[] height = new short[COLUMNS];
   public final short[] topHeight = new short[COLUMNS];
   public final short[] biomeId = new short[COLUMNS];
   public final byte[] light = new byte[COLUMNS];
   public final byte[] flags = new byte[COLUMNS];

   /** Translucent runs above the surface, top-down. Opacity is already dampening x run length. */
   public final int[] overlayBlockId;
   public final byte[] overlayOpacity;
   public final byte[] overlayLight;
   public final byte[] overlayCount = new byte[COLUMNS];

   /** Per-chunk tally so a chunk Voxy only half knows can be skipped as a whole. */
   public final short[] incompletePerChunk = new short[16];

   public int scannedColumns;

   public ColumnData(int generation, int tileChunkX, int tileChunkZ, int overlayStride) {
      this.generation = generation;
      this.tileChunkX = tileChunkX;
      this.tileChunkZ = tileChunkZ;
      this.overlayStride = overlayStride;
      this.overlayBlockId = new int[COLUMNS * overlayStride];
      this.overlayOpacity = new byte[COLUMNS * overlayStride];
      this.overlayLight = new byte[COLUMNS * overlayStride];
   }

   public static int index(int lx, int lz) {
      return lx + lz * SIDE;
   }

   /** Index of the 4x4 chunk grid slot (0..15) that a local column falls in. */
   public static int chunkSlot(int lx, int lz) {
      return (lx >> 4) + (lz >> 4) * 4;
   }

   public boolean isIncomplete(int i) {
      return (this.flags[i] & FLAG_INCOMPLETE) != 0;
   }

   public boolean isVoid(int i) {
      return (this.flags[i] & FLAG_VOID) != 0;
   }

   /**
    * How far an unresolved column will look for a resolved neighbour to copy. Deliberately short.
    *
    * <p>Four columns is about a quarter of a chunk: enough to close the pinholes that Voxy's
    * storage leaves scattered through ground it otherwise knows, and far too short to invent a
    * coastline. Distance alone is not enough to keep it honest though -- see
    * {@link #REQUIRED_ENCLOSING_SIDES}.
    */
   private static final int FILL_RADIUS = 4;

   /**
    * Copies unresolved columns from their nearest resolved neighbour.
    *
    * <p>The black speckle over the oceans was single chunks failing the completeness gate because a
    * handful of their 256 columns could not be resolved -- Voxy's stored stack for that column
    * stops before the surface, or has a hole in it. The chunk was then skipped whole and nothing
    * ever drew it, which reads as a black speck in the middle of ground the map otherwise knows.
    *
    * <p>Neither Voxy nor this mod can recover what was never stored, but a column enclosed by
    * resolved ground is not really unknown: the surface it is missing is almost certainly the
    * surface next to it. Copying that is a guess, and it is a far better one than a hole. The
    * enclosure test is what keeps it to filling in rather than extending outward.
    *
    * <p>Runs on the worker thread, after the scan and before the payload is handed over.
    */
   public int fillFromNeighbours() {
      int filled = 0;

      for (int lz = 0; lz < SIDE; lz++) {
         for (int lx = 0; lx < SIDE; lx++) {
            int i = index(lx, lz);
            if (!this.isIncomplete(i)) {
               continue;
            }

            int source = this.nearestResolved(lx, lz);
            if (source < 0) {
               continue;
            }

            this.opaqueBlockId[i] = this.opaqueBlockId[source];
            this.height[i] = this.height[source];
            this.topHeight[i] = this.topHeight[source];
            this.biomeId[i] = this.biomeId[source];
            this.light[i] = this.light[source];

            int overlays = this.overlayCount[source] & 0xFF;
            this.overlayCount[i] = (byte)overlays;

            for (int k = 0; k < overlays; k++) {
               this.overlayBlockId[i * this.overlayStride + k] = this.overlayBlockId[source * this.overlayStride + k];
               this.overlayOpacity[i * this.overlayStride + k] = this.overlayOpacity[source * this.overlayStride + k];
               this.overlayLight[i * this.overlayStride + k] = this.overlayLight[source * this.overlayStride + k];
            }

            this.flags[i] = (byte)(this.flags[i] & ~FLAG_INCOMPLETE | FLAG_ESTIMATED);
            this.incompletePerChunk[chunkSlot(lx, lz)]--;
            this.scannedColumns++;
            filled++;
         }
      }

      return filled;
   }

   /** Cardinal directions, as (dx, dz) steps. */
   private static final int[][] CARDINALS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

   /**
    * How many of the four cardinal directions must reach resolved ground before a column is filled.
    *
    * <p>This is what separates a hole from an edge, and getting it wrong is visible: filling on a
    * single resolved neighbour smears terrain outward across the frontier of Voxy's coverage,
    * because every column just past the edge has ground on one side of it. A column enclosed on
    * three sides is genuinely surrounded, and copying its surroundings is a reasonable guess. A
    * column with ground on one or two sides is the edge of the map, and the honest answer there is
    * to draw nothing.
    */
   private static final int REQUIRED_ENCLOSING_SIDES = 3;

   /**
    * Nearest resolved column, but only if this column is enclosed rather than on the frontier.
    *
    * <p>Void columns are not sources: copying "genuinely empty down to the world bottom" outward
    * would paint void over ground rather than fill a hole.
    *
    * @return the column to copy, or -1 to leave this one blank
    */
   private int nearestResolved(int lx, int lz) {
      int sides = 0;
      int best = -1;
      int bestDistance = Integer.MAX_VALUE;

      for (int[] step : CARDINALS) {
         for (int r = 1; r <= FILL_RADIUS; r++) {
            int nx = lx + step[0] * r;
            int nz = lz + step[1] * r;

            if (nx < 0 || nz < 0 || nx >= SIDE || nz >= SIDE) {
               break;
            }

            int n = index(nx, nz);
            if ((this.flags[n] & (FLAG_INCOMPLETE | FLAG_VOID)) != 0) {
               continue;
            }

            sides++;

            if (r < bestDistance) {
               bestDistance = r;
               best = n;
            }

            break;
         }
      }

      return sides >= REQUIRED_ENCLOSING_SIDES ? best : -1;
   }
}
