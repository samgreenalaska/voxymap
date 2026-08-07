package dev.local.voxymap.xaero;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import xaero.map.MapProcessor;
import xaero.map.region.MapRegion;
import xaero.map.region.MapTile;
import xaero.map.region.MapTileChunk;

/**
 * Hands ground back to Xaero once the player actually walks onto it.
 *
 * <p>{@code MapWriter.writeChunk} only rewrites a tile that already exists when the
 * "update chunks" option is on. If a user has it off, one of our synthetic tiles would block a
 * real scan of that chunk forever. Nulling the tile puts the chunk back in the
 * {@code mapTile == null && loadChunks} branch, so the next frame writes genuine data over it.
 *
 * <p>Cheap by construction: it only ever looks at tile chunks inside Xaero's own write distance,
 * a handful per tick.
 *
 * <p>Every deletion here is a bet that Xaero will redraw the chunk, and a lost bet is a black hole
 * in the middle of mapped ground. It used to bet on {@code hasChunk}, which is much weaker than
 * Xaero's actual write predicate -- notably Xaero will not write a chunk with a missing neighbour,
 * so the outer ring of the loaded area was being emptied and never refilled. {@link XaeroCoverage}
 * is now the single source of truth, shared with {@link TileWriter}.
 */
public final class ReclaimPass {
   private static final int TILE_CHUNKS_PER_TICK = 4;

   private int cursor;
   public int reclaimed;

   private int writeDistance;
   private int playerChunkX;
   private int playerChunkZ;

   public void tick(MapProcessor mp) {
      Minecraft mc = Minecraft.getInstance();
      LocalPlayer player = mc.player;
      if (player == null || !XaeroBridge.writable(mp)) {
         return;
      }

      // With "update chunks" on -- the default -- Xaero overwrites our tile in place within a
      // couple of seconds of the chunk loading. Deleting it first only opens a window where the
      // ground is missing, so there is nothing to do here at all.
      if (XaeroCoverage.willUpdateExistingTiles(mp)) {
         return;
      }

      // Xaero only creates a tile where there is none if "load new chunks" is on. With it off,
      // deleting one of ours removes the ground permanently.
      if (!XaeroCoverage.willCreateNewTiles(mp)) {
         return;
      }

      int radius = XaeroCoverage.writeDistance(mp);
      if (radius == XaeroCoverage.UNKNOWN) {
         return;
      }

      this.writeDistance = radius;
      this.playerChunkX = player.blockPosition().getX() >> 4;
      this.playerChunkZ = player.blockPosition().getZ() >> 4;

      int startTcX = (this.playerChunkX - radius) >> 2;
      int endTcX = (this.playerChunkX + radius) >> 2;
      int startTcZ = (this.playerChunkZ - radius) >> 2;
      int endTcZ = (this.playerChunkZ + radius) >> 2;

      int width = endTcX - startTcX + 1;
      int height = endTcZ - startTcZ + 1;
      int total = width * height;
      if (total <= 0) {
         return;
      }

      for (int n = 0; n < TILE_CHUNKS_PER_TICK; n++) {
         int i = Math.floorMod(this.cursor++, total);
         this.reclaimTileChunk(mp, startTcX + i % width, startTcZ + i / width);
      }
   }

   private void reclaimTileChunk(MapProcessor mp, int tileChunkX, int tileChunkZ) {
      MapRegion region;

      try {
         // create = false: never fabricate a region just to look for our own tiles.
         region = mp.getLeafMapRegion(XaeroBridge.SURFACE_LAYER, tileChunkX >> 3, tileChunkZ >> 3, false);
      } catch (Throwable t) {
         return;
      }

      if (region == null) {
         return;
      }

      synchronized (mp.renderThreadPauseSync) {
         if (mp.isWritingPaused() || mp.isWaitingForWorldUpdate()) {
            return;
         }

         synchronized (region.writerThreadPauseSync) {
            if (region.isWritingPaused()) {
               return;
            }

            MapTileChunk tc;

            synchronized (region) {
               if (region.getLoadState() != 2 || !region.isResting() || region.isRefreshing()) {
                  return;
               }

               tc = region.getChunk(tileChunkX & 7, tileChunkZ & 7);
            }

            if (tc == null || tc.getLoadState() != 2 || tc.getLeafTexture().shouldDownloadFromPBO()) {
               return;
            }

            boolean changed = false;

            for (int i = 0; i < 4; i++) {
               for (int j = 0; j < 4; j++) {
                  MapTile tile = tc.getTile(i, j);
                  if (!ScannedProbe.isOurs(tile)) {
                     continue;
                  }

                  // Only hand back ground Xaero can actually redraw. Anything else would just
                  // punch a hole in the map that nothing ever fills.
                  int chunkX = (tileChunkX << 2) + i;
                  int chunkZ = (tileChunkZ << 2) + j;
                  if (!XaeroCoverage.willWrite(mp.getWorld(), chunkX, chunkZ, this.playerChunkX, this.playerChunkZ, this.writeDistance)) {
                     continue;
                  }

                  region.setBeingWritten(true);
                  tc.setTile(i, j, null, mp.getBlockStateShortShapeCache(), mp);
                  mp.getTilePool().addToPool(tile);
                  this.reclaimed++;
                  changed = true;
               }
            }

            if (changed) {
               tc.setChanged(true);
               tc.setToUpdateBuffers(true);
               region.setLastSaveTime(0L);
            }
         }
      }
   }
}
