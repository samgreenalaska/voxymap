package dev.local.voxymap.server;

import dev.local.voxymap.VoxyMapConfig;
import dev.local.voxymap.util.Log;
import dev.local.voxymap.util.RadialOffsets;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

/**
 * Passive chunk generation around players, so the world exists before anyone walks to it.
 *
 * <h2>Why this is here</h2>
 *
 * <p>VoxyServer streams LOD for terrain the server already has; it cannot create terrain that has
 * never been generated. Something has to push the world outward past view distance or the map only
 * ever grows where a player has physically been. That was Voxy World Gen's job, and this replaces
 * it -- written against the vanilla ticket API rather than derived from that mod, whose licence
 * does not permit reuse.
 *
 * <h2>How it works</h2>
 *
 * <p>Each pass takes the nearest chunks to a player that have not been asked for yet and calls
 * {@code addTicketAndLoadWithRadius} on each. The chunk is generated or read, saved by the normal
 * unload path once the ticket expires, and nothing is left pinned. That is the whole mechanism; no
 * mixins, no reflection, no access wideners.
 *
 * <p><b>The ticket type is built here rather than borrowed.</b> {@code addTicketAndLoadWithRadius}
 * rejects any type where {@code canExpireIfUnloaded()} is true -- "can expire before it loads,
 * cannot fetch asynchronously" -- which rules out {@code TicketType.UNKNOWN} (flags 18, including
 * {@code FLAG_CAN_EXPIRE_IF_UNLOADED}). Of the registered types that pass, all either never expire
 * ({@code PLAYER_LOADING}, {@code FORCED}), persist to disk ({@code FORCED}, {@code PORTAL}), or
 * simulate ({@code ENDER_PEARL}) -- and simulation across a pregenerated area means ticking mobs
 * and redstone over ground nobody is standing in.
 *
 * <p>So {@link #PREGEN} is {@code FLAG_LOADING} only, with a finite timeout. It is deliberately not
 * added to {@code BuiltInRegistries.TICKET_TYPE}: that registry is only consulted when a ticket is
 * serialized, and {@code TicketStorage.packTickets} filters on {@code persist()} first, so a
 * non-persisting type is never named and never written.
 *
 * <p>Ordering is nearest-first by true distance, using the same offset table as the radial LOD scan
 * (§7.20), so the generated frontier is a disc rather than a square.
 *
 * <h2>What is deliberately absent</h2>
 *
 * <p><b>There is no completed-chunk file on disk.</b> Voxy World Gen kept one, and §7.16 is the
 * story of what that cost: after a client wipe the server still believed it had already sent
 * everything, so the map would not refill near spawn and the only remedy was deleting the file over
 * SSH. The set here lives in memory and dies with the server.
 *
 * <p>The cost of that choice is one re-walk per restart, and it is smaller than it looks: a chunk
 * that already exists is read from disk rather than generated, and reading it is useful work
 * anyway, because VoxyServer voxelizes on chunk load. So a restart re-feeds the LOD store instead
 * of doing nothing. Correctness never depends on the set -- it is only there to stop us asking for
 * the same chunk twice in one session.
 */
public final class ChunkPregen {
   /**
    * How long the ticket keeps driving a chunk toward full status.
    *
    * <p>Was 200 (ten seconds), which is not enough on a slow server: the ticket expired mid-load,
    * nothing kept pushing the chunk, and the future returned by
    * {@code addTicketAndLoadWithRadius} was left uncompleted forever. Every expiry permanently
    * consumed one of the in-flight slots, so generation did not slow down -- it stopped dead once
    * enough had leaked. Raising {@code maxActiveGenerationTasks} only made it stop sooner.
    */
   private static final long TICKET_TIMEOUT_TICKS = 600L;

   /**
    * When an in-flight request is given up on, comfortably past the ticket's own lifetime.
    *
    * <p>The timeout above makes leaks rarer; this makes them survivable. A slot must never be
    * occupied indefinitely by a future that will never complete, whatever the reason.
    */
   private static final long ABANDON_AFTER_NANOS = (TICKET_TIMEOUT_TICKS / 20L + 15L) * 1_000_000_000L;

   /** A request and the point at which waiting for it stops being worthwhile. */
   private record Pending(CompletableFuture<?> future, long deadlineNanos) {
   }

   /**
    * Loads without simulating and never persists. See the class note for why none of the registered
    * types would do, and TICKET_TIMEOUT_TICKS for why the lifetime is what it is.
    */
   private static final TicketType PREGEN = new TicketType(TICKET_TIMEOUT_TICKS, TicketType.FLAG_LOADING);

   /** Chunks asked for this session, keyed by dimension. Memory only, deliberately (see above). */
   private final Map<String, LongOpenHashSet> requested = new HashMap<>();

   /** Where each player's outward walk has reached, so a pass resumes rather than restarts. */
   private final Map<String, Integer> cursor = new HashMap<>();

   /** The chunk each player's cursor was measured from, so movement can invalidate it. */
   private final Map<String, Long> cursorOrigin = new HashMap<>();

   /**
    * Fraction of wall time spent collecting garbage, past which generation stops.
    *
    * <p>The other two brakes watch the framerate and the server thread's tick time. Neither of them
    * can see the failure that actually happens: worldgen allocates hard, and once the heap is full
    * the collector runs continuously, so frames become pauses while the tick loop still looks
    * healthy. A singleplayer world sat at 8.4 ms median frame time with a 99.9th percentile of 330
    * ms and both existing gates reported everything was fine.
    *
    * <p>Twenty percent is deliberately a long way past normal. This is a guard rail against a
    * runaway, not a tuning knob, which is why it is not in the config: a player who could usefully
    * choose this number would not need it.
    */
   private static final double GC_PAUSE_FRACTION = 0.20;

   /**
    * Where the throttle starts giving ground, and where it starts taking it back.
    *
    * <p>Stopping outright at {@link #GC_PAUSE_FRACTION} was measured and does not work. By the time
    * a fifth of wall time is collection, G1 has already lost: profiling a 4 GB client showed three
    * {@code ConcurrentModeFailure}s in 90 seconds -- concurrent marking overrun by the allocation
    * rate, falling back to a stop-the-world full GC of 318, 352 and 388 ms -- while every other
    * pause in the same window was under 40 ms. Those three failures are the stutter; nothing else
    * comes close.
    *
    * <p>Marking loses because of the rate, not the size. Bounding the resident set with
    * {@link #RESIDENT_HEAP_FRACTION} cut chunks held from 43,835 to 5,257 and moved the worst pause
    * from 412 ms to 388 ms, which is nothing. What fills the heap is the allocation behind the
    * generating: worldgen accounted for roughly half of all allocation in that profile, and this
    * class is what asks for it.
    *
    * <p>So concurrency is steered rather than switched. Multiplicative decrease when collection
    * costs more than {@code GC_THROTTLE_HIGH}, additive increase below {@code GC_THROTTLE_LOW} --
    * the same shape as congestion control, and for the same reason: the only honest signal is that
    * the thing downstream is struggling, and the only useful response is to send less until it
    * stops.
    */
   private static final double GC_THROTTLE_HIGH = 0.08;

   private static final double GC_THROTTLE_LOW = 0.03;

   /** Long enough that one collection does not trip it, short enough to react within a few passes. */
   private static final long GC_SAMPLE_NANOS = 2_000_000_000L;

   /**
    * Queued chunk work belonging to somebody else that makes this back off.
    *
    * <p>Pregen requests land at the same ticket level as the chunks a player is walking into, so
    * the chunk system has no way to prefer theirs. Rather than trying to out-prioritise it, wait:
    * if there is other work outstanding, the player is loading something and this is the least
    * urgent thing on the machine.
    */
   private static final int PENDING_HEADROOM = 4;

   /**
    * Share of the heap this is allowed to leave sitting in loaded chunks.
    *
    * <p>The expensive thing about generating is not the generating. A pregen ticket keeps its chunk
    * loaded for {@link #TICKET_TIMEOUT_TICKS} and Minecraft unloads lazily after that, so at any
    * real request rate chunks arrive faster than they leave and the resident set grows without
    * anything in this class noticing. Measured on a 4 GB client: 83,822 chunks requested in a
    * session and 43,835 still resident, about 80 KB each, for 3.6 GB of live data in a 4 GB heap.
    *
    * <p>That is what makes the pauses long. Allocation rate only decides how often a collection
    * happens; the live set decides how long each one takes, and an old-generation collection has to
    * trace all of it. Every other brake here throttles the rate. This one bounds the set.
    *
    * <p>A fraction rather than a number because the whole point is to behave on a machine smaller
    * than the one it was written on: ten percent of a 2 GB heap is a different budget from ten
    * percent of an 8 GB one, and both are the right answer for that machine.
    */
   private static final double RESIDENT_HEAP_FRACTION = 0.10;

   /** Roughly what a generated overworld chunk costs resident, measured from a class histogram. */
   private static final int APPROX_CHUNK_BYTES = 80 * 1024;

   /** Never throttle below this, or a small heap could starve the player's own view distance. */
   private static final int MIN_RESIDENT_BUDGET = 512;

   private long failed;
   private long skippedGc;
   private long skippedBusy;
   private long skippedResident;

   /** Chunks resident at the last check, for the status line. */
   private int loaded;

   /** The radius the current cursors were measured against. See {@link #tickLevel}. */
   private int lastRadius = -1;

   private long lastGcMillis;
   private long lastGcSampleNanos;
   private double gcFraction;

   /** Concurrency the throttle currently allows, at most {@link #effectiveTasks}. Zero means unset. */
   private int throttle;

   private final List<Pending> inFlight = new ArrayList<>();
   private long abandoned;

   private final VoxyMapConfig config;
   private int tickCounter;
   private long generated;
   private long skippedTps;
   private long skippedFps;
   private long lastReportNanos;

   /** Where the last pass stopped. See {@link #report}. */
   private String gate = "not started";

   /** Periodic self-report, so "is it generating at all" never needs a command to answer. */
   private static final long REPORT_EVERY_NANOS = 30L * 1_000_000_000L;

   public ChunkPregen(VoxyMapConfig config) {
      this.config = config;
   }

   public long generatedCount() {
      return this.generated;
   }

   /**
    * Invalidates every cursor after a radius change.
    *
    * <p>A cursor is an index into the offset table for one radius; against a table built for a
    * different radius the same index is a different chunk, so resuming from it would silently skip
    * ground. Cheap to reset -- the requested set still suppresses repeats.
    */
   public void onRadiusChanged() {
      this.cursor.clear();
      this.cursorOrigin.clear();
   }

   public void tick(MinecraftServer server) {
      if (!this.config.generateChunks || this.config.generationRadiusChunks <= 0) {
         return;
      }

      if (++this.tickCounter < Math.max(1, this.config.generationIntervalTicks)) {
         return;
      }

      this.tickCounter = 0;

      long now = System.nanoTime();
      this.inFlight.removeIf(p -> {
         if (p.future().isDone()) {
            return true;
         }

         if (now - p.deadlineNanos() >= 0L) {
            // The chunk system is never going to answer for this one. Free the slot rather than
            // letting it wedge the generator; the chunk stays in the requested set because it may
            // well have generated anyway, and a retry loop on a chunk that always fails is worse.
            this.abandoned++;
            return true;
         }

         return false;
      });

      // Sampled before the in-flight gate rather than after it. Behind that gate this never ran on
      // a busy generator, so the throttle stopped being steered at exactly the moment it mattered
      // and whatever value it happened to hold became permanent.
      boolean gcStop = this.underGcPressure();

      if (this.inFlight.size() >= this.allowedTasks()) {
         this.report("waiting on chunk loads");
         return;
      }

      if (gcStop) {
         this.skippedGc++;
         this.report(String.format("paused: %.0f%% of wall time in GC", this.gcFraction * 100.0));
         return;
      }

      // On an integrated server the "server" is the player's own machine, so the framerate limiter
      // that protects the map sweep has to protect generation too -- otherwise the two settings
      // disagree about how much of the frame budget this mod is allowed.
      if (belowFpsLimit(this.config)) {
         this.skippedFps++;
         this.report("paused: framerate under " + this.config.pauseWhenFpsBelow);
         return;
      }

      // Back off when the server is already struggling. Generation is the least urgent thing the
      // server does, and a pregenerator that costs tick time is worse than no pregenerator.
      double mspt = server.getAverageTickTimeNanos() / 1_000_000.0;
      double minMspt = 1000.0 / Math.max(1.0, this.config.generationMinTps);

      if (mspt > minMspt) {
         this.skippedTps++;
         this.report(String.format("paused: server at %.1f ms/tick, limit %.1f", mspt, minMspt));
         return;
      }

      if (server.getPlayerList().getPlayers().isEmpty()) {
         this.report("nobody online to generate around");
         return;
      }

      this.report("generating");

      for (ServerLevel level : server.getAllLevels()) {
         if (this.inFlight.size() >= this.allowedTasks()) {
            return;
         }

         this.tickLevel(level);
      }
   }

   /**
    * The periodic self-report, and the reason the pass ended where it did.
    *
    * <p>This used to sit *after* the three gates above, so it only ever printed on a pass that got
    * through all of them. A generator wedged behind a full in-flight list or the TPS backoff
    * therefore said nothing at all -- the log went quiet at precisely the moment someone would be
    * reading it to find out why nothing was being generated. Every path reports now, and carries
    * which gate it stopped at.
    */
   private void report(String gate) {
      this.gate = gate;
      long now = System.nanoTime();

      if (now - this.lastReportNanos >= REPORT_EVERY_NANOS) {
         this.lastReportNanos = now;
         Log.info(this.status());
      }
   }

   private void tickLevel(ServerLevel level) {
      List<ServerPlayer> players = level.players();

      if (players.isEmpty()) {
         return;
      }

      // Yield to whatever else wants chunks. Our own outstanding requests are already counted in
      // the chunk system's queue, so they come off first -- otherwise this would simply restate
      // the in-flight cap and never notice the player at all. See PENDING_HEADROOM.
      int otherPending = level.getChunkSource().getPendingTasksCount() - this.inFlight.size();

      if (otherPending > PENDING_HEADROOM) {
         this.skippedBusy++;
         this.report("waiting: " + otherPending + " chunk tasks queued by something else");
         return;
      }

      // Stop before the heap fills rather than after. Backing off here lets the tickets already
      // out expire and the chunks behind them unload, which is the only way the resident set ever
      // comes down -- nothing in this class can evict a chunk directly. See RESIDENT_HEAP_FRACTION.
      this.loaded = level.getChunkSource().getLoadedChunksCount();
      int residentBudget = residentBudget();

      if (this.loaded > residentBudget) {
         this.skippedResident++;
         this.report("waiting: " + this.loaded + " chunks resident, budget " + residentBudget);
         return;
      }

      String dim = level.dimension().identifier().toString();
      LongOpenHashSet done = this.requested.computeIfAbsent(dim, k -> new LongOpenHashSet());
      int radius = effectiveRadius(this.config);

      // Notice the radius moving under us rather than being told about it. It can change from the
      // video settings page, from /voxymap reload, from /voxymapserver pregen radius, or because
      // Voxy's own render distance changed -- and a cursor left over from a different offset table
      // points at a different chunk, so the walk would skip ground it had never examined.
      if (radius != this.lastRadius) {
         this.lastRadius = radius;
         this.onRadiusChanged();
      }

      int[] offsets = RadialOffsets.forRadius(radius);

      int budget = this.allowedTasks();

      for (ServerPlayer player : players) {
         BlockPos pos = player.blockPosition();
         int centreX = pos.getX() >> 4;
         int centreZ = pos.getZ() >> 4;
         String key = dim + "/" + player.getUUID();
         long origin = ChunkPos.pack(centreX, centreZ);

         // The cursor is an index into offsets measured from a centre. Once the player moves, the
         // same index means a different chunk and everything nearer than it would be skipped until
         // the walk wrapped around -- so nearest-first quietly stopped being true. Restart from the
         // middle instead; already-requested chunks are skipped by the set, so it is cheap.
         if (!Long.valueOf(origin).equals(this.cursorOrigin.get(key))) {
            this.cursorOrigin.put(key, origin);
            this.cursor.put(key, 0);
         }

         // Fill the whole in-flight budget rather than asking for a single chunk and waiting for
         // the next pass. One per pass was one chunk per second, which is indistinguishable from
         // doing nothing at a radius of 128.
         while (this.inFlight.size() < budget) {
            int from = this.cursor.getOrDefault(key, 0);
            int index = this.nextUnrequested(offsets, done, centreX, centreZ, from);

            if (index < 0) {
               // Everything within the radius has been asked for.
               this.cursor.put(key, offsets.length);
               break;
            }

            int packed = offsets[index];
            int cx = centreX + RadialOffsets.dx(packed);
            int cz = centreZ + RadialOffsets.dz(packed);

            this.cursor.put(key, index + 1);

            // Only mark it done if the request was actually accepted, or a transient failure would
            // retire the chunk permanently.
            if (this.request(level, cx, cz)) {
               done.add(ChunkPos.pack(cx, cz));
            } else {
               return;
            }
         }

         if (this.inFlight.size() >= budget) {
            return;
         }
      }
   }

   /** @return the index into {@code offsets} of the nearest not-yet-requested chunk, or -1 */
   private int nextUnrequested(int[] offsets, LongOpenHashSet done, int centreX, int centreZ, int from) {
      for (int i = from; i < offsets.length; i++) {
         int packed = offsets[i];
         int cx = centreX + RadialOffsets.dx(packed);
         int cz = centreZ + RadialOffsets.dz(packed);

         if (!done.contains(ChunkPos.pack(cx, cz))) {
            return i;
         }
      }

      return -1;
   }

   /** @return true if the chunk system accepted the request */
   private boolean request(ServerLevel level, int cx, int cz) {
      try {
         ServerChunkCache chunks = level.getChunkSource();

         // Radius 0: this chunk only.
         CompletableFuture<?> future = chunks.addTicketAndLoadWithRadius(PREGEN, new ChunkPos(cx, cz), 0);

         this.inFlight.add(new Pending(future, System.nanoTime() + ABANDON_AFTER_NANOS));
         this.generated++;
         return true;
      } catch (Throwable t) {
         // Log the first few and then go quiet. A systematic failure produced 756 identical stack
         // traces in one session before this was capped.
         if (this.failed++ < 3) {
            Log.warn("could not request generation of chunk " + cx + "," + cz, t);
         }

         return false;
      }
   }

   /**
    * Concurrent chunk loads, scaled by the same Map filling speed preset the sweep uses.
    *
    * <p>The two were independent, which meant choosing Background for a weak machine still let
    * generation run flat out -- and generation is the more expensive half. Scaling both from one
    * choice is the whole point of the preset (SS7.11).
    */
   static int effectiveTasks(VoxyMapConfig config) {
      int base = Math.max(1, config.maxActiveGenerationTasks);

      return switch (config.fillSpeed()) {
         case BACKGROUND -> Math.max(1, base / 4);
         case BALANCED -> base;
         case FAST -> Math.min(64, base * 2);
      };
   }

   /**
    * How many loaded chunks this will tolerate before it stops asking for more.
    *
    * <p>Derived from the heap the game was actually given, so the same jar is not reckless on a
    * 2 GB machine and needlessly timid on a 12 GB one. See {@link #RESIDENT_HEAP_FRACTION}.
    */
   static int residentBudget() {
      long allowed = (long) (Runtime.getRuntime().maxMemory() * RESIDENT_HEAP_FRACTION);

      return (int) Math.max(MIN_RESIDENT_BUDGET, Math.min(1 << 20, allowed / APPROX_CHUNK_BYTES));
   }

   /**
    * Whether the JVM is spending too much of its time collecting.
    *
    * <p>Sampled rather than computed per call: {@code getCollectionTime} is cumulative, so it only
    * means anything as a difference over a known interval. Between samples the last reading stands,
    * which is what makes this cheap enough to sit in the tick path.
    *
    * <p>Some collectors sum the time across their own threads, so the fraction can exceed 1.0 on a
    * parallel collector. That only makes this trip sooner under exactly the conditions it exists
    * for, so it is left alone rather than normalised against a core count that would be wrong in
    * its own way.
    */
   private boolean underGcPressure() {
      long now = System.nanoTime();
      long collected = 0L;

      for (java.lang.management.GarbageCollectorMXBean bean : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
         long t = bean.getCollectionTime();

         if (t > 0L) {
            collected += t;
         }
      }

      if (this.lastGcSampleNanos == 0L) {
         this.lastGcSampleNanos = now;
         this.lastGcMillis = collected;
         return false;
      }

      long elapsed = now - this.lastGcSampleNanos;

      if (elapsed >= GC_SAMPLE_NANOS) {
         this.gcFraction = (collected - this.lastGcMillis) / (elapsed / 1_000_000.0);
         this.lastGcSampleNanos = now;
         this.lastGcMillis = collected;
         this.steerThrottle();
      }

      return this.gcFraction > GC_PAUSE_FRACTION;
   }

   /**
    * Moves the concurrency cap toward whatever the collector can currently keep up with.
    *
    * <p>Halve on trouble, add one on calm. Recovery is deliberately much slower than retreat: the
    * cost of being one task too slow is a slightly later map, and the cost of being too fast is a
    * full GC the player sees. See {@link #GC_THROTTLE_HIGH}.
    */
   private void steerThrottle() {
      int ceiling = effectiveTasks(this.config);

      if (this.throttle <= 0) {
         this.throttle = ceiling;
      }

      if (this.gcFraction > GC_THROTTLE_HIGH) {
         this.throttle = Math.max(1, Math.min(this.throttle, ceiling) / 2);
      } else if (this.gcFraction < GC_THROTTLE_LOW && this.throttle < ceiling) {
         this.throttle++;
      }

      this.throttle = Math.max(1, Math.min(this.throttle, ceiling));
   }

   /** The in-flight cap actually in force: the configured one as narrowed by {@link #steerThrottle}. */
   private int allowedTasks() {
      int ceiling = effectiveTasks(this.config);

      return this.throttle <= 0 ? ceiling : Math.max(1, Math.min(this.throttle, ceiling));
   }

   /**
    * Whether the client's framerate is under {@code pauseWhenFpsBelow}.
    *
    * <p>Read reflectively: this class runs on a dedicated server too, where {@code Minecraft} does
    * not exist and touching it would fail at class load.
    */
   static boolean belowFpsLimit(VoxyMapConfig config) {
      if (config.pauseWhenFpsBelow <= 0) {
         return false;
      }

      try {
         Class<?> mc = Class.forName("net.minecraft.client.Minecraft");
         Object instance = mc.getMethod("getInstance").invoke(null);

         if (instance == null) {
            return false;
         }

         int fps = (int) mc.getMethod("getFps").invoke(instance);
         return fps > 0 && fps < config.pauseWhenFpsBelow;
      } catch (Throwable t) {
         return false;
      }
   }

   /**
    * The radius to generate to, in chunks: the Chunkgen distance setting, and nothing else.
    *
    * <p>This used to derive the radius from Voxy's {@code sectionRenderDistance} whenever
    * {@code generationMatchVoxyRenderDistance} was set, on the reasoning that generating less than
    * Voxy can draw leaves the map empty at the edges. The reasoning was fine and the arithmetic was
    * a disaster: Voxy's default render distance is 500 of its sections, so the derived radius came
    * back as 500 chunks -- a disc of 785,349 -- on a config whose own radius said 128. Nobody chose
    * that number, nobody could see it, and the only place it appeared was a log line.
    *
    * <p>So the derivation is gone rather than bounded. One setting, in chunks, that says what it
    * does. If a player wants the map filled further out they can say so, and the cost is theirs to
    * pick rather than inherited from a rendering option that was never about worldgen.
    */
   static int effectiveRadius(VoxyMapConfig config) {
      return config.generateChunks ? config.generationRadiusChunks : 0;
   }

   public String status() {
      int tracked = this.requested.values().stream().mapToInt(LongOpenHashSet::size).sum();

      int radius = effectiveRadius(this.config);

      return "chunk pregen: requested=" + this.generated + " failed=" + this.failed
         + " tracked=" + tracked
         + " inFlight=" + this.inFlight.size() + "/" + this.allowedTasks() + " (cap " + effectiveTasks(this.config) + ")"
         + " abandoned=" + this.abandoned
         + " skippedForTps=" + this.skippedTps + " skippedForFps=" + this.skippedFps
         + " skippedForGc=" + this.skippedGc + " skippedForBusy=" + this.skippedBusy
         + " skippedForResident=" + this.skippedResident
         + " resident=" + this.loaded + "/" + residentBudget()
         + " radius=" + radius + " chunks (" + RadialOffsets.forRadius(radius).length + " in the disc)"
         + " -- " + this.gate;
   }

   public void onLevelUnload(ServerLevel level) {
      this.requested.remove(level.dimension().identifier().toString());
   }
}
