package dev.local.voxymap.sweep;

import dev.local.voxymap.VoxyMapConfig;
import dev.local.voxymap.util.Log;
import dev.local.voxymap.voxy.SectionIndex;
import dev.local.voxymap.xaero.Diagnostics;
import dev.local.voxymap.xaero.ScannedProbe;
import dev.local.voxymap.xaero.XaeroBridge;
import dev.local.voxymap.xaero.XaeroCoverage;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import xaero.map.MapProcessor;
import xaero.map.region.MapRegion;
import xaero.map.region.MapTileChunk;

/**
 * Everything worth checking about one region, in one place: {@code /voxymap debug}.
 *
 * <p>The question this exists to answer is always the same -- <em>this bit of the map looks wrong,
 * whose fault is it?</em> -- and answering it needs both sides at once. Xaero's side says which
 * chunks have a tile and who wrote it; Voxy's side says whether there was ever anything to write.
 * A chunk with no tile and no Voxy data is not this mod's problem; a chunk with no tile and a full
 * Voxy column is.
 *
 * <p>Read-only. It acquires and releases Voxy sections inside single calls like everything else,
 * and it never touches Xaero's write path.
 */
public final class DebugReport {
   private DebugReport() {
   }

   public static List<String> build(SweepController sweep, VoxyMapConfig config, int regionX, int regionZ) {
      List<String> out = new ArrayList<>();
      out.add("=== voxymap debug r(" + regionX + "," + regionZ + ")  blocks x " + (regionX << 9) + ".." + ((regionX << 9) + 511)
         + "  z " + (regionZ << 9) + ".." + ((regionZ << 9) + 511));

      MapProcessor mp = XaeroBridge.processor();
      if (mp == null) {
         out.add("xaero: no session -- nothing can be written or read right now.");
         return out;
      }

      out.add("");
      out.add("-- sweep --");
      out.addAll(sweep.status());

      out.add("");
      out.add("-- xaero, this region --");

      MapRegion region = null;

      try {
         // create = false: never fabricate a region just to look at it.
         region = mp.getLeafMapRegion(XaeroBridge.SURFACE_LAYER, regionX, regionZ, false);
      } catch (Throwable t) {
         out.add("getLeafMapRegion threw: " + t);
      }

      if (region == null) {
         out.add("NOT IN MEMORY. Xaero has no region object here, which is normal far from the");
         out.add("player and says nothing about whether the data is on disk. Walk closer, or open");
         out.add("the world map over it, and run this again.");
      } else {
         out.add(Diagnostics.regionState(region));
         out.add(Diagnostics.identity(mp, region));
         out.add(Diagnostics.saveFile(region));
         out.add(Diagnostics.tileChunkSummary(region));
      }

      out.add("");
      out.add("-- coverage: who owns the chunks here --");
      appendCoverage(out, mp, region, regionX, regionZ);

      out.add("");
      out.add("-- voxy, what it actually stored here --");
      // Voxy only, and the check has to be here rather than inside: appendVoxy names Voxy types in
      // its own signature, and on a client without Voxy installed calling it at all fails to link.
      // The DH backend's equivalent read is /voxymap dh, which asks its database the same question.
      if (dev.local.voxymap.VoxyMapClient.backend() == dev.local.voxymap.lod.Backends.VOXY) {
         dev.local.voxymap.voxy.VoxyDebugReport.append(out, config, regionX, regionZ);
      } else {
         out.add("lod backend: " + dev.local.voxymap.VoxyMapClient.backend().display()
            + " -- use /voxymap dh for what it has stored under this region.");
      }

      return out;
   }

   /**
    * Per-chunk accounting over the region's 16x16 chunks: has a tile, who wrote it, and -- for the
    * ones with no tile -- whether we are waiting on Xaero or on Voxy. This is the line that says
    * whether a black speck is a bug in this mod.
    */
   private static void appendCoverage(List<String> out, MapProcessor mp, MapRegion region, int regionX, int regionZ) {
      if (region == null) {
         out.add("(region not in memory)");
         return;
      }

      ClientLevel level = Minecraft.getInstance().level;
      LocalPlayer player = Minecraft.getInstance().player;
      int writeDistance = XaeroCoverage.writeDistance(mp);
      int playerChunkX = player == null ? 0 : player.blockPosition().getX() >> 4;
      int playerChunkZ = player == null ? 0 : player.blockPosition().getZ() >> 4;

      int ours = 0;
      int xaeroTiles = 0;
      int blank = 0;
      int blankXaeroWillWrite = 0;
      int noTileChunk = 0;

      for (int ltcX = 0; ltcX < 8; ltcX++) {
         for (int ltcZ = 0; ltcZ < 8; ltcZ++) {
            MapTileChunk tc;

            synchronized (region) {
               tc = region.getChunk(ltcX, ltcZ);
            }

            if (tc == null) {
               noTileChunk += 16;
               blank += 16;
               continue;
            }

            for (int i = 0; i < 4; i++) {
               for (int j = 0; j < 4; j++) {
                  if (!ScannedProbe.exists(tc, i, j)) {
                     blank++;
                     int chunkX = (((regionX << 3) + ltcX) << 2) + i;
                     int chunkZ = (((regionZ << 3) + ltcZ) << 2) + j;

                     if (XaeroCoverage.willWrite(level, chunkX, chunkZ, playerChunkX, playerChunkZ, writeDistance)) {
                        blankXaeroWillWrite++;
                     }
                  } else if (ScannedProbe.isOurs(tc.getTile(i, j))) {
                     ours++;
                  } else {
                     xaeroTiles++;
                  }
               }
            }
         }
      }

      out.add("chunks 256/region: ours=" + ours + " xaero=" + xaeroTiles + " blank=" + blank
         + " (of which " + noTileChunk + " in tile chunks that do not exist yet)");
      out.add("blank chunks Xaero is about to write itself: " + blankXaeroWillWrite);
      out.add("xaero write distance: " + (writeDistance == XaeroCoverage.UNKNOWN ? "unknown" : writeDistance + " chunks")
         + ", loadNewChunks=" + XaeroCoverage.willCreateNewTiles(mp)
         + " updateChunks=" + XaeroCoverage.willUpdateExistingTiles(mp));

      if (blank == 256) {
         out.add("NOTE: nothing is drawn here at all. If Voxy has data below, this region has never");
         out.add("been swept -- /voxymap region " + regionX + " " + regionZ + " forces it.");
      }
   }
}
