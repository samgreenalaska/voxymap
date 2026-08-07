package dev.local.voxymap;

import dev.local.voxymap.util.Log;
import dev.local.voxymap.sweep.SweepController;
import dev.local.voxymap.xaero.XaeroBridge;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.world.level.storage.LevelResource;
import xaero.map.MapProcessor;
import xaero.map.world.MapDimension;

/**
 * Throws away every trace of the current world so it can be explored again from nothing.
 *
 * <p>Purely a debugging tool: the only reason to want this is to watch a world fill in from scratch.
 * It is registered under {@code debug} with everything else that exists to develop this mod.
 *
 * <h2>Why it happens at disconnect</h2>
 *
 * <p>Both mods hold their data open while you are in a world. Xaero keeps every loaded region in
 * memory and writes it back out on its own schedule, so deleting the files underneath it would just
 * be undone -- and Voxy's storage backend has the database open. Neither can be wiped from under a
 * running session.
 *
 * <p>So the command arms the wipe and it lands as you leave. Xaero has its own mechanism for
 * exactly this ({@code requestCurrentMapDeletion}, which its own "delete map" button uses), and it
 * is better than anything done from outside: it makes {@code changeWorld} skip the final save of
 * in-memory regions, so nothing is written back after the folder moves. Voxy and this mod's own
 * files are removed on the first tick after the level is gone, by which point Voxy's shutdown has
 * closed the database.
 *
 * <h2>What survives</h2>
 *
 * <p>Xaero's data is <em>moved</em>, to {@code <dimension>/last deleted/<multiworld>} -- that is
 * Xaero's own recycle bin and its own behaviour, not a choice made here. Voxy's is deleted outright
 * because it runs to gigabytes and keeping a copy of that around after every wipe is not a kindness.
 * The confirmation prompt says so, with the measured size, before anything happens.
 */
public final class MapWipe {
   private static Path pendingVoxy;
   private static Path pendingOwn;
   private static String pendingLabel;

   private MapWipe() {
   }

   /** @return a human-readable account of what a wipe would remove, or an error line. */
   public static List<String> describe() {
      List<String> out = new ArrayList<>();
      Minecraft mc = Minecraft.getInstance();
      MapProcessor mp = XaeroBridge.processor();

      if (mc.level == null) {
         out.add("Not in a world. Join the world you want to wipe first.");
         return out;
      }

      if (mp == null) {
         out.add("Xaero has no active session, so its data cannot be wiped safely. Try again once the map is running.");
         return out;
      }

      Path xaero = xaeroFolder(mp);
      Path voxy = voxySaveFolder();
      Path own = ownFolder(mp);

      out.add("=== /voxymap wipe would remove, as you disconnect ===");
      out.add("");
      out.add("Xaero's map for this world  " + describePath(xaero));
      out.add("  moved to Xaero's own '" + (xaero == null ? "last deleted" : xaero.getParent().resolve("last deleted")) + "' folder, not deleted");
      out.add("Voxy's voxel database       " + describePath(voxy));
      out.add("  DELETED PERMANENTLY. There is no copy and it can only be rebuilt by exploring again.");
      out.add("  This includes voxymap-have.bin, our record of which streamed LOD columns you hold.");
      out.add("  It lives inside that folder on purpose: it is a claim about the database beside it,");
      out.add("  and a claim that outlives its data is what stopped the map refilling in 7.16.");
      out.add("This mod's sweep baseline   " + describePath(own));
      out.add("  deleted; it is derived data and regenerates on the next sweep.");

      List<Path> sidecars = voxyServerSidecars(voxy);

      if (!sidecars.isEmpty()) {
         out.add("VoxyServer's hash sidecar   " + sidecars.size() + " file(s) under " + voxyServerHashDir());
         out.add("  deleted, and it must be: it is the client's claim about which sections it already");
         out.add("  holds. Left behind, the manifest would tell the server you still have everything");
         out.add("  that was just deleted and the server would re-send none of it.");
      }

      out.add("");
      out.add("NOT wiped, and out of reach from here: Voxy World Gen keeps its record of what it has");
      out.add("  already generated on the SERVER, in <world>/voxy_gen_<dimension>.bin. VWG will not");
      out.add("  re-generate that terrain, so if VoxyServer is not installed on both sides the map");
      out.add("  will only refill where you have never been. With VoxyServer running, this does not");
      out.add("  matter: it re-streams from the server's own store once the sidecar above is gone.");
      out.add("");
      out.add("Nothing happens until you disconnect, and nothing at all happens without:");
      out.add("  /voxymap wipe confirm");
      return out;
   }

   /**
    * Arms the wipe. Xaero's half is handed to Xaero; the rest is remembered until the level is gone.
    *
    * @return what to tell the player
    */
   public static String arm() {
      Minecraft mc = Minecraft.getInstance();
      MapProcessor mp = XaeroBridge.processor();

      if (mc.level == null) {
         return "Not in a world. Join the world you want to wipe first.";
      }

      if (mp == null) {
         return "Xaero has no active session, so its data cannot be wiped safely. Try again once the map is running.";
      }

      if (pendingLabel != null) {
         return "A wipe is already armed for " + pendingLabel + ". Disconnect to let it happen.";
      }

      Path voxy = voxySaveFolder();
      Path own = ownFolder(mp);

      // Stop writing before asking for the data to go away, so nothing is mid-region when Xaero
      // drops its in-memory state.
      SweepController sweep = VoxyMapClient.sweep();
      if (sweep != null) {
         sweep.stop();
      }

      try {
         synchronized (mp.uiSync) {
            // Same call Xaero's own "delete map" button makes for the multiworld you are in. It
            // throws if a deletion is already pending, hence the guard above.
            mp.requestCurrentMapDeletion();
         }
      } catch (Throwable t) {
         Log.warn("could not ask Xaero to delete the current map", t);
         return "Xaero refused the deletion request (" + t + "). Nothing has been armed.";
      }

      pendingVoxy = voxy;
      pendingOwn = own;
      pendingLabel = mp.getCurrentWorldId() + " / " + mp.getCurrentDimId() + " / " + mp.getCurrentMWId();

      Log.info("wipe armed for [" + pendingLabel + "]; voxy=" + voxy + " own=" + own);
      return "Wipe armed. Disconnect now -- Xaero's map moves to its 'last deleted' folder and "
         + "Voxy's database is deleted as you leave. Automatic sweeping is suspended until then.";
   }

   /** Called every client tick. Does the deleting once the world is gone and the files are free. */
   public static void tick() {
      if (pendingLabel == null || Minecraft.getInstance().level != null) {
         return;
      }

      String label = pendingLabel;
      Path voxy = pendingVoxy;
      Path own = pendingOwn;
      pendingLabel = null;
      pendingVoxy = null;
      pendingOwn = null;

      Log.info("wiping [" + label + "]");

      // Before the Voxy folder goes -- the world hashes that name the sidecars are its subdirectories.
      List<Path> sidecars = voxyServerSidecars(voxy);

      deleteTree(voxy, "Voxy's voxel database");
      deleteTree(own, "the sweep baseline");

      for (Path p : sidecars) {
         deleteFile(p, "VoxyServer's hash sidecar");
      }

      // arm() suspended automatic sweeping so nothing would write between confirming and leaving.
      // Now the world is gone and the point of the wipe is to watch it fill in again, so put it
      // back. Nothing starts until a world is joined.
      SweepController sweep = VoxyMapClient.sweep();
      if (sweep != null) {
         sweep.setAuto(true);
      }

      Log.info("wipe complete. Xaero's own data was moved to its 'last deleted' folder as the world closed.");
   }

   public static boolean armed() {
      return pendingLabel != null;
   }

   // ------------------------------------------------------------------ paths

   private static Path xaeroFolder(MapProcessor mp) {
      try {
         MapDimension dim = mp.getMapWorld().getCurrentDimension();
         return dim.getMainFolderPath().resolve(dim.getCurrentMultiworld());
      } catch (Throwable t) {
         return null;
      }
   }

   /**
    * Mirrors {@code VoxyClientInstance.getBasePath}, which is private.
    *
    * <p>Kept deliberately literal against that method: {@code .voxy/saves/<server ip with ':' as
    * '_'>} in multiplayer, {@code realms} for a realm, and the world folder's own {@code voxy}
    * directory in singleplayer.
    */
   public static Path voxySaveFolder() {
      Minecraft mc = Minecraft.getInstance();

      try {
         if (mc.getSingleplayerServer() != null) {
            return mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT).resolve("voxy").toAbsolutePath();
         }

         Path base = mc.gameDirectory.toPath().resolve(".voxy").resolve("saves");
         ServerData info = mc.getCurrentServer();

         if (info == null) {
            return base.resolve("UNKNOWN").toAbsolutePath();
         }

         return base.resolve(info.isRealm() ? "realms" : info.ip.replace(":", "_")).toAbsolutePath();
      } catch (Throwable t) {
         Log.warn("could not work out where Voxy stores this world", t);
         return null;
      }
   }

   /** {@code <gameDir>/voxyserver/hashes}, mirroring {@code ClientLodHashStore.fileFor}. */
   private static Path voxyServerHashDir() {
      return FabricLoader.getInstance().getGameDir().resolve("voxyserver").resolve("hashes").toAbsolutePath();
   }

   /**
    * VoxyServer's record of which LOD sections this client believes it already has, for every world
    * about to be deleted.
    *
    * <p>This has to go with Voxy's database or the wipe half-lands. VoxyServer's client sends the
    * server a manifest of section keys and content hashes read from these files, and
    * {@code PlayerLodTracker.applyManifestBatch} marks every one of them as already sent -- so a
    * stale sidecar tells the server to skip exactly the sections the wipe just destroyed, and the
    * map refills only where the player has never been. That symptom was blamed on Voxy World Gen's
    * server-side generation cache for a whole release; it is at least partly this.
    *
    * <p>Named by Voxy's world hash, which is also the name of each subdirectory of the Voxy folder,
    * so the set is derived from the folder being deleted rather than recomputed. That keeps other
    * servers' sidecars out of it. Matched by prefix rather than an exact {@code .bin} so a change of
    * suffix in VoxyServer does not silently make this a no-op.
    *
    * @param voxyFolder the Voxy save folder about to be deleted, or null if it could not be located
    */
   private static List<Path> voxyServerSidecars(Path voxyFolder) {
      List<Path> out = new ArrayList<>();

      if (voxyFolder == null || !Files.isDirectory(voxyFolder)) {
         return out;
      }

      Path hashes = voxyServerHashDir();

      if (!Files.isDirectory(hashes)) {
         return out;
      }

      try (var worlds = Files.list(voxyFolder)) {
         List<String> worldHashes = worlds.filter(Files::isDirectory).map(p -> p.getFileName().toString()).toList();

         for (String hash : worldHashes) {
            try (var files = Files.list(hashes)) {
               files.filter(Files::isRegularFile)
                  .filter(p -> p.getFileName().toString().startsWith(hash))
                  .forEach(out::add);
            }
         }
      } catch (Throwable t) {
         Log.warn("could not list VoxyServer's hash sidecars under " + hashes, t);
      }

      return out;
   }

   private static Path ownFolder(MapProcessor mp) {
      try {
         return FabricLoader.getInstance()
            .getGameDir()
            .resolve("voxymap")
            .resolve(safe(mp.getCurrentWorldId()))
            .resolve(safe(mp.getCurrentDimId()))
            .resolve(safe(mp.getCurrentMWId()))
            .toAbsolutePath();
      } catch (Throwable t) {
         return null;
      }
   }

   private static String safe(String s) {
      return s == null || s.isEmpty() ? "default" : s.replaceAll("[^A-Za-z0-9._$-]", "_");
   }

   // --------------------------------------------------------------- deleting

   /**
    * Removes a directory tree, refusing anything that is not a directory we resolved ourselves.
    *
    * <p>The path is logged before a single file goes, because the one way this could be a disaster
    * is a path resolved wrongly, and the log is the only record of what it actually pointed at.
    */
   private static void deleteTree(Path root, String what) {
      if (root == null) {
         Log.warn("skipping " + what + ": could not work out where it lives");
         return;
      }

      if (!Files.isDirectory(root)) {
         Log.info("skipping " + what + ": nothing at " + root);
         return;
      }

      Log.info("deleting " + what + " at " + root);

      try (var walk = Files.walk(root)) {
         List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();

         for (Path p : paths) {
            try {
               Files.deleteIfExists(p);
            } catch (IOException e) {
               // Something still has it open. Leaving the rest is better than half a retry loop.
               Log.warn("could not delete " + p + " (" + e + "); the rest of " + what + " was left alone");
               return;
            }
         }
      } catch (Throwable t) {
         Log.warn("failed while deleting " + what + " at " + root, t);
         return;
      }

      Log.info("deleted " + what);
   }

   /** Same contract as {@link #deleteTree}, for a single file. */
   private static void deleteFile(Path p, String what) {
      if (p == null || !Files.isRegularFile(p)) {
         return;
      }

      Log.info("deleting " + what + " at " + p);

      try {
         Files.deleteIfExists(p);
      } catch (Throwable t) {
         Log.warn("could not delete " + what + " at " + p + " (" + t + ")");
      }
   }

   // -------------------------------------------------------------- reporting

   private static String describePath(Path p) {
      if (p == null) {
         return "-- could not be located, so it will be left alone";
      }

      if (!Files.isDirectory(p)) {
         return "-- nothing there yet (" + p + ")";
      }

      return humanSize(sizeOf(p)) + "  " + p;
   }

   private static long sizeOf(Path root) {
      try (var walk = Files.walk(root)) {
         return walk.filter(Files::isRegularFile).mapToLong(p -> {
            try {
               return Files.size(p);
            } catch (IOException e) {
               return 0L;
            }
         }).sum();
      } catch (Throwable t) {
         return -1L;
      }
   }

   private static String humanSize(long bytes) {
      if (bytes < 0L) {
         return "size unknown";
      }

      if (bytes < 1024L * 1024L) {
         return String.format("%.0f KB", bytes / 1024.0);
      }

      if (bytes < 1024L * 1024L * 1024L) {
         return String.format("%.0f MB", bytes / (1024.0 * 1024.0));
      }

      return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
   }
}
