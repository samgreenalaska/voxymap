package dev.local.voxymap.xaero;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import xaero.map.MapProcessor;
import xaero.map.WorldMap;
import xaero.map.common.config.option.WorldMapProfiledConfigOptions;

/**
 * "Will Xaero's own writer map this chunk?" -- the one predicate that decides where this mod
 * defers and where it has to fill in.
 *
 * <p>Getting this wrong in either direction leaves a hole:
 *
 * <ul>
 *   <li>{@link TileWriter} skips a chunk only when the answer is yes. A false yes means neither
 *       writer ever touches the chunk and it stays black forever.
 *   <li>{@link ReclaimPass} deletes one of our tiles only when the answer is yes. A false yes
 *       means it deletes ground nothing will redraw.
 * </ul>
 *
 * <p>So both callers need certainty, and "unknown" has to answer no. That is why every lookup here
 * fails closed.
 *
 * <p>The predicate mirrors {@code MapWriter.writeChunk} exactly. It is far narrower than
 * "the client has this chunk":
 *
 * <ol>
 *   <li>The chunk must be within {@code MapWriter.getWriteDistance()} of the player, which is
 *       {@code min(writingDistanceConfig, min(32, effectiveRenderDistance))}. The client's chunk
 *       cache reaches further than that whenever the server's view distance exceeds the client's
 *       render distance, or the user lowered Xaero's writing distance.
 *   <li>The chunk itself must be loaded and not an {@code EmptyLevelChunk}.
 *   <li><b>All eight neighbours must be loaded too.</b> {@code writeChunk} computes
 *       {@code edgeChunk} over the 3x3 neighbourhood and refuses to write if any of it is missing,
 *       because the tile's slope and shading are derived from neighbouring heights. This alone
 *       makes the outermost ring of the loaded area permanently unwritable by Xaero.
 *   <li>Xaero only creates a tile where there is none if "load new chunks" is on.
 * </ol>
 *
 * <p>Xaero's write distance shrinks to 16 on a cave layer unless the full map screen is open. We
 * only ever write the surface, where {@code getCurrentCaveLayer()} is {@code Integer.MAX_VALUE} and
 * the clamp never applies, but it is mirrored unconditionally anyway: a smaller distance is the
 * safe direction for both callers.
 */
public final class XaeroCoverage {
   /** Returned by {@link #writeDistance} when Xaero's reach cannot be determined. */
   public static final int UNKNOWN = -1;

   private XaeroCoverage() {
   }

   /** The radius in chunks, around the player, inside which Xaero's writer will map. */
   public static int writeDistance(MapProcessor mp) {
      int limit;

      try {
         if (XaeroBridge.isUsingWorldSave(mp)) {
            limit = Integer.MAX_VALUE;
         } else {
            Integer configured = WorldMap.INSTANCE
               .getConfigs()
               .getClientConfigManager()
               .getEffective(WorldMapProfiledConfigOptions.WRITING_DISTANCE);
            limit = configured == null || configured < 0 ? Integer.MAX_VALUE : configured;
         }
      } catch (Throwable t) {
         return UNKNOWN;
      }

      try {
         int distance = Math.min(limit, Math.min(32, Minecraft.getInstance().options.getEffectiveRenderDistance()));

         if (mp.getCurrentCaveLayer() != Integer.MAX_VALUE) {
            distance = Math.min(16, distance);
         }

         return Math.max(0, distance);
      } catch (Throwable t) {
         return UNKNOWN;
      }
   }

   /** Whether Xaero will create a tile for a chunk that has none. Off means it never fills a hole. */
   public static boolean willCreateNewTiles(MapProcessor mp) {
      try {
         if (XaeroBridge.isUsingWorldSave(mp)) {
            return true;
         }

         Boolean loadNewChunks = WorldMap.INSTANCE
            .getConfigs()
            .getClientConfigManager()
            .getEffective(WorldMapProfiledConfigOptions.LOAD_NEW_CHUNKS);
         return Boolean.TRUE.equals(loadNewChunks);
      } catch (Throwable t) {
         return false;
      }
   }

   /**
    * Whether Xaero rewrites a chunk that already has a tile.
    *
    * <p>When it does, one of our tiles is replaced in place a couple of seconds after the chunk
    * loads and there is nothing to hand back -- which is the whole reason {@link ReclaimPass}
    * exists. Reads false if the answer cannot be determined, so an unreadable config leaves the
    * reclaim pass doing what it always did.
    */
   public static boolean willUpdateExistingTiles(MapProcessor mp) {
      try {
         if (XaeroBridge.isUsingWorldSave(mp)) {
            return true;
         }

         Boolean updateChunks = WorldMap.INSTANCE
            .getConfigs()
            .getClientConfigManager()
            .getEffective(WorldMapProfiledConfigOptions.UPDATE_CHUNKS);
         return Boolean.TRUE.equals(updateChunks);
      } catch (Throwable t) {
         return false;
      }
   }

   /**
    * Loaded and real, the way {@code writeChunk} tests both the chunk and its neighbours.
    * {@code hasChunk} is deliberately not used: it answers a different, much looser question.
    */
   public static boolean chunkPresent(Level level, int chunkX, int chunkZ) {
      if (level == null) {
         return false;
      }

      try {
         ChunkAccess chunk = level.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
         return chunk != null && !(chunk instanceof EmptyLevelChunk);
      } catch (Throwable t) {
         return false;
      }
   }

   /** The full predicate, for callers that look at a handful of scattered chunks. */
   public static boolean willWrite(Level level, int chunkX, int chunkZ, int playerChunkX, int playerChunkZ, int writeDistance) {
      if (writeDistance == UNKNOWN
         || Math.abs(chunkX - playerChunkX) > writeDistance
         || Math.abs(chunkZ - playerChunkZ) > writeDistance) {
         return false;
      }

      for (int dx = -1; dx <= 1; dx++) {
         for (int dz = -1; dz <= 1; dz++) {
            if (!chunkPresent(level, chunkX + dx, chunkZ + dz)) {
               return false;
            }
         }
      }

      return true;
   }
}
