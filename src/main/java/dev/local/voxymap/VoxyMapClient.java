package dev.local.voxymap;

import dev.local.voxymap.lod.Backends;
import dev.local.voxymap.lod.LodBackend;
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
   private static Backends backend = Backends.NONE;

   /**
    * Builds the backend, in the one place allowed to name both implementations.
    *
    * <p>Kept to a single method with a switch rather than spread around, because each branch loads
    * the classes of a mod that may not be installed. Anything that touches {@code VoxyBackend} on a
    * client without Voxy is a {@code NoClassDefFoundError} at the first tick.
    */
   private static LodBackend newBackend(Backends kind) {
      return switch (kind) {
         case VOXY -> new dev.local.voxymap.voxy.VoxyBackend();
         case DH -> new dev.local.voxymap.dh.DhBackend(dev.local.voxymap.client.LodClient::heldColumns);
         case NONE -> null;
      };
   }

   public static Backends backend() {
      return backend;
   }

   @Override
   public void onInitializeClient() {
      // The ARM buffer-upload workaround, absorbed from the standalone gcarmfix mod. Its mixins
      // have very likely already run by now -- blaze3d builds its GlDevice before mod initialisers
      // -- and they load the config themselves for that reason. This call is only so the file
      // exists and the settings are logged on a machine that never reaches an upload.
      dev.local.voxymap.armfix.ArmFix.loadConfig();

      config = VoxyMapConfig.shared();
      Log.setDebug(config.debug);
      Log.setDiagnostics(config.diagnostics);

      // Which LOD database this client has. Voxy does not run on Windows-on-ARM at all, so this is
      // not a preference -- on those machines Distant Horizons is the only one there, and every
      // Voxy class has to stay unloaded. Everything downstream asks Backends rather than testing
      // for a mod itself.
      backend = Backends.choose(config.lodBackend);

      if (backend == Backends.NONE) {
         Log.info("neither voxy nor Distant Horizons is installed; the client half is doing nothing this session");
         return;
      }

      // The LOD receiver works with either backend and does not need Xaero at all: on a DH client
      // it is what puts distant terrain in front of the player, map or no map. Registered before
      // the Xaero check for exactly that reason, and unconditionally -- a server without the mod
      // simply never answers the handshake.
      dev.local.voxymap.client.LodClient.register(backend);

      if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("xaeroworldmap")) {
         Log.info("Xaero's World Map is not installed; streaming LOD into " + backend.display()
            + " but not filling any map");
         ClientTickEvents.END_CLIENT_TICK.register(client -> dev.local.voxymap.client.LodClient.tick());
         return;
      }

      sweep = new SweepController(config, newBackend(backend));
      sweep.installPerfHooks();

      Cmd.register(sweep, config);

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
