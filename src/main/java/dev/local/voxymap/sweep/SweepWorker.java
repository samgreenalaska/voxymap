package dev.local.voxymap.sweep;

import dev.local.voxymap.util.Log;
import dev.local.voxymap.voxy.ColumnData;
import dev.local.voxymap.voxy.ColumnScanner;
import dev.local.voxymap.voxy.SectionIndex;
import dev.local.voxymap.voxy.VoxySource;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import me.cortex.voxy.common.world.WorldEngine;

/**
 * The one extra thread this mod adds. It owns every read of Voxy's storage.
 *
 * <p>The bounded result queue is the throttle: when the main thread falls behind on applying
 * tile chunks, the worker blocks on the offer and stops reading Voxy, which in turn stops the
 * whole sweep from running ahead of what the client can absorb.
 *
 * <p>It also owns letting go of Voxy on disconnect. Voxy's shutdown spins the render thread until
 * every engine reference is gone, so the release cannot come from the client tick -- see
 * {@link VoxySource}. Checking between units of work is the natural place: this thread is the only
 * one that reads Voxy's storage, so once it has stopped, nothing else can.
 */
public final class SweepWorker {
   private static final int REQUEST_CAPACITY = 4;
   private static final int RESULT_CAPACITY = 8;

   public record Request(int generation, int tileChunkX, int tileChunkZ) {
   }

   /** Always produced, even when a tile chunk turned out to be empty, so in-flight counting works. */
   public record Result(int generation, ColumnData data) {
   }

   private final VoxySource voxy;
   private final Thread thread;

   private final BlockingQueue<Request> requests = new ArrayBlockingQueue<>(REQUEST_CAPACITY);
   private final BlockingQueue<Result> results = new ArrayBlockingQueue<>(RESULT_CAPACITY);

   private volatile boolean running = true;
   private volatile int generation;
   private volatile ColumnScanner scanner;

   private volatile boolean enumerateRequested;
   private volatile boolean fingerprintsOnly;
   private volatile SectionIndex index;
   private volatile SectionIndex.FingerprintScan scan;
   private volatile Throwable error;

   public SweepWorker(VoxySource voxy) {
      this.voxy = voxy;
      this.thread = new Thread(this::loop, "voxymap-sweep");
      this.thread.setDaemon(true);
      this.thread.setPriority(Thread.NORM_PRIORITY - 2);
      this.thread.start();
   }

   /**
    * @param fingerprintsOnly ask only which regions changed, which costs a fraction of building
    *     the column map. The map is only needed for regions that are going to be scanned.
    */
   public void requestEnumerate(boolean fingerprintsOnly) {
      this.index = null;
      this.scan = null;
      this.error = null;
      this.fingerprintsOnly = fingerprintsOnly;
      this.enumerateRequested = true;
   }

   public SectionIndex index() {
      return this.index;
   }

   public SectionIndex.FingerprintScan scan() {
      return this.scan;
   }

   public Throwable error() {
      return this.error;
   }

   public boolean enumerating() {
      return this.enumerateRequested;
   }

   public void setScanner(ColumnScanner scanner) {
      this.scanner = scanner;
   }

   public int generation() {
      return this.generation;
   }

   /** Invalidates every request and result currently in flight. */
   public void bumpGeneration() {
      this.generation++;
      this.requests.clear();
      this.results.clear();
   }

   /** @return false if the request queue is full and the caller should try again next tick. */
   public boolean submit(int tileChunkX, int tileChunkZ) {
      return this.requests.offer(new Request(this.generation, tileChunkX, tileChunkZ));
   }

   public Result poll() {
      Result r = this.results.poll();
      return r != null && r.generation() == this.generation ? r : null;
   }

   public int queuedResults() {
      return this.results.size();
   }

   public int resultCapacity() {
      return RESULT_CAPACITY;
   }

   public void shutdown() {
      this.running = false;
      this.bumpGeneration();
      this.thread.interrupt();
   }

   private void loop() {
      while (this.running) {
         try {
            // Between units of work, so no WorldSection is held and nothing below will touch
            // Voxy's storage again once the reference is gone.
            if (this.voxy.releaseIfLevelGone()) {
               this.running = false;
               this.enumerateRequested = false;
               return;
            }

            WorldEngine engine = this.voxy.engine();
            if (engine == null) {
               this.enumerateRequested = false;
               Thread.sleep(50L);
               continue;
            }

            if (this.enumerateRequested) {
               try {
                  java.util.function.BooleanSupplier abort = () -> !this.running || this.voxy.levelGone();

                  if (this.fingerprintsOnly) {
                     this.scan = SectionIndex.scanFingerprints(engine, abort);
                  } else {
                     this.index = SectionIndex.enumerate(engine, abort);
                     this.scan = SectionIndex.scanFingerprints(engine, abort);
                  }
               } catch (Throwable t) {
                  this.error = t;
                  Log.warn("enumerating Voxy's LOD-0 sections failed", t);
               } finally {
                  this.enumerateRequested = false;
               }

               continue;
            }

            Request request = this.requests.poll(50L, TimeUnit.MILLISECONDS);
            if (request == null) {
               continue;
            }

            ColumnScanner sc = this.scanner;
            if (sc == null || request.generation() != this.generation) {
               continue;
            }

            ColumnData data;

            try {
               data = sc.scanTileChunk(request.generation(), request.tileChunkX(), request.tileChunkZ());
            } catch (Throwable t) {
               Log.warn("scanning tile chunk " + request.tileChunkX() + "," + request.tileChunkZ() + " failed", t);
               data = null;
            }

            Result result = new Result(request.generation(), data);

            // Block until the main thread has room. This is the backpressure -- and the one place
            // this thread can be stuck for a long time, because a main thread that has stopped
            // ticking never drains. It stops ticking during a disconnect, which is exactly when
            // Voxy is waiting on the reference this thread holds, so the level check has to happen
            // in here too rather than only at the top of the loop.
            while (this.running && result.generation() == this.generation) {
               if (this.results.offer(result, 50L, TimeUnit.MILLISECONDS)) {
                  break;
               }

               if (this.voxy.releaseIfLevelGone()) {
                  this.running = false;
                  return;
               }
            }
         } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
         } catch (Throwable t) {
            Log.warn("sweep worker hit an unexpected error", t);
         }
      }
   }
}
