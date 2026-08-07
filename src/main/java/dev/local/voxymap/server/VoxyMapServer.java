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
   private static final ChunkIngest ingest = new ChunkIngest();
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
      streamer = new LodStreamer(config);
      streamer.register();
      ServerCmd.register(config, pregen, streamer);

      // The LOD store lives under the world folder, so it is only knowable once a server exists.
      ServerLifecycleEvents.SERVER_STARTING.register(server -> {
         if (ServerVoxyInstance.install(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("voxymap").resolve("lod"), config.lodIngestThreads)) {
            ingest.register();
         }
      });

      ServerTickEvents.END_SERVER_TICK.register(server -> {
         try {
            pregen.tick(server);
            streamer.tick(server);
         } catch (Throwable t) {
            // Never take the server down for this. Generation is the least important thing here.
            Log.error("chunk pregen tick failed", t);
         }
      });

      ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
         Log.info(pregen.status());
         Log.info(ingest.status());
         Log.info(streamer.status());
      });

      Log.info(
         "server ready -- chunk pregen "
            + (config.generateChunks ? "on, radius " + config.generationRadiusChunks + " chunks" : "off")
      );
   }

   /** Exposed so /voxymap pregen can show whether LOD is actually being stored and sent. */
   public static String ingestStatus() {
      return ingest.status();
   }

   public static String streamStatus() {
      return streamer == null ? "lod stream: not started" : streamer.status();
   }

   public static ChunkPregen pregen() {
      return pregen;
   }
}
