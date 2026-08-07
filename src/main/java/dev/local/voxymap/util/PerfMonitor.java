package dev.local.voxymap.util;

import java.util.Arrays;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

/**
 * Frame-level timing, so the cost of the 2D map work can be compared against the 3D world render
 * it is competing with.
 *
 * <p>The headline number is the slow tail, not the average: a sweep that drops the mean framerate
 * by two is fine, one that adds a 40 ms hitch every second is not, and both look identical in an
 * average. So everything here is reported as percentiles.
 *
 * <p>Samples come from {@code LevelRenderEvents.START_MAIN} / {@code END_MAIN}, which fire once
 * per rendered frame. Sampling from the client tick instead would only see 20 of every ~100 frames
 * and would be biased, because tick work and frame work are correlated.
 */
public final class PerfMonitor {
   /** ~40 s of history at 100 fps. Enough for a stable P98 without unbounded memory. */
   private static final int CAPACITY = 4096;

   private final long[] frameNanos = new long[CAPACITY];
   private final long[] worldRenderNanos = new long[CAPACITY];
   private final long[] applyNanos = new long[CAPACITY];

   /** Window for the load figures. Matches the progress log interval so a line covers one window. */
   private static final long LOAD_WINDOW_NANOS = 30_000_000_000L;

   private int frameCount;
   private int worldRenderCount;
   private int applyCount;

   private long lastFrameStart;
   private long worldRenderStart;

   // Main-thread time spent, summed over a wall-clock window rather than sampled per event. This
   // is the only fair way to compare the two: they happen at completely different rates.
   private long windowStartNanos;
   private long applySumNanos;
   private long worldRenderSumNanos;
   private double lastApplyLoad = -1.0;
   private double lastWorldRenderLoad = -1.0;

   private final long[] scratch = new long[CAPACITY];

   public void install() {
      try {
         LevelRenderEvents.START_MAIN.register(context -> this.onFrameStart());
         LevelRenderEvents.END_MAIN.register(context -> this.onFrameEnd());
      } catch (Throwable t) {
         Log.warn("could not install the frame timing hooks; perf numbers will be unavailable", t);
      }
   }

   private void onFrameStart() {
      long now = System.nanoTime();
      this.rollLoadWindow(now);

      if (this.lastFrameStart != 0L) {
         long delta = now - this.lastFrameStart;
         // Drop absurd gaps: alt-tab, loading screens and GC pauses are not frame times.
         if (delta > 0L && delta < 2_000_000_000L) {
            this.frameNanos[this.frameCount++ % CAPACITY] = delta;
         }
      }

      this.lastFrameStart = now;
      this.worldRenderStart = now;
   }

   private void onFrameEnd() {
      if (this.worldRenderStart == 0L) {
         return;
      }

      long delta = System.nanoTime() - this.worldRenderStart;
      if (delta > 0L && delta < 2_000_000_000L) {
         this.worldRenderNanos[this.worldRenderCount++ % CAPACITY] = delta;
         this.worldRenderSumNanos += delta;
      }
   }

   /**
    * Closes the load window every 30 s and keeps the completed one to report.
    *
    * <p>Reporting the window in progress would read near zero right after a roll; reporting the
    * last completed one is always a full, comparable 30 s for both figures.
    */
   private void rollLoadWindow(long now) {
      if (this.windowStartNanos == 0L) {
         this.windowStartNanos = now;
         return;
      }

      long elapsed = now - this.windowStartNanos;
      if (elapsed < LOAD_WINDOW_NANOS) {
         return;
      }

      double seconds = elapsed / 1_000_000_000.0;
      this.lastApplyLoad = this.applySumNanos / 1_000_000.0 / seconds;
      this.lastWorldRenderLoad = this.worldRenderSumNanos / 1_000_000.0 / seconds;
      this.applySumNanos = 0L;
      this.worldRenderSumNanos = 0L;
      this.windowStartNanos = now;
   }

   /** Main-thread nanoseconds this mod spent inside a single client tick. */
   public void recordApply(long nanos) {
      if (nanos > 0L) {
         this.applyNanos[this.applyCount++ % CAPACITY] = nanos;
         this.applySumNanos += nanos;
      }
   }

   public void reset() {
      this.frameCount = 0;
      this.worldRenderCount = 0;
      this.applyCount = 0;
      this.lastFrameStart = 0L;
      this.worldRenderStart = 0L;
      this.windowStartNanos = 0L;
      this.applySumNanos = 0L;
      this.worldRenderSumNanos = 0L;
      this.lastApplyLoad = -1.0;
      this.lastWorldRenderLoad = -1.0;
   }

   public boolean hasData() {
      return this.frameCount > 32;
   }

   private Stats stats(long[] source, int count) {
      int n = Math.min(count, CAPACITY);
      if (n == 0) {
         return null;
      }

      System.arraycopy(source, 0, this.scratch, 0, n);
      long[] sorted = Arrays.copyOf(this.scratch, n);
      Arrays.sort(sorted);

      long sum = 0L;

      for (long v : sorted) {
         sum += v;
      }

      return new Stats(n, sum / n, sorted[n / 2], sorted[(int)(n * 0.98)], sorted[Math.min(n - 1, (int)(n * 0.999))], sorted[n - 1]);
   }

   public record Stats(int samples, long meanNanos, long p50Nanos, long p98Nanos, long p999Nanos, long maxNanos) {
      public double meanMs() {
         return this.meanNanos / 1_000_000.0;
      }

      public double p50Ms() {
         return this.p50Nanos / 1_000_000.0;
      }

      public double p98Ms() {
         return this.p98Nanos / 1_000_000.0;
      }

      public double p999Ms() {
         return this.p999Nanos / 1_000_000.0;
      }

      public double maxMs() {
         return this.maxNanos / 1_000_000.0;
      }

      public double meanFps() {
         return this.meanNanos == 0L ? 0.0 : 1_000_000_000.0 / this.meanNanos;
      }

      /** The framerate the worst 2 % of frames feel like -- the number stutter actually shows up in. */
      public double p98Fps() {
         return this.p98Nanos == 0L ? 0.0 : 1_000_000_000.0 / this.p98Nanos;
      }
   }

   public Stats frames() {
      return this.stats(this.frameNanos, this.frameCount);
   }

   public Stats worldRender() {
      return this.stats(this.worldRenderNanos, this.worldRenderCount);
   }

   public Stats apply() {
      return this.stats(this.applyNanos, this.applyCount);
   }

   /** One-line summary suitable for both the log and {@code /voxymap status}. */
   public String summary() {
      Stats f = this.frames();
      if (f == null) {
         return "perf: no frame samples yet";
      }

      Stats w = this.worldRender();
      Stats a = this.apply();

      StringBuilder sb = new StringBuilder();
      sb.append(
         String.format(
            "perf: fps mean=%.0f p98low=%.0f | frame p50=%.1fms p98=%.1fms p99.9=%.1fms max=%.1fms (n=%d)",
            f.meanFps(),
            f.p98Fps(),
            f.p50Ms(),
            f.p98Ms(),
            f.p999Ms(),
            f.maxMs(),
            f.samples()
         )
      );

      if (w != null) {
         sb.append(String.format(" | 3D world render p50=%.1fms p98=%.1fms", w.p50Ms(), w.p98Ms()));
      }

      if (a != null) {
         // Per busy tick, which is a different population from the per-frame numbers above --
         // apply runs at most 20 times a second and only when there is something to write, so
         // roughly one frame in six carries any of this at all. Useful for spotting a hitch,
         // meaningless to compare against a per-frame percentile.
         sb.append(
            String.format(
               " | voxymap per busy tick p50=%.2fms p98=%.2fms max=%.2fms (n=%d)", a.p50Ms(), a.p98Ms(), a.maxMs(), a.samples()
            )
         );
      }

      if (this.lastApplyLoad >= 0.0 && this.lastWorldRenderLoad >= 0.0) {
         // The bar the user set: the 2D map work must not cost more than drawing the 3D world.
         //
         // Comparing percentiles was the wrong test and read OVER BUDGET when the mod was using
         // an eighth of the world render's main-thread time. The two are sampled at different
         // rates -- once per busy tick against once per frame -- over different ring-buffer
         // windows, so voxymap's p98 was effectively being read at about the p99.7 of frames and
         // held against the world render's p98. Main-thread milliseconds per wall-clock second,
         // over the same window, is the comparison that means what the bar says.
         sb.append(
            String.format(" | main-thread load: voxymap %.1f ms/s vs 3D world render %.1f ms/s", this.lastApplyLoad, this.lastWorldRenderLoad)
         );

         if (this.lastApplyLoad > this.lastWorldRenderLoad) {
            sb.append(" << OVER BUDGET");
         }
      }

      return sb.toString();
   }
}
