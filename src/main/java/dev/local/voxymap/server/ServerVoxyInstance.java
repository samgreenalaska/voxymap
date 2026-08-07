package dev.local.voxymap.server;

import dev.local.voxymap.util.Log;
import java.nio.file.Path;
import me.cortex.voxy.common.StorageConfigUtil;
import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.VoxyInstance;
import me.cortex.voxy.commonImpl.WorldIdentifier;

/**
 * Voxy, running on the server.
 *
 * <p>Voxy ships as {@code environment: "*"} and its whole common layer -- the engine, the storage
 * backends, the voxelizer -- works headless. What it does not do on a dedicated server is decide to
 * exist: {@code VoxyCommon} holds a single {@link VoxyInstance} supplied through
 * {@code setInstanceFactory}, and on a client that factory is installed by Voxy's own client
 * entrypoint. Nothing installs one server-side, which is why a server with Voxy on it still has no
 * LOD database until a mod asks for one.
 *
 * <p>So this is the whole server-side foundation: one subclass, one abstract method. Everything
 * below it -- threading, the ingest service, the saving service, the section format -- is Voxy's
 * own, used through its public API rather than reimplemented.
 *
 * <h2>Where the data lives</h2>
 *
 * <p>{@code <world>/voxymap/lod/<world identifier>/storage/}, built from Voxy's own
 * {@code DEFAULT_STORAGE_PATH} template so the layout matches what Voxy would have produced itself.
 * It is deliberately under the world folder rather than the server root: it is derived from that
 * world and should be copied, backed up and deleted along with it.
 *
 * <h2>Only ever installed when nothing else claimed the slot</h2>
 *
 * <p>{@code setInstanceFactory} is a single setter, so claiming it blindly would break whoever
 * claimed it first -- the same trap as Voxy's dirty callback in §7.12. On an integrated server the
 * client's own instance is already there and must be left alone: it is the one the map bridge reads
 * from, and replacing it would point the client at a second database of the same world.
 */
public final class ServerVoxyInstance extends VoxyInstance {
   private final Path baseSavePath;

   private ServerVoxyInstance(Path baseSavePath, int threads) {
      this.baseSavePath = baseSavePath;

      // Without this the instance has a thread pool of zero and every ingest is queued and never
      // run: chunks went in, "queued" climbed, the store never grew and the streamer found nothing
      // to send. Voxy's own client calls updateDedicatedThreads() after construction, which is
      // where the threads actually come from; nothing does that for an instance built elsewhere.
      this.setNumThreads(Math.max(1, threads));
   }

   @Override
   protected SectionStorage createStorage(WorldIdentifier id) {
      // The path comes from the context's path *stack*, not from these properties --
      // ConfigBuildCtx.resolvePath() walks pushPath() entries and the properties are only template
      // substitutions. Setting the properties alone left RocksDB opening a path that did not exist,
      // which is what threw "While mkdir if missing" on every chunk load until the watchdog killed
      // the server.
      Path storage = this.baseSavePath.resolve(id.getWorldId()).resolve("storage");

      ConfigBuildCtx ctx = new ConfigBuildCtx()
         .setProperty(ConfigBuildCtx.BASE_SAVE_PATH, this.baseSavePath.toAbsolutePath().toString())
         .setProperty(ConfigBuildCtx.WORLD_IDENTIFIER, id.getWorldId())
         .pushPath(storage.toAbsolutePath().toString());

      // RocksDB creates its own leaf directory but not the chain above it.
      try {
         java.nio.file.Files.createDirectories(storage);
      } catch (Exception e) {
         Log.warn("could not create the LOD storage directory at " + storage, e);
      }

      Log.info("opening LOD storage for " + id.getWorldId() + " at " + storage);

      return StorageConfigUtil.createDefaultSerializer().build(ctx);
   }

   /**
    * Installs this as Voxy's instance, unless something already did.
    *
    * @param baseSavePath the world folder to keep the LOD database under
    * @return true if the server now has a Voxy instance to ingest into
    */
   public static boolean install(Path baseSavePath, int threads) {
      if (VoxyCommon.getInstance() != null) {
         // Someone got here first. On an integrated server that is Voxy's own client instance, and
         // it is the one the map bridge reads -- taking the slot would point the client at a second
         // database of the same world.
         Log.info("Voxy already has an instance; using it rather than installing a second");
         return true;
      }

      try {
         VoxyCommon.setInstanceFactory(() -> new ServerVoxyInstance(baseSavePath, threads));

         // setInstanceFactory only stores the factory -- getInstance() is a plain field read and
         // never creates anything. createInstance() is what actually consults it, and without this
         // call every ingest silently finds a null instance and drops the chunk.
         VoxyCommon.createInstance();

         Log.info("server-side Voxy instance created; LOD store under " + baseSavePath);
         return true;
      } catch (Throwable t) {
         // In singleplayer Voxy's client installs its own factory before the integrated server
         // starts, so this throws -- and treating that as fatal was a real bug: it skipped
         // registering chunk ingest, so pregenerated chunks were never voxelized and never reached
         // the map. Whoever owns the instance, ingest still has somewhere to put things.
         Log.info("Voxy's instance is provided elsewhere (" + t + "); ingesting into that instead");
         return true;
      }
   }
}
