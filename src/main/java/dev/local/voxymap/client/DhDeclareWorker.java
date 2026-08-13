package dev.local.voxymap.client;

import com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataCache;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import dev.local.voxymap.dh.DhBridge;
import dev.local.voxymap.util.Log;
import dev.local.voxymap.voxy.SectionIndex;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.nio.file.Path;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * What a Distant Horizons client can honestly claim to hold, off the main thread.
 *
 * <p>The Voxy version of this ({@link LodDeclareWorker}) enumerates Voxy's storage and keeps only
 * the store entries the database confirms. DH cannot be enumerated — its api answers "what is at
 * this chunk" and nothing wider — so confirmation is done by sampling instead: read the chunks a
 * column covers and see whether DH has anything there.
 *
 * <h2>Sampling, and which way it is allowed to be wrong</h2>
 *
 * <p>Reading every column would be tens of thousands of database hits at join. So this reads as
 * many as it can inside a budget, in a shuffled order so the sample is spread across the map rather
 * than concentrated in one corner, and <b>declares only what it verified</b>. Columns it did not
 * get to are dropped from the declaration.
 *
 * <p>That is the same bias every other part of this protocol has: dropping a column the client
 * really holds costs one redundant resend, while declaring a column it does not hold loses that
 * ground until somebody deletes a file by hand (§7.16). A slow disk makes the client ask for more,
 * never for less.
 *
 * <p>The wholesale failure — DH's database deleted or replaced between sessions — shows up
 * immediately: the sample comes back empty, nothing is declared, and the server re-sends
 * everything. That is the case the guard exists for.
 */
public final class DhDeclareWorker {
   /**
    * How long to spend confirming.
    *
    * <p>Ten seconds of a background thread against a join that is already waiting on chunk loading.
    * The client declares whatever it managed in that time and the server fills in the rest.
    */
   private static final long BUDGET_NANOS = 10L * 1_000_000_000L;

   private final Thread thread;

   private volatile LodDeclareWorker.Result result;
   private volatile Throwable error;
   private volatile boolean done;

   public DhDeclareWorker(ClientLevel level, IDhApiLevelWrapper dhLevel, Path folder, String worldId) {
      this.thread = new Thread(() -> this.run(level, dhLevel, folder, worldId), "voxymap-lod-declare-dh");
      this.thread.setDaemon(true);
      this.thread.setPriority(Thread.NORM_PRIORITY - 2);
      this.thread.start();
   }

   public boolean done() {
      return this.done;
   }

   public LodDeclareWorker.Result result() {
      return this.result;
   }

   public Throwable error() {
      return this.error;
   }

   private void run(ClientLevel level, IDhApiLevelWrapper dhLevel, Path folder, String worldId) {
      long startedAt = System.nanoTime();

      try {
         LodHaveStore store = LodHaveStore.load(folder, worldId);

         if (store.size() == 0) {
            this.publish(new LodDeclareWorker.Result(store, new Long2LongOpenHashMap(), 0, 0, millisSince(startedAt)));
            return;
         }

         int before = store.size();
         IDhApiTerrainDataCache cache = DhBridge.newCache();
         long deadline = startedAt + BUDGET_NANOS;

         LongArrayList keys = new LongArrayList(store.columns().keySet());
         // Shuffled with a fixed seed: spread across the map rather than along one axis, and the
         // same order every session so a client on a slow disk keeps confirming the same columns
         // instead of a different tenth each time.
         java.util.Collections.shuffle(keys, new java.util.Random(0x766F786DL));

         it.unimi.dsi.fastutil.longs.LongOpenHashSet confirmed = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

         for (int i = 0; i < keys.size(); i++) {
            if (net.minecraft.client.Minecraft.getInstance().level != level) {
               // Left the world mid-walk. Nothing to declare and nobody to declare it to.
               this.publish(new LodDeclareWorker.Result(store, new Long2LongOpenHashMap(), 0, before, millisSince(startedAt)));
               return;
            }

            if ((i & 0x3F) == 0 && System.nanoTime() >= deadline) {
               break;
            }

            long key = keys.getLong(i);

            if (this.confirm(dhLevel, key, cache)) {
               confirmed.add(key);
            }
         }

         store.columns().keySet().retainAll(confirmed);
         store.retainHeightsForColumns();

         int dropped = before - store.size();
         Long2LongOpenHashMap regions = LodDeclareWorker.rollUp(store.columns());
         this.publish(new LodDeclareWorker.Result(store, regions, store.size(), dropped, millisSince(startedAt)));
      } catch (Throwable t) {
         this.error = t;
         this.done = true;
         Log.warn("could not work out what LOD this client already holds; the server will re-send everything", t);
      }
   }

   /**
    * Whether DH has anything at all in the column.
    *
    * <p>One chunk of the four, not all four: this is a test for "the database still has this
    * ground", not an audit of it. A column DH kept only part of will be re-sent as a whole the next
    * time its content hash disagrees with the server's, which is the mechanism that repairs
    * everything else too.
    */
   private boolean confirm(IDhApiLevelWrapper dhLevel, long columnKey, IDhApiTerrainDataCache cache) {
      int chunkX = SectionIndex.columnX(columnKey) << 1;
      int chunkZ = SectionIndex.columnZ(columnKey) << 1;

      try {
         var data = DhBridge.chunkData(dhLevel, chunkX, chunkZ, cache);
         return data != null && data.length > 0;
      } catch (Throwable t) {
         return false;
      }
   }

   private void publish(LodDeclareWorker.Result r) {
      this.result = r;
      this.done = true;
   }

   private static long millisSince(long startNanos) {
      return (System.nanoTime() - startNanos) / 1_000_000L;
   }
}
