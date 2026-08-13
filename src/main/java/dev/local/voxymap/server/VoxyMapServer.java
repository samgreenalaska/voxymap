package dev.local.voxymap.server;

import dev.local.voxymap.VoxyMapConfig;
import dev.local.voxymap.util.Log;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/**
 * The server half of voxymap: passive chunk generation, and nothing else.
 *
 * <p>Runs on a dedicated server and on the integrated server behind singleplayer. It shares only
 * the config file and the logger with the client half -- the map bridge itself is client-only and
 * none of its classes are touched from here, which is what lets one jar load on a server that has
 * neither Xaero nor Sodium installed.
 *
 * <p>Note the mod's dependencies on {@code xaeroworldmap} and {@code voxy} are declared as
 * suggestions rather than requirements for exactly that reason. The client entrypoint checks for
 * them itself; this one needs neither.
 */
public final class VoxyMapServer implements ModInitializer {
   private static ChunkPregen pregen;

   /**
    * Both null where Voxy is not installed.
    *
    * <p>Not fields with initialisers any more: this entrypoint runs on a <em>client</em> too, and
    * as of 0.8.0 a client can be one where Voxy is absent entirely (Windows-on-ARM, running the
    * Distant Horizons backend). A {@code static final ChunkIngest ingest = new ChunkIngest()} loads
    * Voxy's classes during class initialisation of this one, which on such a machine is a
    * {@code NoClassDefFoundError} before the mod has done anything at all.
    */
   private static ChunkIngest ingest;
   private static LodStreamer streamer;

   @Override
   public void onInitialize() {
      // Shared with the client half rather than loaded again: see VoxyMapConfig.shared(). On a
      // dedicated server this entrypoint is the only caller and the sharing is a no-op; on a client
      // hosting singleplayer it is what makes the video settings page reach the generator.
      VoxyMapConfig config = VoxyMapConfig.shared();
      Log.setDebug(config.debug);
      Log.setDiagnostics(config.diagnostics);

      // Both sides run this entrypoint, so registering here keeps the protocol symmetric.
      dev.local.voxymap.net.LodProtocol.registerTypes();

      pregen = new ChunkPregen(config);

      // Chunk generation needs nothing but Minecraft, so it runs either way. Everything below it
      // -- voxelizing what was generated and streaming it out -- is Voxy's, and Voxy may not be
      // here: this entrypoint also runs on a client, including one that has Distant Horizons
      // instead. Naming those classes at all on that machine fails to link, so the check has to
      // come before the constructor call rather than inside it.
      boolean voxy = dev.local.voxymap.lod.Backends.voxyInstalled();

      if (voxy) {
         streamer = new LodStreamer(config);
         streamer.register();
      }

      ServerCmd.register(config, pregen, streamer);

      if (voxy) {
         // The LOD store lives under the world folder, so it is only knowable once a server exists.
         ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            ingest = new ChunkIngest();

            if (ServerVoxyInstance.install(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("voxymap").resolve("lod"), config.lodIngestThreads)) {
               ingest.register();
            }
         });
      } else {
         Log.info("voxy is not installed here, so this side generates chunks but does not store or stream LOD");
      }

      ServerTickEvents.END_SERVER_TICK.register(server -> {
         try {
            pregen.tick(server);

            if (streamer != null) {
               streamer.tick(server);
            }
         } catch (Throwable t) {
            // Never take the server down for this. Generation is the least important thing here.
            Log.error("chunk pregen tick failed", t);
         }
      });

      ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
         Log.info(pregen.status());
         Log.info(ingestStatus());
         Log.info(streamStatus());
      });

      Log.info(
         "server ready -- chunk pregen "
            + (config.generateChunks ? "on, radius " + config.generationRadiusChunks + " chunks" : "off")
      );
   }

   /** Exposed so /voxymap pregen can show whether LOD is actually being stored and sent. */
   public static String ingestStatus() {
      return ingest == null ? "lod ingest: not running (voxy is not installed on this side)" : ingest.status();
   }

   public static String streamStatus() {
      return streamer == null ? "lod stream: not started" : streamer.status();
   }

   public static ChunkPregen pregen() {
      return pregen;
   }
}
