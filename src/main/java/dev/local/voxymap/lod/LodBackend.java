package dev.local.voxymap.lod;

import dev.local.voxymap.voxy.PaletteSnapshot;
import dev.local.voxymap.voxy.SectionIndex;
import java.util.function.BooleanSupplier;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import xaero.map.MapProcessor;

/**
 * The LOD database the map bridge reads, behind one interface so the sweep does not care which.
 *
 * <p>Two exist. Voxy is the original and reads its own storage directly. Distant Horizons is what
 * 0.8 added, because Voxy does not run on Windows-on-ARM at all — on those clients its classes are
 * not merely unused, they are absent, so every one of them has to sit behind this line.
 *
 * <p>Lifetime is one sweep: {@link #open} takes whatever reference the backend needs, and the
 * sweep worker calls {@link #releaseIfLevelGone} between units of work. That last part is not
 * ceremony — Voxy's shutdown spins the render thread until every engine reference is gone, so a
 * reference held past a disconnect deadlocks the game.
 */
public interface LodBackend {
   Backends kind();

   /** Whether this backend has a database for the level the client is in, without taking a hold. */
   boolean present(ClientLevel level);

   /** @return true if a usable handle was taken */
   boolean open(ClientLevel level);

   void close();

   /** Whether the handle is still for the level the client is actually in. */
   boolean stillValid();

   boolean levelGone();

   /**
    * Drops the handle the moment the client is no longer in the level it was taken for.
    *
    * @return true if it had to be released, meaning the caller must stop reading
    */
   boolean releaseIfLevelGone();

   /** Worker thread. @return null if the walk was aborted */
   SectionIndex enumerate(BooleanSupplier abort);

   /** Worker thread. @return null if the walk was aborted */
   SectionIndex.FingerprintScan scanFingerprints(BooleanSupplier abort);

   /** Whether a cached snapshot still covers everything this backend can name. */
   boolean paletteMatches(PaletteSnapshot snapshot);

   PaletteWork paletteWork(MapProcessor mp, Level level);

   TileScanner scanner(SectionIndex index, PaletteSnapshot palette, int worldMinY, int worldMaxY, int overlayStride);

   /** One line for {@code /voxymap status}, naming the backend and what it can see. */
   String describe(ClientLevel level);
}
