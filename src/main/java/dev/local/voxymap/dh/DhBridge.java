package dev.local.voxymap.dh;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataCache;
import com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataRepo;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import dev.local.voxymap.util.Log;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Everything this mod says to Distant Horizons, in one place.
 *
 * <p>DH is the LOD backend on clients where Voxy cannot run — Windows-on-ARM, where Voxy's
 * renderer has no working driver path. It plays both parts Voxy plays elsewhere: it is where
 * streamed LOD is put, and it is where the Xaero bridge reads terrain back out of.
 *
 * <h2>Two API surfaces, one supported</h2>
 *
 * <p>Writing goes through {@link IDhApiTerrainDataRepo#overwriteChunkDataAsync}, which takes a
 * vanilla {@code ChunkAccess} and runs it through the same ingest DH uses for chunks the client
 * loads for itself: wrapper, LOD build, mip, store, render. Nothing here reaches past the api
 * package, and nothing here needs DH on the server.
 *
 * <p>The world-generator override ({@code AbstractDhApiChunkWorldGenerator}) looks like the natural
 * fit and is not: on a multiplayer client DH builds a {@code DhClientLevel} whose LOD request state
 * is a <em>remote</em> retrieval queue, so a registered generator is never consulted. The write path
 * above is not gated on singleplayer.
 *
 * <h2>Everything is optional</h2>
 *
 * <p>DH is a suggestion, not a dependency, and this class is the only thing that loads its classes.
 * Every entry point checks {@link #available()} first, so on a client without DH nothing here is
 * ever linked.
 */
public final class DhBridge {
   private static Boolean modLoaded;

   /** Why the last read came back empty, when DH said why. Read by {@code /voxymap dh}. */
   private static volatile String lastReadMessage;

   public static String lastReadMessage() {
      return lastReadMessage;
   }

   private DhBridge() {
   }

   /** Whether DH is installed at all. Cheap after the first call; does not touch DH's classes. */
   public static boolean installed() {
      Boolean cached = modLoaded;

      if (cached == null) {
         cached = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("distanthorizons");
         modLoaded = cached;
      }

      return cached;
   }

   /**
    * Whether DH is installed <em>and</em> far enough through its own startup to be talked to.
    *
    * <p>{@code DhApi.Delayed} is exactly what its name says: the fields are null until DH has built
    * its world, so every caller has to re-ask rather than binding once.
    */
   public static boolean available() {
      return installed() && DhApi.Delayed.terrainRepo != null && DhApi.Delayed.worldProxy != null;
   }

   /** DH's version string, for {@code /voxymap status}. */
   public static String version() {
      if (!installed()) {
         return "absent";
      }

      try {
         return DhApi.getModVersion() + " (api " + DhApi.getApiMajorVersion() + "." + DhApi.getApiMinorVersion() + ")";
      } catch (Throwable t) {
         return "present, version unreadable";
      }
   }

   /**
    * The DH level for the level the client is in, or null if DH has not built one yet.
    *
    * <p>Matched on the wrapped Minecraft object rather than on the dimension name, because a server
    * can present several levels with the same dimension type and DH keys its own identifiers off
    * more than the name.
    */
   public static IDhApiLevelWrapper levelFor(ClientLevel level) {
      if (level == null || !available()) {
         return null;
      }

      try {
         for (IDhApiLevelWrapper wrapper : DhApi.Delayed.worldProxy.getAllLoadedLevelWrappers()) {
            if (wrapper != null && wrapper.getWrappedMcObject() == level) {
               return wrapper;
            }
         }
      } catch (Throwable t) {
         Log.diag("DH level lookup failed: " + t);
      }

      return null;
   }

   /**
    * Hands DH a chunk to ingest as if the client had just loaded it.
    *
    * <p>The two arguments are not the same object twice. The first is DH's own level wrapper,
    * which is how the repo finds the level to write into. The second array is what DH turns into a
    * chunk wrapper, and its second element must be the <em>vanilla</em> {@code Level} — DH derives
    * its wrapper from that itself, through {@code ClientLevelWrapper.getWrapper}. Passing the api
    * wrapper there instead is a {@code ClassCastException: Chunk wrapper creation failed}, which is
    * what the first working build did.
    *
    * @return null on success, or why it was refused
    */
   public static String submitChunk(IDhApiLevelWrapper level, ChunkAccess chunk, Level mcLevel) {
      if (level == null || mcLevel == null) {
         return "no DH level";
      }

      if (!available()) {
         return "DH is not ready";
      }

      forgetChunkHash(level, chunk.getPos().x(), chunk.getPos().z());

      try {
         DhApiResult<Void> result = DhApi.Delayed.terrainRepo.overwriteChunkDataAsync(level, new Object[] {chunk, mcLevel});
         return result != null && result.success ? null : result == null ? "no result" : result.message;
      } catch (Throwable t) {
         return t.toString();
      }
   }

   /** Set once the hash table proved unreachable, so a changed DH costs one log line, not thousands. */
   private static volatile boolean chunkHashUnreachable;

   /**
    * Makes DH forget the content hash it holds for one chunk, so the next submission of it is
    * applied rather than skipped.
    *
    * <p>Before DH processes a chunk it compares the chunk's block/biome hash with its
    * {@code ChunkHash} table and silently drops the update if they match ({@code
    * disableUnchangedChunkCheck}, off by default). That is meant for the client re-loading ground
    * it already has. But the hash is recorded independently of the data: a chunk whose data never
    * reached the database -- lost from DH's delayed save at a disconnect, or from its update queue
    * -- keeps its hash. Every later resend of the same ground then matches and is discarded, so the
    * hole is permanent, however many times the server sends it. That is what 0.10.2 ran into: the
    * declaration fixed, a full resend, and the same black chunks along the old frontier.
    *
    * <p>Everything streamed is sent precisely because this client does not have it, so it should
    * always be applied. Forgetting the hash first does that, for exactly the chunks being written
    * and no others; chunks the client loads itself keep DH's check.
    *
    * <p>This is the one place this class reaches past DH's api package. Every member it touches is
    * public, and it fails open: if DH's internals have moved, it logs once and the submit goes
    * ahead as before.
    */
   private static void forgetChunkHash(IDhApiLevelWrapper level, int chunkX, int chunkZ) {
      if (chunkHashUnreachable) {
         return;
      }

      try {
         com.seibel.distanthorizons.core.world.AbstractDhWorld world =
            com.seibel.distanthorizons.core.api.internal.SharedApi.getAbstractDhWorld();

         if (world == null || !(level instanceof com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper wrapper)) {
            return;
         }

         if (world.getLevel(wrapper) instanceof com.seibel.distanthorizons.core.level.AbstractDhLevel dhLevel
            && dhLevel.chunkHashRepo != null) {
            dhLevel.chunkHashRepo.deleteWithKey(new com.seibel.distanthorizons.core.pos.DhChunkPos(chunkX, chunkZ));
         }
      } catch (Throwable t) {
         chunkHashUnreachable = true;
         Log.warn("could not clear Distant Horizons' chunk hash; resent chunks DH already hashed may be skipped", t);
      }
   }

   /**
    * Every stored terrain column in one chunk, indexed {@code [x][z][point]} top-down.
    *
    * <p>Each point is a run: one block state and biome over a {@code bottomYBlockPos ..
    * topYBlockPos} span, which is what the map bridge wants anyway — a column walk over runs rather
    * than over blocks.
    *
    * @return null when DH has nothing stored there, which is not an error
    */
   public static DhApiTerrainDataPoint[][][] chunkData(IDhApiLevelWrapper level, int chunkX, int chunkZ, IDhApiTerrainDataCache cache) {
      if (level == null || !available()) {
         return null;
      }

      try {
         DhApiResult<DhApiTerrainDataPoint[][][]> result =
            DhApi.Delayed.terrainRepo.getAllTerrainDataAtChunkPos(level, chunkX, chunkZ, cache);

         if (result == null) {
            lastReadMessage = "null result";
            return null;
         }

         // Kept rather than discarded: "no data" and "you asked wrongly" both arrive as an empty
         // answer here, and telling them apart from the outside is impossible. The map bridge
         // treats both as "leave it to Xaero", which is safe but silent -- so the reason goes to
         // /voxymap dh, where it can be read.
         lastReadMessage = result.success ? null : result.message;
         return result.success ? result.payload : null;
      } catch (Throwable t) {
         lastReadMessage = t.toString();
         Log.diag("DH chunk read failed at " + chunkX + "," + chunkZ + ": " + t);
         return null;
      }
   }

   /**
    * How many of a chunk read's columns DH actually holds terrain for.
    *
    * <p>The array {@link #chunkData} returns is never the answer to "is anything there". DH builds
    * the full {@code [16][16]} array before it looks, and a chunk it has nothing for comes back as
    * a <em>successful</em> read ("No data found.") with every column null. So {@code data != null &&
    * data.length > 0} is true for every read that did not throw, and testing that is testing
    * nothing. Count the columns.
    */
   public static int columnsWithData(DhApiTerrainDataPoint[][][] data) {
      if (data == null) {
         return 0;
      }

      int n = 0;

      for (DhApiTerrainDataPoint[][] row : data) {
         if (row == null) {
            continue;
         }

         for (DhApiTerrainDataPoint[] column : row) {
            if (column != null && column.length > 0) {
               n++;
            }
         }
      }

      return n;
   }

   /** Whether DH holds every column of the chunk: the bar for claiming the chunk is held. */
   public static boolean chunkComplete(DhApiTerrainDataPoint[][][] data) {
      if (data == null || data.length == 0) {
         return false;
      }

      // Every row present and square: a coarse answer is still a whole chunk, a ragged one is not.
      for (DhApiTerrainDataPoint[][] row : data) {
         if (row == null || row.length != data.length) {
            return false;
         }
      }

      return columnsWithData(data) == data.length * data.length;
   }

   /**
    * DH's own render distance in chunks, which is how far out it is worth reading.
    *
    * @return the configured radius, or the fallback if DH will not say
    */
   public static int renderDistanceChunks(int fallback) {
      if (!installed() || DhApi.Delayed.configs == null) {
         return fallback;
      }

      try {
         Integer value = DhApi.Delayed.configs.graphics().chunkRenderDistance().getValue();
         return value == null || value <= 0 ? fallback : value;
      } catch (Throwable t) {
         return fallback;
      }
   }

   /** Whether DH is drawing at all. Terrain still stores and still maps when it is not. */
   public static boolean renderingEnabled() {
      if (!installed() || DhApi.Delayed.configs == null) {
         return false;
      }

      try {
         return Boolean.TRUE.equals(DhApi.Delayed.configs.graphics().renderingEnabled().getValue());
      } catch (Throwable t) {
         return false;
      }
   }

   /**
    * Gives a cache's data sources back to DH.
    *
    * <p>The sources a cache holds come out of DH's array pools, and only {@code close()} returns
    * them. Dropping the reference instead leaves them for the soft-reference GC to find, which
    * returns the memory but never the pooled arrays.
    */
   public static void closeCache(IDhApiTerrainDataCache cache) {
      if (cache == null) {
         return;
      }

      try {
         cache.close();
      } catch (Throwable t) {
         Log.diag("DH cache close failed: " + t);
      }
   }

   /** A cache DH is allowed to drop under memory pressure; reused across a sweep. */
   public static IDhApiTerrainDataCache newCache() {
      if (!available()) {
         return null;
      }

      try {
         return DhApi.Delayed.terrainRepo.createSoftCache();
      } catch (Throwable t) {
         return null;
      }
   }
}
