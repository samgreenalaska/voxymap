package dev.local.voxymap.voxy;

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
import net.minecraft.world.level.material.FluidState;
import xaero.map.MapProcessor;
import xaero.map.MapWriter;

/**
 * Immutable classification table over Voxy's block palette, indexed by Voxy block id.
 *
 * <p>Every predicate is delegated to the live {@link MapWriter} instance, so a synthetic tile
 * classifies a block exactly the way a genuinely scanned tile would. That is what keeps the
 * seam between our data and Xaero's own invisible.
 *
 * <p>Building touches client-side state (block models, map colours), so it happens on the main
 * thread, spread over several ticks. Once built the table is read-only and safe to share with
 * the scanner thread.
 */
public final class PaletteSnapshot {
   public static final byte AIR = 1;
   public static final byte INVISIBLE = 2;
   public static final byte TRANSLUCENT = 4;
   public static final byte NO_COLOUR = 8;
   public static final byte GLOWING = 16;

   private final byte[] classOf;
   private final BlockState[] stateOf;
   private final byte[] dampeningOf;
   private final ResourceKey<Biome>[] biomeOf;

   private PaletteSnapshot(byte[] classOf, BlockState[] stateOf, byte[] dampeningOf, ResourceKey<Biome>[] biomeOf) {
      this.classOf = classOf;
      this.stateOf = stateOf;
      this.dampeningOf = dampeningOf;
      this.biomeOf = biomeOf;
   }

   public int blockCount() {
      return this.classOf.length;
   }

   public int biomeCount() {
      return this.biomeOf.length;
   }

   /**
    * Voxy may ingest new blocks while a sweep runs, pushing ids past the end of the snapshot.
    * Those are treated as ordinary opaque terrain rather than dropped.
    */
   public boolean isKnown(int blockId) {
      return blockId >= 0 && blockId < this.classOf.length;
   }

   public byte classOf(int blockId) {
      return this.isKnown(blockId) ? this.classOf[blockId] : 0;
   }

   /** Drawn as nothing at all. Checked before {@link #isTranslucent}, the way Xaero orders it. */
   public boolean isInvisible(int blockId) {
      return (this.classOf(blockId) & (AIR | INVISIBLE)) != 0;
   }

   /**
    * No usable map colour. Checked <em>after</em> {@link #isTranslucent}, because
    * {@code loadPixelHelp} tests {@code shouldOverlay} before {@code hasVanillaColor} -- a
    * translucent block is drawn as an overlay whether or not it has a map colour of its own.
    */
   public boolean isColourless(int blockId) {
      return (this.classOf(blockId) & NO_COLOUR) != 0;
   }

   public boolean isTranslucent(int blockId) {
      return (this.classOf(blockId) & TRANSLUCENT) != 0;
   }

   public boolean isGlowing(int blockId) {
      return (this.classOf(blockId) & GLOWING) != 0;
   }

   public BlockState stateOf(int blockId) {
      return this.isKnown(blockId) && this.stateOf[blockId] != null ? this.stateOf[blockId] : Blocks.STONE.defaultBlockState();
   }

   public int dampeningOf(int blockId) {
      return this.isKnown(blockId) ? this.dampeningOf[blockId] & 0xFF : 15;
   }

   /** Null is a legal answer -- {@code MapBlock.write} keeps the previous biome when handed null. */
   public ResourceKey<Biome> biomeOf(int biomeId) {
      return biomeId >= 0 && biomeId < this.biomeOf.length ? this.biomeOf[biomeId] : null;
   }

   public static Builder builder(WorldEngine engine, MapProcessor mp, Level level) {
      return new Builder(engine, mp, level);
   }

   /** Incremental, main-thread-only builder. Call {@link #advance} until it returns true. */
   public static final class Builder {
      private static final int CHUNK_OF_WORK = 96;

      private final MapProcessor mp;
      private final Level level;
      private final Mapper.StateEntry[] states;
      private final Mapper.BiomeEntry[] biomes;

      private final byte[] classOf;
      private final BlockState[] stateOf;
      private final byte[] dampeningOf;
      @SuppressWarnings("unchecked")
      private final ResourceKey<Biome>[] biomeOf;

      private int cursor;
      private int failed;

      private Builder(WorldEngine engine, MapProcessor mp, Level level) {
         this.mp = mp;
         this.level = level;
         this.states = engine.getMapper().getStateEntries();
         this.biomes = engine.getMapper().getBiomeEntries();
         this.classOf = new byte[this.states.length];
         this.stateOf = new BlockState[this.states.length];
         this.dampeningOf = new byte[this.states.length];
         this.biomeOf = new ResourceKey[this.biomes.length];
      }

      public int total() {
         return this.states.length;
      }

      public int done() {
         return this.cursor;
      }

      /** @return true once the whole palette has been classified. */
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

         byte flags = 0;
         if (id == 0 || state.isAir()) {
            // Mapper.isAir(id) is literally blockId == 0, so id 0 is the only true air entry.
            this.classOf[id] = AIR;
            this.dampeningOf[id] = 0;
            return;
         }

         try {
            // hasVanillaColor runs first because it is what populates MapWriter's buggedStates
            // list, which isInvisible then consults.
            if (!mw.hasVanillaColor(state, this.level, blockRegistry, probePos)) {
               flags |= NO_COLOUR;
            }

            if (mw.isInvisible(state, state.getBlock(), flowers)) {
               flags |= INVISIBLE;
            }

            // Ask about the *fluid* for a block that is nothing but fluid, which is what
            // MapWriter.loadPixel does: it converts the fluid state to a block and passes the
            // FluidState alongside, so loadPixelHelp ends up in shouldOverlay's FluidState branch.
            //
            // The two branches answer completely different questions. The FluidState branch reads
            // the fluid's own render layer, which for water is translucent. The BlockState branch
            // reads the block model's quads -- and water has no block model, so it comes back
            // opaque. Asking the block classified water as solid terrain, so instead of a seafloor
            // with a water overlay over it, the sea was drawn as a flat sheet of water blocks at
            // the surface height: no depth shading, no overlay blending, and visibly different
            // from every ocean Xaero scanned itself.
            //
            // Waterlogged blocks are deliberately not routed here. Xaero draws them twice, once as
            // the water overlay and once as the block, and one palette entry cannot express both;
            // they keep the block's own classification and lose the water, as before.
            FluidState fluid = state.getFluidState();
            boolean pureFluid = !fluid.isEmpty() && fluid.createLegacyBlock().getBlock() == state.getBlock();

            if (mw.shouldOverlay(pureFluid ? fluid : state)) {
               flags |= TRANSLUCENT;
            }

            if (mw.isGlowing(state)) {
               flags |= GLOWING;
            }

            this.dampeningOf[id] = (byte)Math.max(0, Math.min(255, state.getLightDampening()));
         } catch (Throwable t) {
            // A broken modded block must not take the sweep down with it: drop it from the map.
            this.failed++;
            flags |= NO_COLOUR;
            this.dampeningOf[id] = 15;
            if (this.failed <= 8) {
               Log.warn("could not classify block id " + id + " (" + state + "), skipping it", t);
            }
         }

         this.classOf[id] = flags;
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

      public PaletteSnapshot finish() {
         if (this.failed > 0) {
            Log.warn(this.failed + " block states could not be classified and will not be drawn");
         }

         return new PaletteSnapshot(this.classOf, this.stateOf, this.dampeningOf, this.biomeOf);
      }
   }
}
