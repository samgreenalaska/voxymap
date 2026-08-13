package dev.local.voxymap.voxy;

import dev.local.voxymap.lod.Backends;
import dev.local.voxymap.lod.LodBackend;
import dev.local.voxymap.lod.PaletteWork;
import dev.local.voxymap.lod.TileScanner;
import java.util.function.BooleanSupplier;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import xaero.map.MapProcessor;

/**
 * The Voxy backend: {@link VoxySource} for the handle, {@link VoxyIndexScan} for the walk,
 * {@link ColumnScanner} for the read.
 *
 * <p>Every part of this existed before 0.8 and none of it changed; this is the seam that lets a
 * second backend exist beside it. Loading this class loads Voxy's, which is exactly why nothing
 * mentions it except {@link Backends}.
 */
public final class VoxyBackend implements LodBackend {
   private final VoxySource source = new VoxySource();

   @Override
   public Backends kind() {
      return Backends.VOXY;
   }

   @Override
   public boolean present(ClientLevel level) {
      return VoxySource.peek(level) != null;
   }

   @Override
   public boolean open(ClientLevel level) {
      return this.source.open(level);
   }

   @Override
   public void close() {
      this.source.close();
   }

   @Override
   public boolean stillValid() {
      return this.source.stillValid();
   }

   @Override
   public boolean levelGone() {
      return this.source.levelGone();
   }

   @Override
   public boolean releaseIfLevelGone() {
      return this.source.releaseIfLevelGone();
   }

   @Override
   public SectionIndex enumerate(BooleanSupplier abort) {
      var engine = this.source.engine();
      return engine == null ? null : VoxyIndexScan.enumerate(engine, abort);
   }

   @Override
   public SectionIndex.FingerprintScan scanFingerprints(BooleanSupplier abort) {
      var engine = this.source.engine();
      return engine == null ? null : VoxyIndexScan.scanFingerprints(engine, abort);
   }

   @Override
   public boolean paletteMatches(PaletteSnapshot snapshot) {
      try {
         var mapper = this.source.engine().getMapper();
         return mapper.getStateEntries().length == snapshot.blockCount() && mapper.getBiomeEntries().length == snapshot.biomeCount();
      } catch (Throwable t) {
         return false;
      }
   }

   @Override
   public PaletteWork paletteWork(MapProcessor mp, Level level) {
      return new VoxyPaletteBuilder(this.source.engine(), mp, level);
   }

   @Override
   public TileScanner scanner(SectionIndex index, PaletteSnapshot palette, int worldMinY, int worldMaxY, int overlayStride) {
      return new ColumnScanner(this.source.engine(), index, palette, worldMinY, worldMaxY, overlayStride);
   }

   @Override
   public String describe(ClientLevel level) {
      var engine = this.source.isOpen() ? this.source.engine() : VoxySource.peek(level);
      return "voxy engine: " + (engine == null ? "ABSENT for this dimension" : "present");
   }

   /** The engine, for the few places that still need Voxy directly (palette size, growth stats). */
   public VoxySource source() {
      return this.source;
   }
}
