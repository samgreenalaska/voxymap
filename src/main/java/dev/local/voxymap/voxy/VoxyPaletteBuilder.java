package dev.local.voxymap.voxy;

import dev.local.voxymap.lod.PaletteWork;
import dev.local.voxymap.util.Log;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import xaero.map.MapProcessor;
import xaero.map.MapWriter;

/**
 * Builds a {@link PaletteSnapshot} out of Voxy's own block and biome tables.
 *
 * <p>This was an inner class of {@code PaletteSnapshot} until 0.8.0, and moved for the same reason
 * {@link VoxyIndexScan} did: the snapshot is read by both backends, so it has to stay loadable on a
 * client where Voxy is not installed, and a class whose fields are {@code Mapper.StateEntry[]}
 * cannot promise that. Nothing about the classification changed — every state still goes through
 * Xaero's own predicates, in Xaero's order, via {@link PaletteSnapshot#classifyState}.
 *
 * <p>Main thread only, a slice at a time: the predicates read block models.
 */
public final class VoxyPaletteBuilder implements PaletteWork {
   private static final int CHUNK_OF_WORK = 96;

   private final MapProcessor mp;
   private final Level level;
   private final Mapper.StateEntry[] states;
   private final Mapper.BiomeEntry[] biomes;

   private final byte[] classOf;
   private final BlockState[] stateOf;
   private final byte[] dampeningOf;
   private final ResourceKey<Biome>[] biomeOf;

   private int cursor;
   private int failed;

   @SuppressWarnings("unchecked")
   VoxyPaletteBuilder(WorldEngine engine, MapProcessor mp, Level level) {
      this.mp = mp;
      this.level = level;
      this.states = engine.getMapper().getStateEntries();
      this.biomes = engine.getMapper().getBiomeEntries();
      this.classOf = new byte[this.states.length];
      this.stateOf = new BlockState[this.states.length];
      this.dampeningOf = new byte[this.states.length];
      this.biomeOf = new ResourceKey[this.biomes.length];
   }

   @Override
   public int total() {
      return this.states.length;
   }

   @Override
   public int done() {
      return this.cursor;
   }

   /** @return true once the whole palette has been classified. */
   @Override
   public boolean advance(long deadlineNanos) {
      MapWriter mw = this.mp.getMapWriter();
      Registry<Block> blockRegistry = this.mp.getWorldBlockRegistry();
      boolean flowers = this.mp.isFlowersConfig();
      BlockPos probePos = new BlockPos(0, this.level.getMinY() + 1, 0);

      while (this.cursor < this.states.length) {
         int end = Math.min(this.states.length, this.cursor + CHUNK_OF_WORK);

         while (this.cursor < end) {
            this.classify(this.cursor, mw, blockRegistry, flowers, probePos);
            this.cursor++;
         }

         if (System.nanoTime() >= deadlineNanos) {
            return false;
         }
      }

      this.buildBiomes();
      return true;
   }

   private void classify(int id, MapWriter mw, Registry<Block> blockRegistry, boolean flowers, BlockPos probePos) {
      Mapper.StateEntry entry = this.states[id];
      BlockState state = entry == null ? null : entry.state;

      if (state == null) {
         state = Blocks.AIR.defaultBlockState();
      }

      this.stateOf[id] = state;

      // Mapper.isAir(id) is literally blockId == 0, so id 0 is the only true air entry.
      if (id == 0 || state.isAir()) {
         this.classOf[id] = PaletteSnapshot.AIR;
         this.dampeningOf[id] = 0;
         return;
      }

      PaletteSnapshot.Classification c = PaletteSnapshot.classifyState(state, mw, this.level, blockRegistry, flowers, probePos);

      if (c.failed()) {
         this.failed++;

         if (this.failed <= 8) {
            Log.warn("could not classify block id " + id + " (" + state + "), skipping it");
         }
      }

      this.classOf[id] = c.flags();
      this.dampeningOf[id] = c.dampening();
   }

   private void buildBiomes() {
      for (int i = 0; i < this.biomes.length; i++) {
         Mapper.BiomeEntry entry = this.biomes[i];

         if (entry == null || entry.biome == null) {
            continue;
         }

         try {
            Identifier id = Identifier.parse(entry.biome);

            if (id != null) {
               this.biomeOf[i] = ResourceKey.create(Registries.BIOME, id);
            }
         } catch (Throwable t) {
            Log.warn("unparseable biome id in the Voxy palette: " + entry.biome);
         }
      }
   }

   @Override
   public PaletteSnapshot finish() {
      if (this.failed > 0) {
         Log.warn(this.failed + " block states could not be classified and will not be drawn");
      }

      return PaletteSnapshot.of(this.classOf, this.stateOf, this.dampeningOf, this.biomeOf);
   }
}
