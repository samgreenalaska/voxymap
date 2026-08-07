package dev.local.voxymap.sweep;

import dev.local.voxymap.util.Log;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import xaero.map.MapProcessor;

/**
 * Sidecar record of which chunks this mod authored, one 1024-bit bitmap per Xaero region.
 *
 * <p>The primary marker is {@code MapTile.worldInterpretationVersion}, which round-trips through
 * Xaero's own region files and is therefore self-maintaining: when Xaero rewrites one of our
 * chunks for real it resets the value to 1 and the chunk silently stops being ours.
 *
 * <p>This file exists as the belt to that mod's braces. If a future Xaero release ever starts
 * validating the interpretation version, the marker becomes unusable and this index is the only
 * remaining record of what we wrote -- which is what {@code /voxymap purge} needs.
 *
 * <p>Layout: {@code <gameDir>/voxymap/<worldId>/<dimId>/<mwId>/authored-<rx>_<rz>.bits}
 */
public final class AuthoredIndex {
   private static final int BITS = 1024;
   private static final int BYTES = BITS / 8;

   private final Path dir;
   private final byte[] bits = new byte[BYTES];
   private int regionX;
   private int regionZ;
   private boolean open;
   private boolean dirty;

   public AuthoredIndex(MapProcessor mp) {
      this.dir = baseDir(mp);
   }

   private static Path baseDir(MapProcessor mp) {
      Path root = FabricLoader.getInstance().getGameDir().resolve("voxymap");
      String world = safe(mp.getCurrentWorldId());
      String dim = safe(mp.getCurrentDimId());
      String mw = safe(mp.getCurrentMWId());
      return root.resolve(world).resolve(dim).resolve(mw);
   }

   private static String safe(String s) {
      if (s == null || s.isEmpty()) {
         return "default";
      }

      return s.replaceAll("[^A-Za-z0-9._$-]", "_");
   }

   private Path fileFor(int rx, int rz) {
      return this.dir.resolve("authored-" + rx + "_" + rz + ".bits");
   }

   public void open(int rx, int rz) {
      this.close();
      this.regionX = rx;
      this.regionZ = rz;
      this.open = true;
      this.dirty = false;
      java.util.Arrays.fill(this.bits, (byte)0);

      Path f = this.fileFor(rx, rz);

      try {
         if (Files.exists(f)) {
            byte[] read = Files.readAllBytes(f);
            System.arraycopy(read, 0, this.bits, 0, Math.min(read.length, BYTES));
         }
      } catch (IOException e) {
         Log.warn("could not read the authored index for r(" + rx + "," + rz + ")", e);
      }
   }

   /** Absolute chunk coordinates. */
   public void mark(int chunkX, int chunkZ) {
      if (!this.open) {
         return;
      }

      int i = (chunkX & 31) + (chunkZ & 31) * 32;
      this.bits[i >> 3] |= (byte)(1 << (i & 7));
      this.dirty = true;
   }

   public boolean isMarked(int chunkX, int chunkZ) {
      int i = (chunkX & 31) + (chunkZ & 31) * 32;
      return (this.bits[i >> 3] & 1 << (i & 7)) != 0;
   }

   public int count() {
      int n = 0;

      for (byte b : this.bits) {
         n += Integer.bitCount(b & 0xFF);
      }

      return n;
   }

   public void close() {
      if (this.open && this.dirty) {
         Path f = this.fileFor(this.regionX, this.regionZ);

         try {
            Files.createDirectories(this.dir);
            Files.write(f, this.bits);
         } catch (IOException e) {
            Log.warn("could not write the authored index for r(" + this.regionX + "," + this.regionZ + ")", e);
         }
      }

      this.open = false;
      this.dirty = false;
   }

   /** @return every region that has a sidecar file, as packed region keys. */
   public List<long[]> listRegions() {
      List<long[]> out = new ArrayList<>();
      if (!Files.isDirectory(this.dir)) {
         return out;
      }

      try (Stream<Path> s = Files.list(this.dir)) {
         s.forEach(p -> {
            String name = p.getFileName().toString();
            if (!name.startsWith("authored-") || !name.endsWith(".bits")) {
               return;
            }

            String body = name.substring("authored-".length(), name.length() - ".bits".length());
            int sep = body.indexOf('_', body.startsWith("-") ? 1 : 0);
            if (sep <= 0) {
               return;
            }

            try {
               out.add(new long[]{Integer.parseInt(body.substring(0, sep)), Integer.parseInt(body.substring(sep + 1))});
            } catch (NumberFormatException ignored) {
            }
         });
      } catch (IOException e) {
         Log.warn("could not list the authored index", e);
      }

      return out;
   }

   public Path directory() {
      return this.dir;
   }
}
