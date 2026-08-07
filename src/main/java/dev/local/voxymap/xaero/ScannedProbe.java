package dev.local.voxymap.xaero;

import xaero.map.region.MapTile;
import xaero.map.region.MapTileChunk;

/**
 * The "never overwrite genuinely scanned data" predicate.
 *
 * <p>{@code MapSaveLoad} reconstructs every saved tile into the tile chunk's array and calls
 * {@code setLoaded(true)} on it; tiles that were never written are stored as a {@code -1}
 * sentinel and come back null. So once a region has actually reached load state 2, "the tile
 * object is there and loaded" is an exact test for "this ground has been explored".
 *
 * <p>That is why {@link RegionSession} refuses to write to a region that is not at load state 2:
 * a freshly created region has an empty tile array that would silently shadow the on-disk data
 * and then overwrite it at the next save.
 */
public final class ScannedProbe {
   private ScannedProbe() {
   }

   public static boolean isOurs(MapTile tile) {
      return tile != null && tile.getWorldInterpretationVersion() == XaeroBridge.VOXY_MARK;
   }

   /**
    * @param reauthor allow replacing tiles this mod wrote on an earlier pass
    * @return true if writing this tile cannot destroy explored ground
    */
   public static boolean isWritable(MapTileChunk tileChunk, int insideX, int insideZ, boolean reauthor) {
      MapTile tile = tileChunk.getTile(insideX, insideZ);
      if (tile == null) {
         return true;
      }

      if (!tile.isLoaded()) {
         // A pooled tile that was never filled in. Nothing to lose.
         return true;
      }

      return reauthor && isOurs(tile);
   }

   public static boolean exists(MapTileChunk tileChunk, int insideX, int insideZ) {
      MapTile tile = tileChunk.getTile(insideX, insideZ);
      return tile != null && tile.isLoaded();
   }
}
