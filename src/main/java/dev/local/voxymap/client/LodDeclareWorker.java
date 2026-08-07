package dev.local.voxymap.client;

import dev.local.voxymap.net.LodHash;
import dev.local.voxymap.util.Log;
import dev.local.voxymap.voxy.SectionIndex;
import dev.local.voxymap.voxy.VoxySource;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Works out what this client can honestly claim to hold, off the main thread.
 *
 * <p>Three steps, in this order because each one narrows the last: read the persisted store, ask
 * Voxy's storage which columns are actually on disk, and keep only the entries both agree on. Then
 * roll the survivors up per region, which is what goes on the wire -- a few hundred numbers instead
 * of tens of thousands.
 *
 * <h2>Why this cannot run on the client thread</h2>
 *
 * <p>The index walk is a range scan over Voxy's whole LOD-0 key space: a second or two on a mature
 * database, which is a second or two of frozen game if it happens on the tick.
 *
 * <h2>Why it cannot hold Voxy's engine carelessly</h2>
 *
 * <p>Disconnecting runs {@code VoxyInstance.shutdown()} on the render thread and spins there until
 * every engine reference is gone. A reference held by a thread the render thread is waiting on is a
 * deadlock and a watchdog kill -- see {@link VoxySource}, which is why this borrows that class
 * rather than calling {@code ofEngineNullable} itself, and why the abort predicate handed to
 * {@link SectionIndex#enumerate} is "the player has left".
 */
public final class LodDeclareWorker {
   /** What the client thread needs once the walk is done. */
   public record Result(LodHaveStore store, Long2LongOpenHashMap regions, int declared, int dropped, long millis) {
   }

   private final VoxySource voxy = new VoxySource();
   private final Thread thread;

   private volatile Result result;
   private volatile Throwable error;
   private volatile boolean done;

   public LodDeclareWorker(ClientLevel level, java.nio.file.Path voxySaveFolder, String worldId) {
      this.thread = new Thread(() -> this.run(level, voxySaveFolder, worldId), "voxymap-lod-declare");
      this.thread.setDaemon(true);
      this.thread.setPriority(Thread.NORM_PRIORITY - 2);
      this.thread.start();
   }

   public boolean done() {
      return this.done;
   }

   public Result result() {
      return this.result;
   }

   public Throwable error() {
      return this.error;
   }

   private void run(ClientLevel level, java.nio.file.Path voxySaveFolder, String worldId) {
      long startedAt = System.nanoTime();

      try {
         LodHaveStore store = LodHaveStore.load(voxySaveFolder, worldId);

         if (!this.voxy.open(level)) {
            // No engine for this world yet. Declaring the store unverified is the one thing this
            // must never do, so declare nothing and let the server send everything.
            this.publish(new Result(store, new Long2LongOpenHashMap(), 0, store.size(), millisSince(startedAt)));
            return;
         }

         SectionIndex index;

         try {
            WorldEngine engine = this.voxy.engine();
            index = SectionIndex.enumerate(engine, this.voxy::levelGone);
         } finally {
            this.voxy.close();
         }

         if (index == null) {
            // Aborted: the player left mid-walk. Nothing to declare and nobody to declare it to.
            this.publish(new Result(store, new Long2LongOpenHashMap(), 0, store.size(), millisSince(startedAt)));
            return;
         }

         int dropped = store.retainConfirmed(index);
         Long2LongOpenHashMap regions = rollUp(store.columns());
         this.publish(new Result(store, regions, store.size(), dropped, millisSince(startedAt)));
      } catch (Throwable t) {
         this.error = t;
         this.done = true;
         Log.warn("could not work out what LOD this client already holds; the server will re-send everything", t);
      } finally {
         // Belt and braces: every path above releases, and none of them may leave Voxy's shutdown
         // spinning if one day one of them stops.
         this.voxy.close();
      }
   }

   private void publish(Result r) {
      this.result = r;
      this.done = true;
   }

   private static long millisSince(long startNanos) {
      return (System.nanoTime() - startNanos) / 1_000_000L;
   }

   /**
    * One number per Xaero-sized region, XOR-folded from the columns inside it.
    *
    * <p>The same fold the server runs over its own columns, so equal numbers mean the two ends agree
    * about every column in the region and the server can skip all 256 of them without asking about
    * any. Unequal means somewhere in there they differ, and only then is the per-column detail
    * worth a round trip.
    */
   public static Long2LongOpenHashMap rollUp(Long2LongOpenHashMap columns) {
      Long2LongOpenHashMap out = new Long2LongOpenHashMap(512);
      out.defaultReturnValue(0L);

      for (Long2LongOpenHashMap.Entry e : columns.long2LongEntrySet()) {
         long col = e.getLongKey();
         long region = SectionIndex.regionKey(SectionIndex.columnX(col) >> 4, SectionIndex.columnZ(col) >> 4);
         out.put(region, LodHash.region(out.get(region), col, e.getLongValue()));
      }

      // Zero is "I have nothing here", and a region whose columns happened to fold to zero would be
      // read as exactly that. One in 2^64, and one line to rule out -- but it has to be ruled out
      // the same way on the server, or every region would disagree by construction.
      for (Long2LongOpenHashMap.Entry e : out.long2LongEntrySet()) {
         e.setValue(LodHash.nonZero(e.getLongValue()));
      }

      return out;
   }
}
