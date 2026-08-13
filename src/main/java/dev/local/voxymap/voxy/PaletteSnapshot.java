package dev.local.voxymap.voxy;

import dev.local.voxymap.util.Log;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
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

   /**
    * For a backend that hands out its own ids.
    *
    * <p>Nothing in here is Voxy-specific once it is built -- an id is an index and the arrays say
    * what that index means. Voxy supplies its {@code Mapper}'s ids; the Distant Horizons palette
    * ({@code dev.local.voxymap.dh.DhPalette}) hands out its own as it meets block states, and the
    * scanner and tile writer cannot tell the difference.
    */
   public static PaletteSnapshot of(byte[] classOf, BlockState[] stateOf, byte[] dampeningOf, ResourceKey<Biome>[] biomeOf) {
      return new PaletteSnapshot(classOf, stateOf, dampeningOf, biomeOf);
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


   /** What one block state comes out as, and whether asking threw. */
   public record Classification(byte flags, byte dampening, boolean failed) {
   }

   /**
    * Runs one block state through Xaero's own predicates, in Xaero's own order.
    *
    * <p>Shared by both palettes rather than reimplemented per backend: this is the code that keeps
    * the seam between a synthetic tile and a scanned one invisible, and two copies of it would be
    * two chances for a backend to draw the world slightly differently.
    *
    * <p>Main thread only -- {@code hasVanillaColor} and {@code shouldOverlay} read block models.
    */
   public static Classification classifyState(
      BlockState state,
      MapWriter mw,
      Level level,
      Registry<Block> blockRegistry,
      boolean flowers,
      BlockPos probePos
   ) {
      if (state == null || state.isAir()) {
         return new Classification(AIR, (byte)0, false);
      }

      byte flags = 0;

      try {
         // hasVanillaColor runs first because it is what populates MapWriter's buggedStates
         // list, which isInvisible then consults.
         if (!mw.hasVanillaColor(state, level, blockRegistry, probePos)) {
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

         return new Classification(flags, (byte)Math.max(0, Math.min(255, state.getLightDampening())), false);
      } catch (Throwable t) {
         // A broken modded block must not take the sweep down with it: drop it from the map.
         return new Classification((byte)(flags | NO_COLOUR), (byte)15, true);
      }
   }
}
