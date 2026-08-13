package dev.local.voxymap.voxy;

import dev.local.voxymap.VoxyMapConfig;
import dev.local.voxymap.util.Log;
import java.util.List;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * The Voxy half of {@code /voxymap debug}: what Voxy has stored under one region.
 *
 * <p>Lifted out of {@code DebugReport} in 0.8.0 so that class stays loadable where Voxy is not
 * installed. The DH backend's answer to the same question is {@code /voxymap dh}, which reads its
 * database rather than this one.
 */
public final class VoxyDebugReport {
   private VoxyDebugReport() {
   }

   /**
    * Walks Voxy's stored stacks the way {@code ColumnScanner} does, but only far enough to
    * classify each column: does it reach a surface, does the stack have a hole in it, or is there
    * nothing stored at all. A column that Voxy cannot resolve is a column this mod is right to
    * leave blank.
    */
   public static void append(List<String> out, VoxyMapConfig config, int regionX, int regionZ) {
      ClientLevel level = Minecraft.getInstance().level;
      WorldEngine engine = VoxySource.peek(level);

      if (level == null || engine == null) {
         out.add("voxy: no engine for this dimension -- nothing here is this mod's to draw.");
         return;
      }

      int worldMinY = level.getMinY();
      int minSy = worldMinY >> 5;

      int stacks = 0;
      int emptyStacks = 0;
      int sectionsTotal = 0;
      int reachesBottom = 0;
      int hasHoles = 0;
      int shallow = 0;
      int topOnly = 0;

      for (int lsx = 0; lsx < 16; lsx++) {
         for (int lsz = 0; lsz < 16; lsz++) {
            int sx = (regionX << 4) + lsx;
            int sz = (regionZ << 4) + lsz;
            stacks++;

            long ySet = storedYSet(engine, sx, sz);

            if (ySet == 0L) {
               emptyStacks++;
               continue;
            }

            int count = Long.bitCount(ySet);
            sectionsTotal += count;

            int top = SectionIndex.highestSy(ySet);
            int bottom = SectionIndex.lowestSy(ySet);
            boolean contiguous = count == top - bottom + 1;

            if (!contiguous) {
               hasHoles++;
            }

            if (bottom <= minSy) {
               reachesBottom++;
            } else {
               shallow++;

               if (count == 1) {
                  topOnly++;
               }
            }
         }
      }

      out.add("LOD-0 stacks 256/region: stored=" + (stacks - emptyStacks) + " never ingested=" + emptyStacks
         + ", " + sectionsTotal + " sections total");
      out.add("stacks reaching world bottom: " + reachesBottom + " | stopping short: " + shallow
         + " (of those, " + topOnly + " are a single section) | with a hole in the middle: " + hasHoles);
      out.add("incompleteChunkTolerance=" + config.incompleteChunkTolerance
         + " (0.0 means one unresolved column skips the whole chunk)");
      out.add("");
      out.add("Reading this: a stack that stops short is one where the column can be see-through");
      out.add("all the way down with no floor stored -- deep ocean, almost always. Those columns");
      out.add("get an inferred surface rather than being left blank. Stacks with a hole, and stacks");
      out.add("never ingested, are left blank on purpose: the real surface could be inside the gap.");
      out.add("Blank chunks whose stacks all reach the bottom would be a bug in this mod.");
   }

   /** Rebuilds one column's Y bitset straight from the engine, without a full enumeration. */
   private static long storedYSet(WorldEngine engine, int sx, int sz) {
      long ySet = 0L;

      for (int sy = -32; sy <= 31; sy++) {
         WorldSection section = null;

         try {
            section = engine.acquireIfExists(0, sx, sy, sz);
            if (section != null) {
               ySet |= 1L << (sy + 32);
            }
         } catch (Throwable t) {
            Log.warn("debug: probing Voxy section " + sx + "," + sy + "," + sz + " failed", t);
         } finally {
            if (section != null) {
               try {
                  section.release();
               } catch (Throwable ignored) {
               }
            }
         }
      }

      return ySet;
   }
}
