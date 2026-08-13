package dev.local.voxymap.client;

import dev.local.voxymap.MapWipe;
import dev.local.voxymap.net.SectionCodec;
import dev.local.voxymap.util.Log;
import dev.local.voxymap.voxy.VoxySource;
import java.nio.file.Path;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.voxelization.WorldVoxilizedSectionMipper;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldUpdater;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Writes streamed sections into Voxy's database. The 0.7 receive path, moved behind
 * {@link StreamIngest} without a change to what it does.
 *
 * <h2>Translating ids</h2>
 *
 * <p>Sections arrive with two lookup tables rather than raw ids, because block and biome ids are
 * assigned per engine and the server's numbering means nothing here (see {@link SectionCodec}).
 * Each section is rebuilt against this client's own {@link Mapper}: block states come back through
 * the game's block-state registry, biomes through their registry name. A palette entry that cannot
 * be resolved -- a block from a mod this client lacks -- becomes air rather than something wrong.
 */
public final class VoxyStreamIngest implements StreamIngest {
   private long unresolved;
   private long written;
   private boolean verifiedWriteback;

   @Override
   public boolean ready(ClientLevel level) {
      WorldEngine engine = WorldIdentifier.ofEngineNullable(level);
      return engine != null && engine.isLive();
   }

   /** Voxy keeps one database per world under its saves root, so the id is part of the path. */
   @Override
   public Path storeFolder(ClientLevel level) {
      Path root = MapWipe.voxySaveFolder();
      String id = this.worldId(level);
      return root == null || id == null || id.isEmpty() ? null : root.resolve(id);
   }

   @Override
   public String worldId(ClientLevel level) {
      return WorldIdentifier.of(level).getWorldId();
   }

   @Override
   public Declaration declare(ClientLevel level, Path folder, String worldId) {
      LodDeclareWorker worker = new LodDeclareWorker(level, folder, worldId);

      return new Declaration() {
         @Override
         public boolean done() {
            return worker.done();
         }

         @Override
         public LodDeclareWorker.Result result() {
            return worker.result();
         }

         @Override
         public Throwable error() {
            return worker.error();
         }
      };
   }

   @Override
   public boolean write(ClientLevel level, SectionCodec.Decoded decoded) {
      WorldEngine engine = WorldIdentifier.ofEngineNullable(level);

      if (engine == null || !engine.isLive()) {
         return false;
      }

      this.write(engine, engine.getMapper(), level, decoded);
      return true;
   }

   /** Voxy holds a column the moment it is inserted, so there is nothing to wait for. */
   @Override
   public boolean columnComplete(ClientLevel level, long columnKey, long hash, long ySet) {
      return true;
   }

   @Override
   public void reset() {
   }

   @Override
   public String status() {
      return " written=" + this.written + " unresolvedPaletteEntries=" + this.unresolved;
   }

   private void write(WorldEngine engine, Mapper mapper, ClientLevel level, SectionCodec.Decoded decoded) {
      int[] blockIds = new int[decoded.blockStateIds().length];

      for (int i = 0; i < blockIds.length; i++) {
         BlockState state = Block.BLOCK_STATE_REGISTRY.byId(decoded.blockStateIds()[i]);

         if (state == null) {
            this.unresolved++;
            blockIds[i] = 0;
         } else {
            blockIds[i] = mapper.getIdForBlockState(state);
         }
      }

      int[] biomeIds = new int[decoded.biomeNames().length];
      var biomes = level.registryAccess().lookupOrThrow(Registries.BIOME);

      for (int i = 0; i < biomeIds.length; i++) {
         String name = decoded.biomeNames()[i];
         Holder<Biome> holder = null;

         if (name != null && !name.isEmpty()) {
            Identifier location = Identifier.tryParse(name);

            if (location != null) {
               holder = biomes.get(ResourceKey.create(Registries.BIOME, location)).orElse(null);
            }
         }

         if (holder == null) {
            this.unresolved++;
            biomeIds[i] = 0;
         } else {
            biomeIds[i] = mapper.getIdForBiome(holder);
         }
      }

      // Translate the whole 32^3 stack into this client's ids first.
      long[] source = decoded.voxels();
      long[] translated = new long[source.length];

      for (int i = 0; i < source.length; i++) {
         long voxel = source[i];
         int block = Mapper.getBlockId(voxel);
         int biome = Mapper.getBiomeId(voxel);
         int light = Mapper.getLightId(voxel);

         translated[i] = Mapper.withLight(
            Mapper.withBlockBiome(
               0L,
               block >= 0 && block < blockIds.length ? blockIds[block] : 0,
               biome >= 0 && biome < biomeIds.length ? biomeIds[biome] : 0
            ),
            light
         );
      }

      // Insert through Voxy's own pipeline instead of writing the raw array.
      //
      // Poking _unsafeGetRawDataArray() and marking dirty populates LOD level 0 and NOTHING ELSE.
      // Voxy's renderer is hierarchical: it descends a quadtree from coarse nodes, and levels 1..4
      // are what it draws everything beyond the innermost ring from. With those empty, streamed
      // ground rendered only in the small band where level 0 is used directly -- about 8 chunks,
      // against a configured render distance of 7 x 512 blocks. The map was right the whole time
      // because the map reads the database, which had the level-0 data all along.
      //
      // VoxelIngestService.processJob is the reference: convert, then mipSection, then
      // WorldUpdater.insertUpdate. The mip step is the one that was missing.
      //
      // A VoxelizedSection is a 16^3 unit (level 0 occupies indices 0..4095), so one streamed 32^3
      // section is eight of them. Reused across the eight rather than reallocated, the way Voxy's
      // own ingest keeps one per thread.
      int sx = WorldEngine.getX(decoded.key());
      int sy = WorldEngine.getY(decoded.key());
      int sz = WorldEngine.getZ(decoded.key());

      VoxelizedSection scratch = VoxelizedSection.createEmpty();

      for (int oct = 0; oct < 8; oct++) {
         int ox = (oct & 1) * 16;
         int oy = ((oct >> 1) & 1) * 16;
         int oz = ((oct >> 2) & 1) * 16;

         scratch.zero();
         int nonAir = 0;

         for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
               for (int x = 0; x < 16; x++) {
                  // WorldSection.getIndex is (y<<10)|(z<<5)|x over 32^3; VoxelizedSection level 0
                  // is (y<<8)|(z<<4)|x over 16^3. Same Y-major order, different edge length.
                  long v = translated[((oy + y) << 10) | ((oz + z) << 5) | (ox + x)];
                  scratch.section[(y << 8) | (z << 4) | x] = v;

                  if (Mapper.getBlockId(v) != 0) {
                     nonAir++;
                  }
               }
            }
         }

         scratch.lvl0NonAirCount = nonAir;
         // Chunk-section coordinates: 16-block units, so twice the 32-block section index.
         scratch.setPosition((sx << 1) | (ox >> 4), (sy << 1) | (oy >> 4), (sz << 1) | (oz >> 4));

         WorldVoxilizedSectionMipper.mipSection(scratch, mapper);
         WorldUpdater.insertUpdate(engine, scratch);
      }

      this.written++;

      if (!this.verifiedWriteback) {
         this.verifiedWriteback = true;
         Log.info("first streamed section inserted through the mip pipeline: key=" + decoded.key()
            + " section=" + sx + "," + sy + "," + sz + " voxel[0]=" + (translated.length > 0 ? translated[0] : 0L));
      }
   }

   /** Read-only probe for {@code /voxymap status}; does not take a reference. */
   public static boolean enginePresent(ClientLevel level) {
      return VoxySource.peek(level) != null;
   }

   /** Whether the client is in a world Voxy is tracking at all, without touching Minecraft state. */
   public static boolean live() {
      ClientLevel level = Minecraft.getInstance().level;
      return level != null && VoxySource.peek(level) != null;
   }
}
