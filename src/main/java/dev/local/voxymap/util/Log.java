package dev.local.voxymap.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Log {
   private static final Logger LOGGER = LoggerFactory.getLogger("voxymap");

   private Log() {
   }

   public static void info(String msg) {
      LOGGER.info("[voxymap] {}", msg);
   }

   public static void warn(String msg) {
      LOGGER.warn("[voxymap] {}", msg);
   }

   public static void warn(String msg, Throwable t) {
      LOGGER.warn("[voxymap] " + msg, t);
   }

   public static void error(String msg, Throwable t) {
      LOGGER.error("[voxymap] " + msg, t);
   }

   public static void debug(String msg) {
      LOGGER.debug("[voxymap] {}", msg);
   }

   private static volatile boolean diagnostics = true;
   private static volatile boolean debug = true;

   public static void setDiagnostics(boolean enabled) {
      diagnostics = enabled;
   }

   /** Master switch. With it off, {@link #diag} and {@link #dev} say nothing at all. */
   public static void setDebug(boolean enabled) {
      debug = enabled;
   }

   public static boolean debugEnabled() {
      return debug;
   }

   /**
    * Diagnostic detail, logged at INFO rather than DEBUG on purpose -- Minecraft ships with DEBUG
    * suppressed, so anything logged there would be invisible in the log the user actually sends.
    */
   public static void diag(String msg) {
      if (debug && diagnostics) {
         LOGGER.info("[voxymap] {}", msg);
      }
   }

   /**
    * Routine progress that only matters while developing this mod -- per-region lines, per-sweep
    * summaries, rescan counts. Silent once {@code debug} is off.
    */
   public static void dev(String msg) {
      if (debug) {
         LOGGER.info("[voxymap] {}", msg);
      }
   }
}
