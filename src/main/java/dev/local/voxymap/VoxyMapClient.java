package dev.local.voxymap;

import dev.local.voxymap.sweep.SweepController;
import dev.local.voxymap.util.Log;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * Bridges Voxy's stored voxel data onto Xaero's world map.
 *
 * <p>Voxy keeps a persistent LOD database of everything the client has ever streamed, including
 * terrain far past Xaero's own write distance. Xaero only draws what its writer has walked over.
 * This mod reads the former and writes the latter, filling in ground the player has seen from a
 * distance but never explored -- without ever overwriting a tile Xaero scanned for real.
 */
public final class VoxyMapClient implements ClientModInitializer {
   private static VoxyMapConfig config;
   private static SweepController sweep;

   @Override
   public void onInitializeClient() {
      // Both are suggestions rather than requirements as of 0.6, because the same jar now loads on
      // a dedicated server where neither exists (see VoxyMapServer). On a client without them there
      // is simply nothing to bridge, and saying so beats a NoClassDefFoundError from the first tick.
      if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("voxy")
         || !net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("xaeroworldmap")) {
         Log.info("voxy or Xaero's World Map is not installed; the map bridge is doing nothing this session");
         return;
      }

      config = VoxyMapConfig.shared();
      Log.setDebug(config.debug);
      Log.setDiagnostics(config.diagnostics);
      sweep = new SweepController(config);
      sweep.installPerfHooks();

      Cmd.register(sweep, config);

      // Receives LOD from a voxymap server. Registered unconditionally: on a server without the
      // mod the handshake simply goes unanswered and nothing streams.
      dev.local.voxymap.client.LodClient.register();

      ClientTickEvents.END_CLIENT_TICK.register(client -> {
         try {
            // Before the sweep, so an armed wipe lands on the first tick after the world closes
            // rather than a tick later.
            MapWipe.tick();
            // Drives the join-time declaration of what LOD this client already holds, and writes
            // that record back periodically. Cannot be done from the join event: Voxy has no engine
            // for the world yet, and the index walk it needs takes seconds.
            dev.local.voxymap.client.LodClient.tick();
            sweep.tick();
         } catch (Throwable t) {
            // A throw here would be swallowed into a client crash; stop cleanly instead.
            Log.error("sweep tick failed, stopping", t);

            try {
               sweep.stop();
            } catch (Throwable ignored) {
            }
         }
      });

      Log.info("ready -- /voxymap status to check what it can see" + (config.debug ? " (debug on)" : ""));
   }

   public static VoxyMapConfig config() {
      return config;
   }

   public static SweepController sweep() {
      return sweep;
   }

   /** Used by {@code /voxymap reload}. Copies in place so a running sweep picks the values up. */
   public static void replaceConfig(VoxyMapConfig fresh) {
      config.copyFrom(fresh);
      Log.setDebug(config.debug);
      Log.setDiagnostics(config.diagnostics);
   }
}
