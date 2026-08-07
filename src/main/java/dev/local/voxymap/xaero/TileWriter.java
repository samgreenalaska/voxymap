package dev.local.voxymap.xaero;

import dev.local.voxymap.voxy.ColumnData;
import dev.local.voxymap.voxy.PaletteSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import xaero.map.MapProcessor;
import xaero.map.region.MapBlock;
import xaero.map.region.MapTile;
import xaero.map.region.MapTileChunk;
import xaero.map.region.OverlayBuilder;

/**
 * Writes one scanned tile chunk into Xaero's in-memory structures. Main thread only.
 *
 * <p>Slopes are deliberately left unknown. {@code MapPixel.getPixelColours} notices
 * {@code slopeUnknown} and calls {@code fixHeightType(..., useSourceData = false, ...)}, which
 * reads neighbour heights out of the tile chunk's leaf texture -- and {@code MapTileChunk.setTile}
 * fills that texture for all 64x64 pixels. So as long as every tile of a tile chunk is set before
 * the buffers are rebuilt, the slopes come out identical to a real scan without us computing
 * anything.
 *
 * <p>Depth shading is derived from the stored height, so it is correct for free.
 */
public final class TileWriter {
   private final MapProcessor mp;
   private final PaletteSnapshot palette;
   private final OverlayBuilder overlayBuilder;
   private final int worldMinY;
   private final int caveStart;
   private final boolean reauthorAlways;
   private final int incompleteLimitPerChunk;

   /**
    * Whether our own tiles may be replaced in the region currently being written.
    *
    * <p>Per region, not per sweep, because staleness is a property of one region's Voxy data having
    * moved since we last authored it. See {@code SweepController.staleRegions}.
    */
   private boolean reauthor;

   /**
    * Loaded-chunk mask over the tile chunk's 4x4 chunks plus a one-chunk margin, so the 3x3
    * neighbourhood test costs nine array reads instead of nine chunk lookups.
    */
   private final boolean[] present = new boolean[36];

   /** Xaero's settings cannot change meaningfully inside a tick, and reading them is not free. */
   private static final long COVERAGE_SAMPLE_NANOS = 200_000_000L;

   private boolean coverageSampled;
   private long coverageSampledAt;
   private int sampledWriteDistance = XaeroCoverage.UNKNOWN;

   private int writeDistance = XaeroCoverage.UNKNOWN;
   private int playerChunkX;
   private int playerChunkZ;

   public int authored;
   public int existing;
   public int skippedIncomplete;
   public int skippedLoaded;

   /**
    * Chunks left to Xaero that have no tile yet -- ground that is currently blank and is only
    * going to be filled if Xaero gets to it. Unlike {@link #skippedLoaded}, this drops to zero once
    * the ground is drawn, which is what lets a region stop being re-swept.
    */
   public int deferredMissing;

   public TileWriter(MapProcessor mp, PaletteSnapshot palette, int worldMinY, boolean reauthor, double incompleteTolerance) {
      this.mp = mp;
      this.palette = palette;
      this.overlayBuilder = new OverlayBuilder(mp.getOverlayManager());
      this.worldMinY = worldMinY;
      this.caveStart = XaeroBridge.surfaceCaveStart(mp);
      this.reauthorAlways = reauthor;
      this.reauthor = reauthor;
      this.incompleteLimitPerChunk = (int)Math.floor(incompleteTolerance * 256.0);
   }

   /**
    * Sets whether our own tiles may be replaced for the region about to be written.
    *
    * <p>An explicit {@code --reauthor} sweep, or {@code reauthorOwnTiles} in the config, pins this
    * on for every region; otherwise the caller decides per region.
    */
   public void setRegionStale(boolean stale) {
      this.reauthor = this.reauthorAlways || stale;
   }

   /** Whether the region currently being written is having our own tiles replaced. */
   public boolean reauthoring() {
      return this.reauthor;
   }

   public void resetCounters() {
      this.authored = 0;
      this.existing = 0;
      this.skippedIncomplete = 0;
      this.skippedLoaded = 0;
      this.deferredMissing = 0;
   }

   /** Counts a tile chunk that was skipped before it was ever scanned, because it is fully mapped. */
   public void countFullyMapped() {
      this.existing += 16;
   }

   /**
    * Whether Xaero's own writer will map this chunk, so that leaving it alone is safe.
    *
    * <p>This has been wrong twice, in opposite directions, and both times it punched a hole in the
    * map. First it was a blanket radius around the player that was wider than Xaero's reach, which
    * left a permanent 21x21 chunk ring nobody wrote. Then it was
    * {@code getChunkSource().hasChunk(...)}, which is wider than Xaero's reach in a subtler way:
    * Xaero refuses to write any chunk with a missing neighbour, so the outermost ring of the
    * client's loaded area is never Xaero's to write, no matter how the distances line up. That
    * ring travels with the player, and since Voxy ingests a chunk at the moment it loads -- the
    * moment we start skipping it -- the ring was black on the leading edge and only filled in once
    * the player had moved past and the chunks unloaded.
    *
    * <p>{@link XaeroCoverage} is the exact predicate now, shared with {@link ReclaimPass} so the
    * two can never disagree about who owns a chunk.
    */
   private boolean xaeroWillMap(int insideX, int insideZ, int chunkX, int chunkZ) {
      if (this.writeDistance == XaeroCoverage.UNKNOWN
         || Math.abs(chunkX - this.playerChunkX) > this.writeDistance
         || Math.abs(chunkZ - this.playerChunkZ) > this.writeDistance) {
         return false;
      }

      // MapWriter.writeChunk's edgeChunk test: the 3x3 neighbourhood must all be loaded.
      for (int dx = 0; dx < 3; dx++) {
         for (int dz = 0; dz < 3; dz++) {
            if (!this.present[(insideX + dx) * 6 + insideZ + dz]) {
               return false;
            }
         }
      }

      return true;
   }

   /**
    * @return the number of tiles authored into this tile chunk
    */
   public int writeTileChunk(MapTileChunk tc, ColumnData data) {
      Level level = this.mp.getWorld();
      int baseChunkX = data.tileChunkX << 2;
      int baseChunkZ = data.tileChunkZ << 2;
      int wrote = 0;

      this.prepareCoverage(level, baseChunkX, baseChunkZ);

      for (int insideX = 0; insideX < 4; insideX++) {
         for (int insideZ = 0; insideZ < 4; insideZ++) {
            int slot = insideX + insideZ * 4;
            int chunkX = baseChunkX + insideX;
            int chunkZ = baseChunkZ + insideZ;

            if (this.xaeroWillMap(insideX, insideZ, chunkX, chunkZ)) {
               // Xaero will write this chunk from live block data, which is strictly better than
               // anything Voxy's LOD can give us. Leaving it is only safe because the predicate
               // above is Xaero's own, not an approximation of it.
               this.skippedLoaded++;

               if (!ScannedProbe.exists(tc, insideX, insideZ)) {
                  this.deferredMissing++;
               }

               continue;
            }

            if ((data.incompletePerChunk[slot] & 0xFFFF) > this.incompleteLimitPerChunk) {
               // Voxy does not know enough about this chunk to draw a trustworthy surface.
               this.skippedIncomplete++;
               continue;
            }

            if (!ScannedProbe.isWritable(tc, insideX, insideZ, this.reauthor)) {
               this.existing++;
               continue;
            }

            this.writeTile(tc, data, insideX, insideZ, chunkX, chunkZ);
            this.authored++;
            wrote++;
         }
      }

      return wrote;
   }

   /**
    * Samples Xaero's reach and the loaded-chunk neighbourhood once per tile chunk. The player
    * moves during a sweep, so this cannot be hoisted to construction time.
    */
   private void prepareCoverage(Level level, int baseChunkX, int baseChunkZ) {
      LocalPlayer player = Minecraft.getInstance().player;

      if (player == null) {
         this.writeDistance = XaeroCoverage.UNKNOWN;
         return;
      }

      long now = System.nanoTime();

      if (!this.coverageSampled || now - this.coverageSampledAt > COVERAGE_SAMPLE_NANOS) {
         this.coverageSampled = true;
         this.coverageSampledAt = now;
         // Xaero configured never to map a chunk it has no tile for means nothing else is going to
         // fill these in, so we author all of them.
         this.sampledWriteDistance = XaeroCoverage.willCreateNewTiles(this.mp) ? XaeroCoverage.writeDistance(this.mp) : XaeroCoverage.UNKNOWN;
      }

      this.writeDistance = this.sampledWriteDistance;
      this.playerChunkX = player.blockPosition().getX() >> 4;
      this.playerChunkZ = player.blockPosition().getZ() >> 4;

      if (this.writeDistance == XaeroCoverage.UNKNOWN) {
         return;
      }

      // Cheap rejection: the whole tile chunk plus its margin is out of Xaero's reach.
      if (Math.abs(baseChunkX + 2 - this.playerChunkX) > this.writeDistance + 3
         || Math.abs(baseChunkZ + 2 - this.playerChunkZ) > this.writeDistance + 3) {
         this.writeDistance = XaeroCoverage.UNKNOWN;
         return;
      }

      for (int dx = 0; dx < 6; dx++) {
         for (int dz = 0; dz < 6; dz++) {
            this.present[dx * 6 + dz] = XaeroCoverage.chunkPresent(level, baseChunkX - 1 + dx, baseChunkZ - 1 + dz);
         }
      }
   }

   private void writeTile(MapTileChunk tc, ColumnData data, int insideX, int insideZ, int chunkX, int chunkZ) {
      MapTile tile = tc.getTile(insideX, insideZ);
      if (tile == null) {
         tile = this.mp.getTilePool().get(XaeroBridge.tilePoolKey(this.mp), chunkX, chunkZ);
         tc.setChanged(true);
      }

      int stride = data.overlayStride;

      for (int x = 0; x < 16; x++) {
         for (int z = 0; z < 16; z++) {
            int oi = ColumnData.index((insideX << 4) + x, (insideZ << 4) + z);

            // Pooled tiles keep their MapBlock objects, exactly the way Xaero's own writer
            // recycles them. Reuse in place rather than allocating 256 per tile.
            MapBlock mb = tile.getBlock(x, z);
            if (mb == null) {
               mb = new MapBlock();
               tile.setBlock(x, z, mb);
            }

            mb.prepareForWriting(this.worldMinY);

            ResourceKey<Biome> biomeKey = this.palette.biomeOf(data.biomeId[oi]);
            byte light = (byte)(data.light[oi] & 15);

            this.overlayBuilder.startBuilding();
            int overlays = data.overlayCount[oi] & 0xFF;

            for (int k = 0; k < overlays; k++) {
               int s = oi * stride + k;
               BlockState overlayState = this.palette.stateOf(data.overlayBlockId[s]);
               this.overlayBuilder
                  .build(overlayState, data.overlayOpacity[s] & 0xFF, (byte)(data.overlayLight[s] & 15), this.mp, biomeKey);
            }

            this.overlayBuilder.finishBuilding(mb);

            int blockId = data.opaqueBlockId[oi];
            BlockState state = this.palette.stateOf(blockId);
            boolean glowing = blockId != 0 && this.palette.isGlowing(blockId);

            // cave = false: this bridge only ever writes the surface layer.
            mb.write(state, data.height[oi], data.topHeight[oi], biomeKey, light, glowing, false);
         }
      }

      tile.setWorldInterpretationVersion(XaeroBridge.VOXY_MARK);
      tile.setWrittenCave(this.caveStart, this.mp.getCaveModeDepthConfig());
      tc.setTile(insideX, insideZ, tile, this.mp.getBlockStateShortShapeCache(), this.mp);
      tile.setWrittenOnce(true);
      tile.setLoaded(true);
   }
}
