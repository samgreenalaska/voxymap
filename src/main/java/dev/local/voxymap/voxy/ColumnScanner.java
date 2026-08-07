package dev.local.voxymap.voxy;

import dev.local.voxymap.util.Log;
import java.util.Arrays;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;

/**
 * Turns Voxy's LOD-0 voxel stacks into per-column surface data, one Xaero tile chunk at a time.
 *
 * <p>Runs on the sweep worker thread and touches nothing but Voxy. The column walk mirrors
 * {@code MapWriter.loadPixel}: descend from the top, skip invisible and colourless blocks,
 * accumulate translucent runs as overlays, stop at the first block that has a real map colour.
 *
 * <p>A section is acquired, copied, and released inside a single call -- never held across
 * anything that could block.
 */
public final class ColumnScanner {
   /** Xaero folds transparency into a single overlay past this depth; so do we. */
   private static final int MAX_TRANSPARENCY_BLEND_DEPTH = 5;

   private static final int STACK_SIDE = 32;
   private static final int STACK_COLUMNS = STACK_SIDE * STACK_SIDE;
   private static final int UNSET = Integer.MIN_VALUE;

   private final WorldEngine engine;
   private final SectionIndex index;
   private final PaletteSnapshot palette;
   private final int worldMinY;
   private final int worldMaxY;
   private final int overlayStride;

   private final long[] voxels = new long[WorldSection.SECTION_VOLUME];

   // Per-stack scratch, reused across tile chunks. One entry per column of a 32x32 stack.
   private final boolean[] done = new boolean[STACK_COLUMNS];
   private final int[] topH = new int[STACK_COLUMNS];
   private final int[] biome = new int[STACK_COLUMNS];
   private final int[] aboveLight = new int[STACK_COLUMNS];
   private final int[] runBlock = new int[STACK_COLUMNS];
   private final int[] runOpacity = new int[STACK_COLUMNS];
   private final int[] runLight = new int[STACK_COLUMNS];
   private final int[] firstTransparentY = new int[STACK_COLUMNS];
   private final boolean[] collapsing = new boolean[STACK_COLUMNS];
   /** Lowest y of the currently open translucent run, for the estimated-surface fallback. */
   private final int[] runLowY = new int[STACK_COLUMNS];
   /** Whether any non-air voxel at all was seen, which is what separates void from "no data". */
   private final boolean[] sawNonAir = new boolean[STACK_COLUMNS];

   /** Columns written as void this scan, for the per-region log. */
   public int voidColumns;

   // Why columns did not resolve, so /voxymap debug can say what the black specks actually are.

   /** Columns in a LOD-0 stack Voxy has never ingested at all. */
   public int noDataColumns;
   /** Columns abandoned at a hole in the middle of the stored stack. */
   public int gapColumns;
   /** Columns where the stored stack simply stopped before any surface was found. */
   public int shallowColumns;
   /** Columns given an inferred surface rather than being left undiscovered. */
   public int estimatedColumns;
   /** Columns copied from a resolved neighbour a few blocks away. */
   public int filledColumns;
   /** Sections found by direct probe that the index did not know about. See {@link #probeStack}. */
   public int probedSections;

   public void resetCounters() {
      this.voidColumns = 0;
      this.noDataColumns = 0;
      this.gapColumns = 0;
      this.shallowColumns = 0;
      this.estimatedColumns = 0;
      this.filledColumns = 0;
      this.probedSections = 0;
   }

   public ColumnScanner(WorldEngine engine, SectionIndex index, PaletteSnapshot palette, int worldMinY, int worldMaxY, int overlayStride) {
      this.engine = engine;
      this.index = index;
      this.palette = palette;
      this.worldMinY = worldMinY;
      this.worldMaxY = worldMaxY;
      this.overlayStride = overlayStride;
   }

   /** @return the scanned tile chunk, or null if Voxy turned out to have nothing here at all. */
   public ColumnData scanTileChunk(int generation, int tileChunkX, int tileChunkZ) {
      ColumnData out = new ColumnData(generation, tileChunkX, tileChunkZ, this.overlayStride);
      boolean any = false;

      for (int dsx = 0; dsx < 2; dsx++) {
         for (int dsz = 0; dsz < 2; dsz++) {
            int sx = (tileChunkX << 1) + dsx;
            int sz = (tileChunkZ << 1) + dsz;
            any |= this.scanStack(out, sx, sz, dsx * STACK_SIDE, dsz * STACK_SIDE);
         }
      }

      if (!any) {
         return null;
      }

      // Close the pinholes Voxy's storage leaves inside ground it otherwise knows. Bounded, so it
      // can only fill in, never extend the edge of coverage outward.
      this.filledColumns += out.fillFromNeighbours();
      return out;
   }

   /** @return true if this LOD-0 column stack had any stored data. */
   private boolean scanStack(ColumnData out, int sx, int sz, int baseLx, int baseLz) {
      long ySet = this.index.ySet(sx, sz);

      if (ySet == 0L) {
         // The index says nothing is here, and for freshly ingested ground the index is wrong.
         // Ask the engine directly before writing the column off.
         ySet = this.probeStack(sx, sz);
      } else {
         // The index says *something* is here, which used to be taken at face value -- and that is
         // the other half of §7.15, missed the first time round.
         //
         // The index cannot see sections that are still in RocksDB's memtable, and VoxyServer
         // delivers a column bottom-up (§7.19), so the sections it cannot see are precisely the
         // ones at the top: the surface. Walking down from the highest *flushed* section then finds
         // stone where grass is already stored, and writes a grey tile over ground Voxy has. That
         // is the grey speckle, and it is written from data that looked complete, so nothing marks
         // it suspect.
         //
         // Extending the stack upward by direct lookup costs a handful of point reads on a column
         // family tuned for exactly that (§7.15), and only above what the index already knows.
         ySet = this.probeAbove(sx, sz, ySet);
      }

      if (ySet == 0L) {
         // Voxy has never ingested here. Unknown, not empty.
         this.markIncomplete(out, baseLx, baseLz);
         this.noDataColumns += STACK_COLUMNS;
         return false;
      }

      this.resetScratch();

      int minSy = this.worldMinY >> 5;
      int maxSy = (this.worldMaxY - 1) >> 5;
      int topSy = Math.min(SectionIndex.highestSy(ySet), maxSy);
      int bottomSy = SectionIndex.lowestSy(ySet);
      int remaining = STACK_COLUMNS;
      boolean hitGap = false;
      boolean consumedAny = false;

      // Whether Voxy's data for this stack runs all the way to the world floor. If it does, nothing
      // is still in flight and a missing section can only mean the volume is empty -- see the hole
      // handling below.
      boolean completeToBottom = bottomSy <= minSy;

      for (int sy = topSy; sy >= bottomSy && remaining > 0; sy--) {
         if (!SectionIndex.hasSy(ySet, sy)) {
            if (!consumedAny) {
               // Nothing above it has been read yet, so there is no surface it could be hiding.
               // Only happens when the top of the stored stack is above the world's build limit.
               continue;
            }

            // A hole. Whether it is see-through or unknown depends on why the section is absent.
            //
            // Voxy stores a section if any voxel in it is non-air, so a missing section inside a
            // stack that reaches the world floor means all 32x32x32 of it is empty -- a genuine
            // band of open air, which you can see straight through from above. The first opaque
            // block below it is exactly what the map pixel should be. That is the common case in
            // dramatic terrain: a spire, an overhang or a floating island stored well above the
            // ground, with nothing in between.
            //
            // The reason SS7.7 refused to walk past a hole was that the surface might be hiding
            // inside it. That only applies when the section is absent because it has not arrived
            // yet, and an incomplete stack cannot reach the world floor: VoxyServer streams a
            // column bottom-up (SS7.19), so a stack that is still filling stops short at the top
            // rather than acquiring a hole in the middle. Requiring the floor is what separates the
            // two, and it is why `shallow` and `gap` never rise together.
            if (completeToBottom) {
               continue;
            }

            hitGap = true;
            break;
         }

         WorldSection section = null;

         try {
            section = this.engine.acquireIfExists(0, sx, sy, sz);
            if (section == null) {
               // Enumerated a moment ago but gone now; treat exactly like a hole.
               hitGap = true;
               break;
            }

            section.copyDataTo(this.voxels);
         } catch (Throwable t) {
            Log.warn("failed reading LOD-0 section " + sx + "," + sy + "," + sz, t);
            hitGap = true;
            break;
         } finally {
            if (section != null) {
               try {
                  section.release();
               } catch (Throwable t) {
                  Log.warn("failed releasing a Voxy section", t);
               }
            }
         }

         consumedAny = true;
         remaining = this.consumeSection(out, sy, baseLx, baseLz, remaining);
      }

      if (remaining > 0) {
         // Columns that never found a surface. Genuine void only if the data reached world bottom.
         boolean reachedBottom = !hitGap && completeToBottom;
         this.finishUnresolved(out, baseLx, baseLz, reachedBottom, hitGap);
      }

      return true;
   }

   /**
    * Builds a column's Y bitset by asking the engine for each section in turn.
    *
    * <p>{@link SectionIndex} is built from {@code storage.iteratePositions}, which on Voxy's RocksDB
    * backend returns only what has been flushed to SST files. The world-sections column family is
    * created with {@code optimizeForPointLookup}, and a range scan over it does not see the
    * memtable -- so everything Voxy has ingested since its last flush is invisible to the index,
    * while remaining perfectly readable by key.
    *
    * <p>That is normally hidden: on a long-lived database almost everything has been flushed, and
    * the unflushed tail is a few megabytes at the edge of a huge map. On a fresh database it is the
    * whole map. Wiping and re-exploring showed it plainly -- Voxy wrote 34 MB into the write-ahead
    * log while our section count sat frozen at the 4141 that had reached an SST.
    *
    * <p>So a column the index calls empty gets probed by key, which is a point lookup and exactly
    * what that column family is tuned for. Bounded by the world's height, twelve sections for a
    * standard world, and only ever reached for columns the index has nothing for.
    *
    * @return the Y bitset actually present, or 0 if the column really is empty
    */
   /**
    * Extends a known stack upward with sections the index cannot see yet.
    *
    * @param ySet what the index knows, which is never empty here
    * @return ySet plus every section that actually exists above the highest one in it
    */
   private long probeAbove(int sx, int sz, long ySet) {
      int maxSy = (this.worldMaxY - 1) >> 5;
      int from = SectionIndex.highestSy(ySet) + 1;

      for (int sy = from; sy <= maxSy; sy++) {
         WorldSection section = null;

         try {
            section = this.engine.acquireIfExists(0, sx, sy, sz);

            if (section != null) {
               ySet |= 1L << (sy + SectionIndex.Y_BIAS);
               this.probedSections++;
            }
         } catch (Throwable t) {
            return ySet;
         } finally {
            if (section != null) {
               try {
                  section.release();
               } catch (Throwable ignored) {
               }
            }
         }
      }

      return ySet;
   }

   private long probeStack(int sx, int sz) {
      long ySet = 0L;
      int minSy = this.worldMinY >> 5;
      int maxSy = (this.worldMaxY - 1) >> 5;

      for (int sy = minSy; sy <= maxSy; sy++) {
         WorldSection section = null;

         try {
            section = this.engine.acquireIfExists(0, sx, sy, sz);
            if (section != null) {
               ySet |= 1L << (sy + SectionIndex.Y_BIAS);
               this.probedSections++;
            }
         } catch (Throwable t) {
            return ySet;
         } finally {
            if (section != null) {
               try {
                  section.release();
               } catch (Throwable ignored) {
               }
            }
         }
      }

      return ySet;
   }

   /** Walks one 32-block-tall section for every column that has not yet found its surface. */
   private int consumeSection(ColumnData out, int sy, int baseLx, int baseLz, int remaining) {
      int baseY = sy << 5;

      for (int vz = 0; vz < STACK_SIDE; vz++) {
         for (int vx = 0; vx < STACK_SIDE; vx++) {
            int c = vx + vz * STACK_SIDE;
            if (this.done[c]) {
               continue;
            }

            int oi = ColumnData.index(baseLx + vx, baseLz + vz);

            for (int vy = STACK_SIDE - 1; vy >= 0; vy--) {
               long voxel = this.voxels[(vy << 10) | (vz << 5) | vx];
               int blockId = Mapper.getBlockId(voxel);
               int y = baseY + vy;

               if (blockId != 0) {
                  this.sawNonAir[c] = true;
               }

               // The order is MapWriter.loadPixelHelp's, and it matters: invisible, then overlay,
               // then colourless, then opaque. Testing "no map colour" before "is an overlay"
               // would drop translucent blocks that have no colour of their own.
               if (blockId == 0 || this.palette.isInvisible(blockId)) {
                  this.aboveLight[c] = Mapper.getLightId(voxel);
                  continue;
               }

               if (this.palette.isTranslucent(blockId)) {
                  this.addTranslucent(out, c, oi, blockId, Mapper.getBiomeId(voxel), y);
                  this.aboveLight[c] = Mapper.getLightId(voxel);
                  continue;
               }

               if (this.palette.isColourless(blockId)) {
                  this.aboveLight[c] = Mapper.getLightId(voxel);
                  continue;
               }

               // Opaque, coloured and visible: this is the surface.
               this.closeRun(out, c, oi);
               out.opaqueBlockId[oi] = blockId;
               out.height[oi] = (short)y;
               out.topHeight[oi] = (short)(this.topH[c] == UNSET ? y : this.topH[c]);
               out.biomeId[oi] = (short)(this.biome[c] >= 0 ? this.biome[c] : Mapper.getBiomeId(voxel));
               // Xaero samples block light one block above the surface; so does Voxy's own stack.
               out.light[oi] = (byte)((this.aboveLight[c] >>> 4) & 15);
               out.scannedColumns++;
               this.done[c] = true;
               remaining--;
               break;
            }
         }
      }

      return remaining;
   }

   private void addTranslucent(ColumnData out, int c, int oi, int blockId, int biomeId, int y) {
      if (this.topH[c] == UNSET) {
         this.topH[c] = y;
      }

      if (this.biome[c] < 0) {
         this.biome[c] = biomeId;
      }

      if (this.firstTransparentY[c] == UNSET) {
         this.firstTransparentY[c] = y;
      }

      int damp = this.palette.dampeningOf(blockId);

      if (this.runBlock[c] == blockId || this.collapsing[c] && this.runBlock[c] >= 0) {
         // Same material, or deep enough that Xaero would have stopped distinguishing them.
         this.runOpacity[c] = Math.min(15, this.runOpacity[c] + damp);
      } else {
         this.closeRun(out, c, oi);
         this.runBlock[c] = blockId;
         this.runOpacity[c] = Math.min(15, damp);
         this.runLight[c] = (this.aboveLight[c] >>> 4) & 15;
      }

      this.runLowY[c] = y;

      if (this.firstTransparentY[c] - y >= MAX_TRANSPARENCY_BLEND_DEPTH) {
         this.collapsing[c] = true;
      }
   }

   /**
    * Writes the open translucent run into the column's overlay list. If the list is already full
    * the run is merged into the last entry, which is what Xaero's own opacity accumulation does.
    */
   private void closeRun(ColumnData out, int c, int oi) {
      if (this.runBlock[c] < 0) {
         return;
      }

      int count = out.overlayCount[oi] & 0xFF;
      if (count < this.overlayStride) {
         int slot = oi * this.overlayStride + count;
         out.overlayBlockId[slot] = this.runBlock[c];
         out.overlayOpacity[slot] = (byte)this.runOpacity[c];
         out.overlayLight[slot] = (byte)this.runLight[c];
         out.overlayCount[oi] = (byte)(count + 1);
      } else if (count > 0) {
         int slot = oi * this.overlayStride + count - 1;
         out.overlayOpacity[slot] = (byte)Math.min(15, (out.overlayOpacity[slot] & 0xFF) + this.runOpacity[c]);
      }

      this.runBlock[c] = -1;
      this.runOpacity[c] = 0;
      this.runLight[c] = 0;
   }

   private void finishUnresolved(ColumnData out, int baseLx, int baseLz, boolean reachedBottom, boolean hitGap) {
      for (int vz = 0; vz < STACK_SIDE; vz++) {
         for (int vx = 0; vx < STACK_SIDE; vx++) {
            int c = vx + vz * STACK_SIDE;
            if (this.done[c]) {
               continue;
            }

            int lx = baseLx + vx;
            int lz = baseLz + vz;
            int oi = ColumnData.index(lx, lz);

            // The column is see-through as far down as Voxy stored anything. Almost always ocean:
            // deep water whose floor was never ingested.
            //
            // Leaving it undiscovered is what drew the fine black speckle across the seas. It is
            // the honest answer for a column with nothing in it, but this column is not empty; we
            // know it is full of water and only the depth is unknown. So the deepest run we did
            // see becomes the surface. That is wrong about the height and right about the colour
            // and the biome, which is the same picture the ocean had before water became an
            // overlay -- and much closer to the truth than a black hole in the middle of a sea.
            //
            // The open run is used directly rather than closed into the overlay list, so the
            // pixel comes out as plain water rather than water tinted by itself.
            //
            // A hole below the run is allowed here, unlike everywhere else a hole is treated as
            // "we cannot know". The reason the surface could be hiding inside a gap does not
            // apply once we are already inside a see-through run: whatever the floor is, the
            // pixel is the colour of the water above it. Only 768 of 465k unresolved columns in
            // the first measured sweep qualified without this, which is why it is allowed.
            if (!reachedBottom && this.runBlock[c] >= 0) {
               out.opaqueBlockId[oi] = this.runBlock[c];
               out.height[oi] = (short)this.runLowY[c];
               out.topHeight[oi] = (short)(this.topH[c] == UNSET ? this.runLowY[c] : this.topH[c]);
               out.biomeId[oi] = (short)this.biome[c];
               out.light[oi] = (byte)this.runLight[c];
               out.flags[oi] |= ColumnData.FLAG_ESTIMATED;
               out.scannedColumns++;
               this.estimatedColumns++;
               this.runBlock[c] = -1;
               continue;
            }

            this.closeRun(out, c, oi);

            // A column has to have contained *something* before we are willing to call it void.
            //
            // A LOD-0 section spans 2x2 chunks. When Voxy has ingested only some of those chunks
            // the section still exists, with air in the parts it never saw -- so a column there
            // looks exactly like empty space that reaches the world bottom. Writing that as void
            // paints it with Xaero's VOID_COLOR (8, 10, 23), which is what drew a black outline
            // around the edge of Voxy's coverage and black rectangles over un-ingested chunks.
            //
            // Any real column that reaches the world bottom has bedrock in it, so "all air, all
            // the way down" means no data rather than no blocks. Marking it incomplete leaves the
            // ground undiscovered, which is the honest answer and the safe direction. Genuine void
            // (End islands, superflat) also lands here and is likewise left undiscovered.
            if (reachedBottom && this.sawNonAir[c]) {
               // Xaero writes air at world bottom for a genuinely empty column and paints void.
               out.opaqueBlockId[oi] = 0;
               out.height[oi] = (short)this.worldMinY;
               out.topHeight[oi] = (short)(this.topH[c] == UNSET ? this.worldMinY : this.topH[c]);
               out.biomeId[oi] = (short)this.biome[c];
               out.light[oi] = 0;
               out.flags[oi] |= ColumnData.FLAG_VOID;
               out.scannedColumns++;
               this.voidColumns++;
            } else {
               out.flags[oi] |= ColumnData.FLAG_INCOMPLETE;
               out.incompletePerChunk[ColumnData.chunkSlot(lx, lz)]++;

               if (hitGap) {
                  this.gapColumns++;
               } else {
                  this.shallowColumns++;
               }
            }
         }
      }
   }

   private void markIncomplete(ColumnData out, int baseLx, int baseLz) {
      for (int vz = 0; vz < STACK_SIDE; vz++) {
         for (int vx = 0; vx < STACK_SIDE; vx++) {
            int lx = baseLx + vx;
            int lz = baseLz + vz;
            out.flags[ColumnData.index(lx, lz)] |= ColumnData.FLAG_INCOMPLETE;
            out.incompletePerChunk[ColumnData.chunkSlot(lx, lz)]++;
         }
      }
   }

   private void resetScratch() {
      Arrays.fill(this.done, false);
      Arrays.fill(this.topH, UNSET);
      Arrays.fill(this.biome, -1);
      Arrays.fill(this.aboveLight, 0);
      Arrays.fill(this.runBlock, -1);
      Arrays.fill(this.runOpacity, 0);
      Arrays.fill(this.runLight, 0);
      Arrays.fill(this.firstTransparentY, UNSET);
      Arrays.fill(this.collapsing, false);
      Arrays.fill(this.runLowY, 0);
      Arrays.fill(this.sawNonAir, false);
   }
}
