package dev.local.voxymap.dh;

import com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataCache;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import dev.local.voxymap.lod.Backends;
import dev.local.voxymap.lod.LodBackend;
import dev.local.voxymap.lod.PaletteWork;
import dev.local.voxymap.lod.TileScanner;
import dev.local.voxymap.voxy.PaletteSnapshot;
import dev.local.voxymap.voxy.SectionIndex;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import xaero.map.MapProcessor;

/**
 * The Distant Horizons backend for the Xaero bridge.
 *
 * <p>Everything the Voxy backend gets for free, this one has to arrange. DH's api can be asked what
 * it holds at a chunk but not what it holds <em>anywhere</em> — there is no equivalent of Voxy's
 * {@code iteratePositions}, and there is nothing behind the api worth reaching past it for. So the
 * index is assembled from the two things that can be known cheaply:
 *
 * <ul>
 *   <li><b>What the server streamed us.</b> Every column this client has taken from a voxymap
 *       server is recorded in {@code voxymap-have.bin} along with the heights it arrived at, and
 *       every one of those was handed to DH. That is the bulk of the map on a server, and it is
 *       exact.
 *   <li><b>What DH scanned for itself.</b> Everything inside DH's own render distance, which is
 *       well past Xaero's write distance. There is no record of which of those chunks DH actually
 *       kept, so the whole disc is offered as candidates and the scanner finds out — a chunk DH has
 *       nothing for reads back empty and costs one query.
 * </ul>
 *
 * <p>Candidates, not claims: nothing here asserts DH has data, so a wrong guess costs a read and
 * never a wrong tile.
 */
public final class DhBackend implements LodBackend {
   /** Used when DH will not say what its render distance is. Its own default is in this region. */
   private static final int FALLBACK_RENDER_DISTANCE_CHUNKS = 128;

   private final java.util.function.Supplier<Long2LongOpenHashMap> streamedColumns;

   private volatile ClientLevel boundLevel;
   private volatile IDhApiLevelWrapper dhLevel;
   private volatile IDhApiTerrainDataCache cache;

   /**
    * @param streamedColumns what the client has taken from the server, as column key to height
    *     bitset. Read on the sweep worker, so it must be safe to call from there.
    */
   public DhBackend(java.util.function.Supplier<Long2LongOpenHashMap> streamedColumns) {
      this.streamedColumns = streamedColumns;
   }

   @Override
   public Backends kind() {
      return Backends.DH;
   }

   @Override
   public boolean present(ClientLevel level) {
      return DhBridge.levelFor(level) != null;
   }

   @Override
   public boolean open(ClientLevel level) {
      this.close();

      if (level == null) {
         return false;
      }

      IDhApiLevelWrapper wrapper = DhBridge.levelFor(level);

      if (wrapper == null) {
         return false;
      }

      this.boundLevel = level;
      this.dhLevel = wrapper;
      this.cache = DhBridge.newCache();
      return true;
   }

   /**
    * Nothing to give back.
    *
    * <p>The Voxy source has to be released off the main thread or the game deadlocks on shutdown;
    * DH hands out no reference at all, so this is just forgetting. The interface still carries the
    * release calls because the sweep worker is written against it.
    */
   @Override
   public void close() {
      this.boundLevel = null;
      this.dhLevel = null;
      this.cache = null;
   }

   @Override
   public boolean stillValid() {
      return this.dhLevel != null && this.boundLevel != null && Minecraft.getInstance().level == this.boundLevel;
   }

   @Override
   public boolean levelGone() {
      ClientLevel now = Minecraft.getInstance().level;
      return now == null || now != this.boundLevel;
   }

   @Override
   public boolean releaseIfLevelGone() {
      if (this.dhLevel == null || !this.levelGone()) {
         return false;
      }

      this.close();
      return true;
   }

   @Override
   public SectionIndex enumerate(BooleanSupplier abort) {
      SectionIndex.Builder builder = this.buildIndex(abort);
      return builder == null ? null : builder.build();
   }

   @Override
   public SectionIndex.FingerprintScan scanFingerprints(BooleanSupplier abort) {
      SectionIndex.Builder builder = this.buildIndex(abort);
      return builder == null ? null : builder.fingerprints();
   }

   /**
    * The candidate set, and the fingerprints that decide when to come back to a region.
    *
    * <p>Streamed columns fingerprint themselves: the heights they arrived at are part of the hash,
    * so a region gains a fingerprint the moment new ground streams into it and the sweep picks it
    * up on the next pass. That is the same signal the Voxy backend gets from its section count.
    *
    * <p>The locally-scanned disc has no such signal — DH does not say when it stores a chunk — so
    * those regions are hashed against the player's own region instead. Standing still, they settle
    * and stop being re-swept; walking into a new region re-offers everything nearby, which is
    * exactly when DH has been given new ground to store.
    */
   private SectionIndex.Builder buildIndex(BooleanSupplier abort) {
      SectionIndex.Builder builder = new SectionIndex.Builder();
      Long2LongOpenHashMap streamed = this.streamedColumns.get();

      if (streamed != null) {
         int counter = 0;

         for (Long2LongOpenHashMap.Entry e : streamed.long2LongEntrySet()) {
            if ((counter++ & 0x3FF) == 0 && abort.getAsBoolean()) {
               return null;
            }

            int sx = SectionIndex.columnX(e.getLongKey());
            int sz = SectionIndex.columnZ(e.getLongKey());
            long ySet = e.getLongValue();

            for (int sy = -32; sy <= 31; sy++) {
               if (SectionIndex.hasSy(ySet, sy)) {
                  builder.add(sx, sy, sz);
               }
            }
         }
      }

      this.addLocalDisc(builder, abort);
      return builder;
   }

   private void addLocalDisc(SectionIndex.Builder builder, BooleanSupplier abort) {
      ClientLevel level = this.boundLevel;
      Minecraft mc = Minecraft.getInstance();

      if (level == null || mc.player == null) {
         return;
      }

      int radiusChunks = DhBridge.renderDistanceChunks(FALLBACK_RENDER_DISTANCE_CHUNKS);
      // A LOD-0 column is 2x2 chunks, so the disc is half as wide in columns as it is in chunks.
      int radius = Math.max(1, radiusChunks >> 1);
      int centreX = mc.player.chunkPosition().x() >> 1;
      int centreZ = mc.player.chunkPosition().z() >> 1;

      // One section per column, at the height the player is at. The index only decides which tile
      // chunks are worth reading, and the DH read is per chunk whatever height it is told.
      int sy = Math.max(-32, Math.min(31, mc.player.blockPosition().getY() >> 5));
      int squared = radius * radius;

      // What the disc's regions are salted with: the player's own region. See buildIndex.
      long playerRegion = SectionIndex.regionKey(centreX >> 4, centreZ >> 4);
      it.unimi.dsi.fastutil.longs.LongOpenHashSet salted = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

      for (int dz = -radius; dz <= radius; dz++) {
         if (abort.getAsBoolean()) {
            return;
         }

         for (int dx = -radius; dx <= radius; dx++) {
            if (dx * dx + dz * dz > squared) {
               continue;
            }

            int sx = centreX + dx;
            int sz = centreZ + dz;
            builder.add(sx, sy, sz);

            long region = SectionIndex.regionKey(sx >> 4, sz >> 4);

            if (salted.add(region)) {
               builder.salt(region, playerRegion * 0x9E3779B97F4A7C15L);
            }
         }
      }
   }

   @Override
   public boolean paletteMatches(PaletteSnapshot snapshot) {
      return snapshot.blockCount() == Block.BLOCK_STATE_REGISTRY.size();
   }

   @Override
   public PaletteWork paletteWork(MapProcessor mp, Level level) {
      return DhPalette.builder(mp, level);
   }

   @Override
   public TileScanner scanner(SectionIndex index, PaletteSnapshot palette, int worldMinY, int worldMaxY, int overlayStride) {
      return new DhTileScanner(this.dhLevel, this.boundLevel, palette, worldMinY, overlayStride, this.cache);
   }

   @Override
   public String describe(ClientLevel level) {
      IDhApiLevelWrapper wrapper = this.dhLevel != null ? this.dhLevel : DhBridge.levelFor(level);

      if (wrapper == null) {
         return "distant horizons: installed (" + DhBridge.version() + ") but has no level for this dimension yet";
      }

      return "distant horizons: " + DhBridge.version()
         + ", level " + wrapper.getDimensionName()
         + ", render distance " + DhBridge.renderDistanceChunks(FALLBACK_RENDER_DISTANCE_CHUNKS) + " chunks"
         + (DhBridge.renderingEnabled() ? "" : " (rendering off)");
   }
}
