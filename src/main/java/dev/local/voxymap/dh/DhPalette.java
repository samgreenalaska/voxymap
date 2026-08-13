package dev.local.voxymap.dh;

import dev.local.voxymap.lod.PaletteWork;
import dev.local.voxymap.util.Log;
import dev.local.voxymap.voxy.PaletteSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import xaero.map.MapProcessor;
import xaero.map.MapWriter;

/**
 * The block palette for the Distant Horizons backend.
 *
 * <p>Voxy hands out its own ids for the blocks it has ingested, and {@link PaletteSnapshot} is
 * built by walking that table. DH has no such table — it answers with block <em>states</em>, one
 * run at a time — so this builds the same shape of snapshot over the game's own block-state
 * registry instead, and the id of a state is simply its registry id.
 *
 * <p>Classifying the whole registry rather than only the blocks that turn up is the deliberate
 * choice. The alternative — mint an id the first time the scanner meets a state — means the scanner
 * can be handed a state it has no classification for, on a worker thread, where classifying is not
 * allowed (Xaero's predicates read block models, which is main-thread only). Every column
 * containing such a block would have to be abandoned and re-swept later. Doing the whole registry
 * up front costs about ten seconds of sliced main-thread work once per session, is cached across
 * sweeps exactly as the Voxy palette is, and means the scanner never meets an id it cannot resolve.
 */
public final class DhPalette {
   private DhPalette() {
   }

   /** The id the scanner should use for a state, which is what the snapshot is indexed by. */
   public static int idOf(BlockState state) {
      return state == null ? 0 : Block.BLOCK_STATE_REGISTRY.getId(state);
   }

   /** The registry key of a biome DH handed back, or null if it cannot be resolved. */
   public static ResourceKey<Biome> keyOf(Object wrapped, Level level) {
      if (wrapped instanceof Holder<?> holder) {
         return holder.unwrapKey().filter(k -> k.isFor(Registries.BIOME)).map(DhPalette::castBiomeKey).orElse(null);
      }

      if (wrapped instanceof Biome biome && level != null) {
         return level.registryAccess().lookupOrThrow(Registries.BIOME).getResourceKey(biome).orElse(null);
      }

      return null;
   }

   @SuppressWarnings("unchecked")
   private static ResourceKey<Biome> castBiomeKey(ResourceKey<?> key) {
      return (ResourceKey<Biome>)key;
   }

   public static Builder builder(MapProcessor mp, Level level) {
      return new Builder(mp, level);
   }

   /** Incremental, main-thread-only, the same contract as {@code PaletteSnapshot.Builder}. */
   public static final class Builder implements PaletteWork {
      /**
       * States per slice.
       *
       * <p>Larger than the Voxy builder's 96 because there are ten times as many states to get
       * through and the deadline is checked between slices either way -- a slice is a few tens of
       * microseconds, so this trades no responsiveness for a tenth of the loop overhead.
       */
      private static final int CHUNK_OF_WORK = 512;

      private final MapProcessor mp;
      private final Level level;
      private final Registry<Biome> biomeRegistry;

      private final byte[] classOf;
      private final BlockState[] stateOf;
      private final byte[] dampeningOf;
      private final ResourceKey<Biome>[] biomeOf;

      private int cursor;
      private int failed;

      @SuppressWarnings("unchecked")
      private Builder(MapProcessor mp, Level level) {
         this.mp = mp;
         this.level = level;
         this.biomeRegistry = level.registryAccess().lookupOrThrow(Registries.BIOME);

         int states = Block.BLOCK_STATE_REGISTRY.size();
         this.classOf = new byte[states];
         this.stateOf = new BlockState[states];
         this.dampeningOf = new byte[states];

         int biomes = 0;

         for (Holder<Biome> holder : this.biomeRegistry.listElements().toList()) {
            biomes = Math.max(biomes, this.biomeRegistry.getId(holder.value()) + 1);
         }

         this.biomeOf = new ResourceKey[Math.max(1, biomes)];
      }

      @Override
      public int total() {
         return this.classOf.length;
      }

      @Override
      public int done() {
         return this.cursor;
      }

      @Override
      public boolean advance(long deadlineNanos) {
         MapWriter mw = this.mp.getMapWriter();
         Registry<Block> blockRegistry = this.mp.getWorldBlockRegistry();
         boolean flowers = this.mp.isFlowersConfig();
         BlockPos probePos = new BlockPos(0, this.level.getMinY() + 1, 0);

         while (this.cursor < this.classOf.length) {
            int end = Math.min(this.classOf.length, this.cursor + CHUNK_OF_WORK);

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
         BlockState state = Block.BLOCK_STATE_REGISTRY.byId(id);

         if (state == null) {
            state = Blocks.AIR.defaultBlockState();
         }

         this.stateOf[id] = state;

         if (state.isAir()) {
            this.classOf[id] = PaletteSnapshot.AIR;
            this.dampeningOf[id] = 0;
            return;
         }

         PaletteSnapshot.Classification c = PaletteSnapshot.classifyState(state, mw, this.level, blockRegistry, flowers, probePos);

         if (c.failed()) {
            this.failed++;

            if (this.failed <= 8) {
               Log.warn("could not classify " + state + "; it will not be drawn");
            }
         }

         this.classOf[id] = c.flags();
         this.dampeningOf[id] = c.dampening();
      }

      private void buildBiomes() {
         for (Holder<Biome> holder : this.biomeRegistry.listElements().toList()) {
            int id = this.biomeRegistry.getId(holder.value());

            if (id >= 0 && id < this.biomeOf.length) {
               this.biomeOf[id] = holder.unwrapKey().map(DhPalette::castBiomeKey).orElse(null);
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
}
