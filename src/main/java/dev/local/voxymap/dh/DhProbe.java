package dev.local.voxymap.dh;

import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * {@code /voxymap dh} — what Distant Horizons can see, and whether this mod can reach it.
 *
 * <p>The DH backend has two halves that fail in completely different ways, and telling them apart
 * from the outside is impossible: streamed terrain not appearing could be the ingest never handing
 * DH anything, or DH refusing what it was handed, or the map read finding nothing afterwards. This
 * prints all three in one screen, in the order they happen.
 *
 * <p>The read at the end is the one that settles arguments: it asks DH for the column the player is
 * standing in and prints the runs it gets back. Data there means the write path worked and anything
 * still missing is the map's problem; nothing there means the map was never going to have anything
 * to draw.
 */
public final class DhProbe {
   private DhProbe() {
   }

   public static List<String> report() {
      List<String> out = new ArrayList<>();
      Minecraft mc = Minecraft.getInstance();
      ClientLevel level = mc.level;

      out.add("=== distant horizons ===");
      out.add("installed: " + DhBridge.installed() + "   version: " + DhBridge.version());
      out.add("api ready: " + DhBridge.available());

      if (level == null) {
         out.add("no client level.");
         return out;
      }

      IDhApiLevelWrapper wrapper = DhBridge.levelFor(level);

      if (wrapper == null) {
         out.add("DH has no level for the dimension you are in. Nothing can be written or read until");
         out.add("it does -- it builds one shortly after joining.");
         return out;
      }

      out.add("level: " + wrapper.getDimensionName() + "   id: " + wrapper.getDhIdentifier());
      out.add("save folder: " + wrapper.getDhSaveFolder());
      out.add("render distance: " + DhBridge.renderDistanceChunks(-1) + " chunks   rendering: " + DhBridge.renderingEnabled());
      out.add("y range: " + wrapper.getMinHeight() + " .. " + wrapper.getMaxHeight());
      out.add("");
      out.add(DhIngest.status());
      out.add("");

      int chunkX = mc.player == null ? 0 : mc.player.chunkPosition().x();
      int chunkZ = mc.player == null ? 0 : mc.player.chunkPosition().z();
      out.addAll(column(chunkX, chunkZ));
      return out;
   }

   /** The runs DH has stored for one chunk, which is exactly what the map scanner walks. */
   public static List<String> column(int chunkX, int chunkZ) {
      List<String> out = new ArrayList<>();
      Minecraft mc = Minecraft.getInstance();
      ClientLevel level = mc.level;
      IDhApiLevelWrapper wrapper = DhBridge.levelFor(level);

      out.add("=== chunk " + chunkX + "," + chunkZ + " ===");

      if (wrapper == null) {
         out.add("no DH level.");
         return out;
      }

      // A real cache, not null: the read is the same call the map sweep makes, and it has to fail
      // and succeed the same way here or this command is testing something else.
      DhApiTerrainDataPoint[][][] data = DhBridge.chunkData(wrapper, chunkX, chunkZ, DhBridge.newCache());

      if (DhBridge.columnsWithData(data) == 0) {
         out.add("DH has nothing stored here. The map will leave this chunk to Xaero.");
         out.add("  DH said: " + (DhBridge.lastReadMessage() == null ? "nothing (a successful, empty answer)" : DhBridge.lastReadMessage()));
         return out;
      }

      int columns = 0;
      int runs = 0;

      for (DhApiTerrainDataPoint[][] row : data) {
         if (row == null) {
            continue;
         }

         for (DhApiTerrainDataPoint[] col : row) {
            if (col != null && col.length > 0) {
               columns++;
               runs += col.length;
            }
         }
      }

      out.add("detail width: " + data.length + " columns across (16 means per-block)");
      out.add("columns with data: " + columns + "   runs: " + runs);

      DhApiTerrainDataPoint[] sample = data[0] == null || data[0].length == 0 ? null : data[0][0];

      if (sample == null || sample.length == 0) {
         out.add("first column: empty");
         return out;
      }

      out.add("first column, top down:");

      for (int i = 0; i < Math.min(8, sample.length); i++) {
         DhApiTerrainDataPoint p = sample[i];

         if (p == null) {
            continue;
         }

         out.add("  y " + p.bottomYBlockPos + ".." + p.topYBlockPos
            + "  " + (p.blockStateWrapper == null ? "?" : p.blockStateWrapper.getSerialString())
            + "  biome " + (p.biomeWrapper == null ? "?" : p.biomeWrapper.getName())
            + "  light b" + p.blockLightLevel + "/s" + p.skyLightLevel
            + "  detail " + p.detailLevel);
      }

      if (sample.length > 8) {
         out.add("  ... " + (sample.length - 8) + " more");
      }

      return out;
   }
}
