package dev.local.voxymap.xaero;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import xaero.map.MapProcessor;
import xaero.map.region.MapRegion;
import xaero.map.region.MapTile;
import xaero.map.region.MapTileChunk;

/**
 * Read-only dumps of Xaero's live state for a region.
 *
 * <p>This exists because "the tile was written" and "the tile is on screen" are separated by a
 * pipeline with several independent ways to drop the data: the region can fall out of
 * {@code toProcess}, the tile chunk can be demoted and cleaned, the texture can be stranded
 * waiting for a buffer rebuild, or the region can be cleared after saving because its world/mw ids
 * no longer match the processor's. Each of those looks identical from the outside.
 */
public final class Diagnostics {
   private Diagnostics() {
   }

   /**
    * The three ids a region was created with, against the ones the processor currently has.
    *
    * <p>If these diverge, {@code MapSaveLoad}'s drain calls {@code region.clearRegion(...)} after
    * saving instead of keeping the data. In multiplayer the multiworld id can be confirmed after
    * the session begins, which makes this a live risk rather than a theoretical one.
    */
   public static String identity(MapProcessor mp, MapRegion region) {
      String regionIds = region.getWorldId() + " / " + region.getDimId() + " / " + region.getMwId();
      String processorIds = mp.getCurrentWorldId() + " / " + mp.getCurrentDimId() + " / " + mp.getCurrentMWId();
      boolean match;

      try {
         match = region.getWorldId() != null && mp.isEqual(region.getWorldId(), region.getDimId(), region.getMwId());
      } catch (Throwable t) {
         match = false;
      }

      return "region[" + regionIds + "] processor[" + processorIds + "] match=" + match;
   }

   /** Whether the region actually landed on disk, which separates a write fault from a render fault. */
   public static String saveFile(MapRegion region) {
      try {
         File f = region.getRegionFile();
         if (f == null) {
            return "regionFile=null saveExists=" + region.getSaveExists();
         }

         return "regionFile=" + f.getAbsolutePath() + " exists=" + f.exists() + " bytes=" + (f.exists() ? f.length() : -1L)
            + " saveExists=" + region.getSaveExists();
      } catch (Throwable t) {
         return "regionFile=<threw " + t + ">";
      }
   }

   public static String regionState(MapRegion region) {
      return "loadState=" + region.getLoadState()
         + " resting=" + region.isResting()
         + " beingWritten=" + region.isBeingWritten()
         + " refreshing=" + region.isRefreshing()
         + " shouldBeProcessed=" + region.shouldBeProcessed()
         + " hasHadTerrain=" + region.hasHadTerrain()
         + " normalMapData=" + region.isNormalMapData()
         + " caveLayer=" + region.getCaveLayer()
         + " lastSaveTime=" + region.getLastSaveTime()
         + " sinceVisit=" + region.getTimeSinceVisit() + "ms";
   }

   /**
    * Tile chunks still waiting for a texture rebuild.
    *
    * <p>Once a region has been settled and flushed this should be zero. Anything else means a
    * rebuild was flagged and then stranded, which is the failure that kept tiles invisible until
    * a world reload.
    */
   public static int pendingBufferUpdates(MapRegion region) {
      int pending = 0;

      for (int i = 0; i < 8; i++) {
         for (int j = 0; j < 8; j++) {
            MapTileChunk tc = region.getChunk(i, j);
            if (tc != null && tc.getToUpdateBuffers()) {
               pending++;
            }
         }
      }

      return pending;
   }

   /** Counts across the region's 64 tile chunks: the single most useful "is it renderable" read. */
   public static String tileChunkSummary(MapRegion region) {
      int present = 0;
      int loaded2 = 0;
      int pendingBuffers = 0;
      int uploaded = 0;
      int includeInSave = 0;
      int hasTerrain = 0;
      int tilesLoaded = 0;
      int tilesOurs = 0;
      int tilesXaero = 0;

      for (int i = 0; i < 8; i++) {
         for (int j = 0; j < 8; j++) {
            MapTileChunk tc = region.getChunk(i, j);
            if (tc == null) {
               continue;
            }

            present++;
            if (tc.getLoadState() == 2) {
               loaded2++;
            }

            if (tc.getToUpdateBuffers()) {
               pendingBuffers++;
            }

            try {
               if (tc.getLeafTexture().isUploaded()) {
                  uploaded++;
               }
            } catch (Throwable ignored) {
            }

            if (tc.includeInSave()) {
               includeInSave++;
            }

            if (tc.hasHadTerrain()) {
               hasTerrain++;
            }

            for (int x = 0; x < 4; x++) {
               for (int z = 0; z < 4; z++) {
                  MapTile tile = tc.getTile(x, z);
                  if (tile == null || !tile.isLoaded()) {
                     continue;
                  }

                  tilesLoaded++;
                  if (tile.getWorldInterpretationVersion() == XaeroBridge.VOXY_MARK) {
                     tilesOurs++;
                  } else {
                     tilesXaero++;
                  }
               }
            }
         }
      }

      return "tileChunks present=" + present
         + " loadState2=" + loaded2
         + " pendingBufferUpdate=" + pendingBuffers
         + " uploaded=" + uploaded
         + " includeInSave=" + includeInSave
         + " hasHadTerrain=" + hasTerrain
         + " | tiles loaded=" + tilesLoaded + " ours=" + tilesOurs + " xaero=" + tilesXaero;
   }

   /** Full dump for {@code /voxymap probe}. */
   public static List<String> probe(MapProcessor mp, int regionX, int regionZ) {
      List<String> out = new ArrayList<>();
      out.add("=== probe r(" + regionX + "," + regionZ + ") layer=" + XaeroBridge.SURFACE_LAYER);

      MapRegion region;

      try {
         // create = false: never fabricate a region just to look at it.
         region = mp.getLeafMapRegion(XaeroBridge.SURFACE_LAYER, regionX, regionZ, false);
      } catch (Throwable t) {
         out.add("getLeafMapRegion threw: " + t);
         return out;
      }

      if (region == null) {
         out.add("NOT IN MEMORY -- Xaero has no region object here right now.");
         out.add("(That is normal for ground far from the player; it does not mean the data is missing.)");
         out.add(identityOnly(mp));
         return out;
      }

      out.add(regionState(region));
      out.add(identity(mp, region));
      out.add(saveFile(region));
      out.add(tileChunkSummary(region));
      return out;
   }

   private static String identityOnly(MapProcessor mp) {
      return "processor[" + mp.getCurrentWorldId() + " / " + mp.getCurrentDimId() + " / " + mp.getCurrentMWId() + "]";
   }
}
