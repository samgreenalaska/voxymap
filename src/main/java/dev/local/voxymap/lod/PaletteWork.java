package dev.local.voxymap.lod;

import dev.local.voxymap.voxy.PaletteSnapshot;

/**
 * Building a block classification table, a slice at a time on the main thread.
 *
 * <p>It has to be the main thread and it has to be sliced: Xaero's predicates read block models,
 * and running a few thousand of them in one tick is a visible stall. Both backends build the same
 * {@link PaletteSnapshot} in the end; they differ only in what they enumerate to get there.
 */
public interface PaletteWork {
   int total();

   int done();

   /** @return true once the whole palette has been classified */
   boolean advance(long deadlineNanos);

   PaletteSnapshot finish();
}
