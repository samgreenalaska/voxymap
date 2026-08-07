package dev.local.voxymap.util;

/** Nanosecond time budget for a single client tick. */
public final class Budget {
   private long deadline;
   private long spentThisTick;

   public void start(double millis) {
      this.deadline = System.nanoTime() + (long)(millis * 1_000_000.0);
      this.spentThisTick = 0L;
   }

   public boolean exhausted() {
      return System.nanoTime() >= this.deadline;
   }

   /** Remembers how much of the budget got used, for the status readout. */
   public void finish(double millis) {
      long remaining = this.deadline - System.nanoTime();
      long total = (long)(millis * 1_000_000.0);
      this.spentThisTick = Math.max(0L, total - Math.max(0L, remaining));
   }

   public double lastSpentMillis() {
      return this.spentThisTick / 1_000_000.0;
   }
}
