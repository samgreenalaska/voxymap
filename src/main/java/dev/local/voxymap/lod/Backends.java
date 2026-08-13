package dev.local.voxymap.lod;

import dev.local.voxymap.dh.DhBridge;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Which LOD database this client is going to use.
 *
 * <p>Two exist and they are not interchangeable by preference — they are interchangeable by
 * availability. Voxy is the original and still the better source where it runs. It does not run on
 * Windows-on-ARM at all, which is the whole reason for 0.8: on those clients the jar has to work
 * with Voxy's classes entirely absent, and Distant Horizons is what is there instead.
 *
 * <p>So the choice is made once per session from what is installed, and everything downstream asks
 * this rather than testing for a mod itself. {@code lodBackend} in the config can pin it, which is
 * for testing the DH path on a machine that also has Voxy.
 */
public enum Backends {
   /** Voxy's {@code WorldEngine}: the 0.7 behaviour, unchanged. */
   VOXY,
   /** Distant Horizons, through its public api package only. */
   DH,
   /** Neither is installed; the client half does nothing this session. */
   NONE;

   public static boolean voxyInstalled() {
      return FabricLoader.getInstance().isModLoaded("voxy");
   }

   public static boolean dhInstalled() {
      return DhBridge.installed();
   }

   /**
    * @param configured {@code auto}, {@code voxy} or {@code dh} from the config file
    */
   public static Backends choose(String configured) {
      String want = configured == null ? "auto" : configured.trim().toLowerCase(java.util.Locale.ROOT);

      if (want.equals("voxy")) {
         return voxyInstalled() ? VOXY : NONE;
      }

      if (want.equals("dh") || want.equals("distanthorizons")) {
         return dhInstalled() ? DH : NONE;
      }

      // Voxy first: where both are installed it is the path with the section index, the direct
      // storage reads and two releases of testing behind it.
      if (voxyInstalled()) {
         return VOXY;
      }

      return dhInstalled() ? DH : NONE;
   }

   public String display() {
      return switch (this) {
         case VOXY -> "voxy";
         case DH -> "distant horizons";
         case NONE -> "none";
      };
   }
}
