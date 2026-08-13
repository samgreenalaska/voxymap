package dev.local.voxymap.dh;

import com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataCache;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import dev.local.voxymap.lod.TileScanner;
import dev.local.voxymap.voxy.ColumnData;
import dev.local.voxymap.voxy.PaletteSnapshot;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Turns Distant Horizons' stored terrain into per-column surface data, one Xaero tile chunk at a
 * time. The DH counterpart of {@code ColumnScanner}.
 *
 * <p>The column walk is the same walk — {@code MapWriter.loadPixel}'s, in Xaero's own order:
 * invisible, then overlay, then colourless, then opaque, stopping at the first block with a real
 * map colour. What differs is the shape of the input. Voxy stores a voxel per block and the scanner
 * descends 32 of them at a time; DH stores <em>runs</em>, each one a block state and biome over a
 * span of Y. So the same walk visits a handful of entries per column instead of a few hundred, and
 * a run's depth is read off the entry rather than counted.
 *
 * <h2>What "no data" looks like here</h2>
 *
 * <p>DH answers a chunk it has never seen with nothing at all, which is the honest and useful
 * answer: the column is left incomplete, no tile is written, and Xaero's own scan still owns that
 * ground. The mistake this must not make is the other one — inventing a surface for a column whose
 * real surface DH has not stored — so a column that runs out of runs before finding a coloured
 * block is left unresolved rather than given the deepest thing seen.
 */
public final class DhTileScanner implements TileScanner {
   /** Xaero folds transparency into a single overlay past this depth; so do we. */
   private static final int MAX_TRANSPARENCY_BLEND_DEPTH = 5;

   private final IDhApiLevelWrapper dhLevel;
   private final Level level;
   private final PaletteSnapshot palette;
   private final Registry<Biome> biomeRegistry;
   private final int worldMinY;
   private final int overlayStride;
   private final IDhApiTerrainDataCache cache;

   private int noDataColumns;
   private int shallowColumns;
   private int voidColumns;
   private int filledColumns;

   /** Chunks DH had something for, out of the sixteen in a tile chunk. Reported by /voxymap debug. */
   public int chunksWithData;

   public DhTileScanner(
      IDhApiLevelWrapper dhLevel,
      Level level,
      PaletteSnapshot palette,
      int worldMinY,
      int overlayStride,
      IDhApiTerrainDataCache cache
   ) {
      this.dhLevel = dhLevel;
      this.level = level;
      this.palette = palette;
      this.biomeRegistry = level.registryAccess().lookupOrThrow(Registries.BIOME);
      this.worldMinY = worldMinY;
      this.overlayStride = overlayStride;
      this.cache = cache;
   }

   @Override
   public void resetCounters() {
      this.noDataColumns = 0;
      this.shallowColumns = 0;
      this.voidColumns = 0;
      this.filledColumns = 0;
      this.chunksWithData = 0;
   }

   @Override
   public Counters counters() {
      // gap, estimated and probed stay zero. DH hands back a run list, and a list that stops short
      // is indistinguishable from one with a hole in it; there is no index to probe against either.
      // Reporting a number nothing measured would be worse than reporting none.
      return new Counters(this.noDataColumns, 0, this.shallowColumns, 0, this.filledColumns, this.voidColumns, 0, 0);
   }

   @Override
   public ColumnData scanTileChunk(int generation, int tileChunkX, int tileChunkZ) {
      ColumnData out = new ColumnData(generation, tileChunkX, tileChunkZ, this.overlayStride);
      boolean any = false;

      // A tile chunk is 4x4 chunks and DH is asked one chunk at a time: there is no coarser read
      // that comes back at the same detail.
      for (int dz = 0; dz < 4; dz++) {
         for (int dx = 0; dx < 4; dx++) {
            int chunkX = (tileChunkX << 2) + dx;
            int chunkZ = (tileChunkZ << 2) + dz;
            DhApiTerrainDataPoint[][][] data = DhBridge.chunkData(this.dhLevel, chunkX, chunkZ, this.cache);

            if (data == null || data.length == 0) {
               this.markChunkIncomplete(out, dx << 4, dz << 4);
               this.noDataColumns += 256;
               continue;
            }

            any = true;
            this.chunksWithData++;
            this.scanChunk(out, data, dx << 4, dz << 4);
         }
      }

      if (!any) {
         return null;
      }

      this.filledColumns += out.fillFromNeighbours();
      return out;
   }

   /**
    * @param data DH's {@code [x][z][run]} array. Its width is the detail it answered at: 16 across
    *     for per-block data, fewer when all DH kept for that ground was a downsample. A coarse
    *     answer is stretched over the columns it covers rather than refused — it is the same
    *     ground described less precisely, and refusing it would leave a hole where DH has something
    *     to say.
    */
   private void scanChunk(ColumnData out, DhApiTerrainDataPoint[][][] data, int baseLx, int baseLz) {
      int side = data.length;
      int scale = Math.max(1, 16 / Math.max(1, side));

      for (int z = 0; z < 16; z++) {
         for (int x = 0; x < 16; x++) {
            int sx = Math.min(side - 1, x / scale);
            DhApiTerrainDataPoint[][] row = data[sx];
            DhApiTerrainDataPoint[] column = null;

            if (row != null && row.length > 0) {
               column = row[Math.min(row.length - 1, z / scale)];
            }

            this.scanColumn(out, column, baseLx + x, baseLz + z);
         }
      }
   }

   private void scanColumn(ColumnData out, DhApiTerrainDataPoint[] column, int lx, int lz) {
      int oi = ColumnData.index(lx, lz);

      if (column == null || column.length == 0) {
         this.markColumnIncomplete(out, lx, lz);
         this.noDataColumns++;
         return;
      }

      // DH returns a column top-down, but nothing in the api promises it, and a reversed one would
      // read the world upside down in silence -- every surface would come out as bedrock. One
      // comparison settles it.
      boolean topDown = column.length < 2 || column[0].topYBlockPos >= column[column.length - 1].topYBlockPos;

      int topHeight = Integer.MIN_VALUE;
      int biome = 0;
      int aboveBlockLight = 0;
      int runBlock = -1;
      int runOpacity = 0;
      int runLight = 0;
      int firstTransparentY = Integer.MIN_VALUE;
      boolean collapsing = false;
      boolean sawNonAir = false;
      int lowest = Integer.MAX_VALUE;

      for (int i = 0; i < column.length; i++) {
         DhApiTerrainDataPoint point = column[topDown ? i : column.length - 1 - i];

         if (point == null) {
            continue;
         }

         lowest = Math.min(lowest, point.bottomYBlockPos);
         BlockState state = stateOf(point);

         if (state == null || state.isAir()) {
            aboveBlockLight = point.blockLightLevel;
            continue;
         }

         sawNonAir = true;
         int id = DhPalette.idOf(state);

         if (this.palette.isInvisible(id)) {
            aboveBlockLight = point.blockLightLevel;
            continue;
         }

         if (this.palette.isTranslucent(id)) {
            int depth = Math.max(1, point.topYBlockPos - point.bottomYBlockPos + 1);

            if (topHeight == Integer.MIN_VALUE) {
               topHeight = point.topYBlockPos;
            }

            if (biome == 0) {
               biome = this.biomeIdOf(point);
            }

            if (firstTransparentY == Integer.MIN_VALUE) {
               firstTransparentY = point.topYBlockPos;
            }

            // Dampening is per block and a run is many blocks of one block, so the run darkens by
            // that much for every block it is deep -- the same total the Voxy scanner accumulates
            // one voxel at a time.
            int damp = Math.min(15, this.palette.dampeningOf(id) * depth);

            if (runBlock == id || collapsing && runBlock >= 0) {
               runOpacity = Math.min(15, runOpacity + damp);
            } else {
               this.closeRun(out, oi, runBlock, runOpacity, runLight);
               runBlock = id;
               runOpacity = damp;
               runLight = aboveBlockLight & 15;
            }

            if (firstTransparentY - point.bottomYBlockPos >= MAX_TRANSPARENCY_BLEND_DEPTH) {
               collapsing = true;
            }

            aboveBlockLight = point.blockLightLevel;
            continue;
         }

         if (this.palette.isColourless(id)) {
            aboveBlockLight = point.blockLightLevel;
            continue;
         }

         // Opaque, coloured and visible: this is the surface.
         this.closeRun(out, oi, runBlock, runOpacity, runLight);
         out.opaqueBlockId[oi] = id;
         out.height[oi] = (short)point.topYBlockPos;
         out.topHeight[oi] = (short)(topHeight == Integer.MIN_VALUE ? point.topYBlockPos : topHeight);
         out.biomeId[oi] = (short)(biome != 0 ? biome : this.biomeIdOf(point));
         // Xaero samples block light one block above the surface, which is the run we just left.
         out.light[oi] = (byte)(aboveBlockLight & 15);
         out.scannedColumns++;
         return;
      }

      this.closeRun(out, oi, runBlock, runOpacity, runLight);

      // Nothing coloured all the way down. Genuine void only if the runs reached the world floor
      // and there was something in the column at all -- the same test the Voxy scanner applies, for
      // the same reason: "air all the way down" is far more often ground nobody stored than ground
      // that is really empty, and painting Xaero's void colour over it is what drew black
      // rectangles across un-ingested chunks.
      if (lowest <= this.worldMinY && sawNonAir) {
         out.opaqueBlockId[oi] = 0;
         out.height[oi] = (short)this.worldMinY;
         out.topHeight[oi] = (short)(topHeight == Integer.MIN_VALUE ? this.worldMinY : topHeight);
         out.biomeId[oi] = (short)biome;
         out.light[oi] = 0;
         out.flags[oi] |= ColumnData.FLAG_VOID;
         out.scannedColumns++;
         this.voidColumns++;
      } else {
         this.markColumnIncomplete(out, lx, lz);
         this.shallowColumns++;
      }
   }

   private int biomeIdOf(DhApiTerrainDataPoint point) {
      if (point.biomeWrapper == null) {
         return 0;
      }

      try {
         Object wrapped = point.biomeWrapper.getWrappedMcObject();

         if (wrapped instanceof net.minecraft.core.Holder<?> holder && holder.value() instanceof Biome b) {
            return Math.max(0, this.biomeRegistry.getId(b));
         }

         if (wrapped instanceof Biome b) {
            return Math.max(0, this.biomeRegistry.getId(b));
         }
      } catch (Throwable t) {
         return 0;
      }

      return 0;
   }

   private static BlockState stateOf(DhApiTerrainDataPoint point) {
      if (point.blockStateWrapper == null) {
         return null;
      }

      try {
         return point.blockStateWrapper.getWrappedMcObject() instanceof BlockState state ? state : null;
      } catch (Throwable t) {
         return null;
      }
   }

   private void closeRun(ColumnData out, int oi, int runBlock, int runOpacity, int runLight) {
      if (runBlock < 0) {
         return;
      }

      int count = out.overlayCount[oi] & 0xFF;

      if (count < this.overlayStride) {
         int slot = oi * this.overlayStride + count;
         out.overlayBlockId[slot] = runBlock;
         out.overlayOpacity[slot] = (byte)runOpacity;
         out.overlayLight[slot] = (byte)runLight;
         out.overlayCount[oi] = (byte)(count + 1);
      } else if (count > 0) {
         int slot = oi * this.overlayStride + count - 1;
         out.overlayOpacity[slot] = (byte)Math.min(15, (out.overlayOpacity[slot] & 0xFF) + runOpacity);
      }
   }

   private void markColumnIncomplete(ColumnData out, int lx, int lz) {
      out.flags[ColumnData.index(lx, lz)] |= ColumnData.FLAG_INCOMPLETE;
      out.incompletePerChunk[ColumnData.chunkSlot(lx, lz)]++;
   }

   private void markChunkIncomplete(ColumnData out, int baseLx, int baseLz) {
      for (int z = 0; z < 16; z++) {
         for (int x = 0; x < 16; x++) {
            this.markColumnIncomplete(out, baseLx + x, baseLz + z);
         }
      }
   }
}
