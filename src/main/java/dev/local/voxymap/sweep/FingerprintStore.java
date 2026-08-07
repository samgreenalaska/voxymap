package dev.local.voxymap.sweep;

import dev.local.voxymap.util.Log;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import xaero.map.MapProcessor;

/**
 * Per-region content hashes of Voxy's data, persisted across sessions.
 *
 * <p>Without this, the automatic sweep would re-walk every region on every login -- about a second
 * each, so roughly five minutes of background work for a world with a few hundred regions, almost
 * all of it discovering that the tiles are already there. With it, a login only visits the regions
 * where Voxy has actually ingested something new.
 *
 * <p>Keyed by Xaero's world/dimension/multiworld identity, so a different server or dimension
 * never reuses another one's baseline.
 */
public final class FingerprintStore {
   private static final int MAGIC = 0x564D4650;

   /**
    * Bumped to discard the baselines written before the coverage fix. Those runs skipped chunks
    * Xaero was never going to write and then banked the region as done, so the holes they left are
    * frozen into every existing baseline. Discarding them costs one full sweep per world and heals
    * the map without the player having to know to ask for it.
    */
   private static final int VERSION = 2;

   private FingerprintStore() {
   }

   private static Path fileFor(MapProcessor mp) {
      Path root = FabricLoader.getInstance().getGameDir().resolve("voxymap");
      return root.resolve(safe(mp.getCurrentWorldId()))
         .resolve(safe(mp.getCurrentDimId()))
         .resolve(safe(mp.getCurrentMWId()))
         .resolve("fingerprints.bin");
   }

   private static String safe(String s) {
      if (s == null || s.isEmpty()) {
         return "default";
      }

      return s.replaceAll("[^A-Za-z0-9._$-]", "_");
   }

   /** @return the stored baseline, or null if there is none for this world. */
   public static Long2LongOpenHashMap load(MapProcessor mp) {
      Path f = fileFor(mp);
      if (!Files.exists(f)) {
         return null;
      }

      try (DataInputStream in = new DataInputStream(Files.newInputStream(f))) {
         if (in.readInt() != MAGIC || in.readInt() != VERSION) {
            return null;
         }

         int count = in.readInt();
         if (count < 0 || count > 4_000_000) {
            return null;
         }

         Long2LongOpenHashMap out = new Long2LongOpenHashMap(Math.max(16, count));
         out.defaultReturnValue(0L);

         for (int i = 0; i < count; i++) {
            out.put(in.readLong(), in.readLong());
         }

         return out;
      } catch (Throwable t) {
         Log.warn("could not read the Voxy fingerprint baseline; the next sweep will be a full one", t);
         return null;
      }
   }

   public static void save(MapProcessor mp, Long2LongOpenHashMap fingerprints) {
      if (fingerprints == null) {
         return;
      }

      Path f = fileFor(mp);

      try {
         Files.createDirectories(f.getParent());

         try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(f))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(fingerprints.size());

            for (Long2LongOpenHashMap.Entry e : fingerprints.long2LongEntrySet()) {
               out.writeLong(e.getLongKey());
               out.writeLong(e.getLongValue());
            }
         }
      } catch (IOException e) {
         Log.warn("could not write the Voxy fingerprint baseline", e);
      }
   }
}
