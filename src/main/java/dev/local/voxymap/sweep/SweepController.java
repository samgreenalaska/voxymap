package dev.local.voxymap.sweep;

import dev.local.voxymap.VoxyMapConfig;
import dev.local.voxymap.util.Budget;
import dev.local.voxymap.util.Log;
import dev.local.voxymap.util.PerfMonitor;
import dev.local.voxymap.voxy.ColumnData;
import dev.local.voxymap.voxy.ColumnScanner;
import dev.local.voxymap.voxy.PaletteSnapshot;
import dev.local.voxymap.voxy.SectionIndex;
import dev.local.voxymap.voxy.VoxySource;
import dev.local.voxymap.xaero.Diagnostics;
import dev.local.voxymap.xaero.RegionSession;
import dev.local.voxymap.xaero.ReclaimPass;
import dev.local.voxymap.xaero.TileWriter;
import dev.local.voxymap.xaero.XaeroBridge;
import dev.local.voxymap.xaero.XaeroCoverage;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import xaero.map.MapProcessor;
import xaero.map.WorldMap;
import xaero.map.region.MapRegion;

/**
 * Drives the whole sweep from the client tick: region ordering, budgeting, and teardown.
 *
 * <p>Exactly one region is written to at a time, and at most {@link #MAX_PENDING_SAVES} more are
 * waiting for their save to land. That is the memory bound, and it has to be enforced here rather
 * than left to Xaero: a region with {@code beingWritten} set is immune both to {@code MapLimiter}
 * eviction and to the {@code LeafRegionTexture.postUpload} demote-and-clean path, so an unbounded
 * sweep would pin every region it ever touched.
 *
 * <p>Region order is not a queue. {@link #takeNearestRegion()} picks against the player's live
 * position every time, which is what makes the mapped area grow outward from wherever they are
 * rather than from wherever they were when the sweep started.
 */
public final class SweepController {
   /** A region that will not load is requeued a few times, then written off. */
   private static final int MAX_REGION_RETRIES = 3;

   /** How often the running sweep writes a progress + perf line to the log. */
   private static final long PROGRESS_LOG_INTERVAL_MILLIS = 30_000L;

   /** How often a long sweep flushes its fingerprint baseline to disk. */
   private static final int FINGERPRINT_SAVE_INTERVAL_REGIONS = 32;

   /**
    * Floor between one rescan finishing and the next starting.
    *
    * <p>Not a setting any more, and not zero. Since the fingerprint pass got cheap -- one walk into
    * a few-hundred-entry map, no column map built unless something moved -- there is no reason to
    * make the player choose an interval, and the honest answer to "how often" is "as often as is
    * useful". A second is that: Voxy ingests newly streamed ground over several seconds, so
    * checking faster finds the same nothing at real CPU cost, and checking slower is the lag that
    * made the map feel like it was trailing the player.
    */
   private static final long RESCAN_FLOOR_MILLIS = 1000L;

   /** Window over which Voxy's ingest rate is averaged. Long enough to be steady, short enough to react. */
   private static final long VOXY_GROWTH_WINDOW_MILLIS = 30_000L;

   /** How long a region that just failed to open waits before it is worth trying again. */
   private static final long RETRY_COOLDOWN_MILLIS = 2000L;

   /** Bigger than any real squared region distance, so a cooling region always sorts last. */
   private static final long RETRY_PENALTY = 1L << 40;

   /**
    * Regions that have been flushed but whose save has not landed yet. Each is still pinned by
    * {@code beingWritten}, so this is the memory bound that the old blocking flush provided.
    */
   private static final int MAX_PENDING_SAVES = 3;

   private enum Phase {
      IDLE,
      ENUMERATING,
      PALETTE,
      RUNNING
   }

   private final VoxyMapConfig config;
   private final VoxySource voxy = new VoxySource();
   private final ReclaimPass reclaim = new ReclaimPass();
   private final Budget budget = new Budget();
   private final PerfMonitor perf = new PerfMonitor();

   private Phase phase = Phase.IDLE;
   private SweepWorker worker;
   private SectionIndex index;
   private PaletteSnapshot palette;
   private PaletteSnapshot.Builder paletteBuilder;
   private ColumnScanner scanner;
   private boolean paletteIsRefresh;

   private TileWriter writer;
   private AuthoredIndex authored;
   private RegionSession current;

   /**
    * Regions still to sweep, unordered. The next one is chosen by distance to the player at the
    * moment it is opened -- see {@link #takeNearestRegion()}.
    */
   private final LongArrayList remaining = new LongArrayList();

   /** Region key -> the time before which a just-failed region should not be retried. */
   private final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap retryNotBefore =
      new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();

   /** Sessions loading ahead of the one being written. See {@link #tickPreload(MapProcessor)}. */
   private final ArrayDeque<RegionSession> preloading = new ArrayDeque<>();

   /** Flushed regions whose save has not landed yet. See {@link #pollPendingSaves()}. */
   private final ArrayDeque<RegionSession> pendingSaves = new ArrayDeque<>();

   private final ArrayDeque<Long> pendingTileChunks = new ArrayDeque<>();
   private LongArrayList explicitRegions;
   private LongOpenHashSet tileChunkFilter;
   private final java.util.HashMap<Long, Integer> retries = new java.util.HashMap<>();
   private ColumnData stalled;
   private int inFlight;

   private boolean reauthor;
   private int worldMinY;
   private int worldMaxY;
   private long startedAt;
   private long lastProgressMessage;
   private String pauseReason;
   private String identityAtStart;

   // Automatic mode.
   private boolean autoRun;
   private boolean environmentDumped;
   private boolean autoSuspended;
   private long joinedAt;
   private long lastAutoFinishedAt;
   private it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap lastFingerprints;
   private boolean fingerprintsDirty;

   /** False while the sweep is still on the cheap fingerprint-only pass. */
   private boolean builtFullIndex;

   /** Last palette built, reused across sweeps while Voxy's mapper has not grown. */
   private PaletteSnapshot cachedPalette;
   private String cachedPaletteIdentity;
   private String fingerprintIdentity;

   /** The player's region when the queue was built. See {@link #shouldRetarget()}. */
   private int queuedAroundX;
   private int queuedAroundZ;

   private int regionsTotal;
   private int regionsDone;
   private long chunksAuthored;
   private long chunksExisting;
   private long chunksIncomplete;
   private long chunksSkippedLoaded;
   private long regionStartedAt;

   // Why columns did not resolve, summed over the sweep. The scanner's own counters are per region.
   private long columnsVoid;
   private long columnsEstimated;
   private long columnsNoData;
   private long columnsGap;
   private long columnsShallow;
   private long columnsFilled;
   private long columnsProbed;

   // How fast Voxy's own database is growing, which is the direct read on whether it is ingesting.
   private int voxySections = -1;
   private int voxySampleSections;
   private long voxySampleAt;
   private double voxyGrowthPerMinute = -1.0;

   public SweepController(VoxyMapConfig config) {
      this.config = config;
   }

   /** Called once at client init; registers the per-frame timing hooks. */
   public void installPerfHooks() {
      this.perf.install();
   }

   public PerfMonitor perf() {
      return this.perf;
   }

   public boolean isRunning() {
      return this.phase != Phase.IDLE;
   }

   // ---------------------------------------------------------------- starting

   public String start(boolean reauthor, boolean force) {
      this.manualStart();
      return this.begin(null, null, reauthor, force, "full sweep");
   }

   /** A manual start always means "and resume automatic sweeping too". */
   private void manualStart() {
      this.autoRun = false;
      this.autoSuspended = false;
      this.lastFingerprints = null;
   }

   public String startRegion(int rx, int rz, boolean reauthor, boolean force) {
      this.manualStart();
      LongArrayList only = new LongArrayList();
      only.add(SectionIndex.regionKey(rx, rz));
      return this.begin(only, null, reauthor, force, "region " + rx + "," + rz);
   }

   public String startTileChunk(int tileChunkX, int tileChunkZ, boolean reauthor, boolean force) {
      this.manualStart();
      LongArrayList only = new LongArrayList();
      only.add(SectionIndex.regionKey(tileChunkX >> 3, tileChunkZ >> 3));
      LongOpenHashSet filter = new LongOpenHashSet();
      filter.add(SectionIndex.regionKey(tileChunkX, tileChunkZ));
      return this.begin(only, filter, reauthor, force, "tile chunk " + tileChunkX + "," + tileChunkZ);
   }

   private String begin(LongArrayList explicitRegions, LongOpenHashSet filter, boolean reauthor, boolean force, String what) {
      if (this.phase != Phase.IDLE) {
         return "A sweep is already running. Use /voxymap stop first.";
      }

      Minecraft mc = Minecraft.getInstance();
      ClientLevel level = mc.level;
      if (level == null) {
         return "Not in a world.";
      }

      MapProcessor mp = XaeroBridge.processor();
      if (mp == null) {
         return "Xaero's world map has no active session.";
      }

      String why = XaeroBridge.whyNotWritable(mp);
      if (why != null) {
         return "Xaero will not accept writes right now: " + why;
      }

      if (XaeroBridge.isUsingWorldSave(mp) && this.config.skipWorldSaveDimensions && !force) {
         return "This dimension is mapped straight from the world save, so Xaero already has "
            + "better data than Voxy does -- and it rebuilds regions from the MCA files on refresh, "
            + "which would wipe anything written here. Use --force to override.";
      }

      if (XaeroBridge.isCaveLike(mp) && this.config.skipCaveLikeDimensions && !force) {
         return "This dimension uses Xaero's cave layers (the Nether does). The surface column "
            + "model this mod implements would render it wrong. Use --force to override.";
      }

      if (!this.voxy.open(level)) {
         return "Voxy has no world database for this dimension.";
      }

      this.reauthor = reauthor || this.config.reauthorOwnTiles;
      this.worldMinY = level.getMinY();
      this.worldMaxY = level.getMaxY() + 1;
      this.tileChunkFilter = filter;
      this.startedAt = System.currentTimeMillis();
      this.lastProgressMessage = 0L;
      this.regionsTotal = 0;
      this.regionsDone = 0;
      this.chunksAuthored = 0L;
      this.chunksExisting = 0L;
      this.chunksIncomplete = 0L;
      this.chunksSkippedLoaded = 0L;
      this.columnsVoid = 0L;
      this.columnsEstimated = 0L;
      this.columnsNoData = 0L;
      this.columnsGap = 0L;
      this.columnsShallow = 0L;
      this.columnsFilled = 0L;
      this.columnsProbed = 0L;
      this.pauseReason = null;
      this.identityAtStart = null;
      this.stalled = null;
      this.inFlight = 0;
      this.remaining.clear();
      this.retryNotBefore.clear();
      this.pendingTileChunks.clear();
      this.pendingSaves.clear();
      this.preloading.clear();

      this.explicitRegions = explicitRegions;
      this.authored = new AuthoredIndex(mp);
      this.writer = null;

      this.worker = new SweepWorker(this.voxy);

      // Only an automatic rescan with a baseline to compare against can take the cheap path; a
      // manual or first sweep needs the column map regardless.
      this.builtFullIndex = !(this.autoRun && this.config.autoRescanOnlyChangedRegions && this.lastFingerprints != null && explicitRegions == null);
      this.worker.requestEnumerate(!this.builtFullIndex);
      this.phase = Phase.ENUMERATING;

      // The environment dump is 20 lines. Worth it once, but the automatic rescan runs every few
      // seconds, so only print it for manual sweeps and the first automatic one of a session.
      boolean quiet = this.autoRun && this.environmentDumped;

      if (quiet) {
         Log.diag("starting " + what);
      } else {
         Log.dev("starting " + what + " (reauthor=" + this.reauthor + ")");
         Log.dev("--- environment at sweep start ---");

         for (String line : this.status()) {
            Log.dev("  " + line);
         }

         Log.dev(
            "  config: applyBudgetMs=" + this.config.applyBudgetMillis
            + " rebuildBuffersInline=" + this.config.rebuildBuffersInline
            + " settleTimeoutS=" + this.config.settleTimeoutSeconds
            + " incompleteTolerance=" + this.config.incompleteChunkTolerance
            + " autoStart=" + this.config.autoStart
            + " pauseWhenFpsBelow=" + this.config.pauseWhenFpsBelow
      );
         Log.dev("  singleplayer=" + Minecraft.getInstance().isLocalServer() + " serverBrand=" + describeServer());
         Log.dev("----------------------------------");
         this.environmentDumped = true;
      }

      return "Enumerating Voxy's stored sections...";
   }

   // ---------------------------------------------------------------- stopping

   public String stop() {
      // An explicit stop also suspends automatic sweeping; otherwise it would restart on its own
      // a couple of minutes later, which is not what "stop" means to anyone.
      this.autoSuspended = true;

      if (this.phase == Phase.IDLE) {
         return "No sweep is running. Automatic sweeping is now suspended until /voxymap auto on.";
      }

      this.teardown("stopped by request");
      return "Sweep stopped. Automatic sweeping is suspended until /voxymap auto on.";
   }

   public String setAuto(boolean enabled) {
      this.autoSuspended = !enabled;
      if (enabled) {
         this.lastAutoFinishedAt = 0L;
         this.joinedAt = System.currentTimeMillis();
      }

      return enabled
         ? "Automatic sweeping enabled. It will pick up newly generated Voxy chunks as they appear."
         : "Automatic sweeping suspended for this session.";
   }

   private void abort(String reason) {
      Log.warn("aborting the sweep: " + reason);
      this.teardown(reason);
   }

   private void teardown(String reason) {
      // Fingerprints are banked per region in bankRegion, so an interrupted sweep keeps whatever
      // it got through. All that is left here is persisting them.
      this.persistFingerprints();
      this.pendingFingerprints = null;
      this.lastAutoFinishedAt = System.currentTimeMillis();
      this.autoRun = false;

      if (this.current != null) {
         // Leave the data in place and let Xaero save it; just stop pinning the region.
         this.current.abandon();
         this.current = null;
      }

      // Sessions that were only loading never wrote anything, so there is nothing to save; they
      // just stop being tracked. Xaero clears their beingWritten on its own save cycle.
      for (RegionSession session : this.preloading) {
         session.abandon();
      }

      if (this.authored != null) {
         this.authored.close();
         this.authored = null;
      }

      if (this.worker != null) {
         this.worker.shutdown();
         this.worker = null;
      }

      this.voxy.close();
      this.index = null;
      this.palette = null;
      this.paletteBuilder = null;
      this.writer = null;
      this.scanner = null;
      this.stalled = null;
      this.inFlight = 0;
      this.remaining.clear();
      this.retryNotBefore.clear();
      this.pendingTileChunks.clear();
      this.pendingSaves.clear();
      this.preloading.clear();
      this.retries.clear();
      this.tileChunkFilter = null;
      this.explicitRegions = null;
      this.phase = Phase.IDLE;

      boolean uneventful = this.autoRun && this.chunksAuthored == 0 && "complete".equals(reason);

      if (uneventful) {
         Log.diag(
            "rescan finished with nothing to do: regions=" + this.regionsDone + "/" + this.regionsTotal
         );
      } else {
      Log.dev(this.perf.summary());
      long elapsed = Math.max(1L, System.currentTimeMillis() - this.startedAt);
      Log.dev(
         "sweep finished (" + reason + "): regions=" + this.regionsDone + "/" + this.regionsTotal
            + " in " + elapsed / 1000L + "s (" + String.format("%.0f", this.regionsDone * 1000.0 / elapsed * 60.0) + " regions/min)"
            + " authored=" + this.chunksAuthored
            + " existing=" + this.chunksExisting + " incomplete=" + this.chunksIncomplete + " alreadyLoaded=" + this.chunksSkippedLoaded
            + " | columns: " + this.columnSummary()
            + " | " + this.voxyGrowth()
      );
      }

      if (this.chunksAuthored == 0 && this.regionsDone > 0) {
         // Distinguishes "the bridge wrote nothing" from "the bridge wrote and it did not show",
         // which are entirely different bugs and look identical on the map.
         Log.warn(
            "sweep authored ZERO chunks. Nothing was written, so this is not a rendering problem."
               + (this.chunksIncomplete > 0
                  ? " Every candidate chunk failed the completeness gate -- Voxy's stored data is too vertically sparse here."
                  : "")
               + (this.chunksExisting > 0 ? " " + this.chunksExisting + " chunks were already mapped by Xaero." : "")
               + (this.chunksSkippedLoaded > 0 ? " " + this.chunksSkippedLoaded + " chunks were loaded client-side and left to Xaero." : "")
         );
      }
   }

   /**
    * Why columns did not resolve, which is what the black specks on the map are made of.
    *
    * <ul>
    *   <li>{@code noData} -- Voxy has never ingested that LOD-0 stack. Not ours to draw.
    *   <li>{@code gap} -- a hole in the middle of the stored stack, so the real surface could be
    *       inside it. Left blank on purpose.
    *   <li>{@code shallow} -- the stack stopped short with nothing see-through to infer from.
    *   <li>{@code filled} -- unresolved, but a resolved column within four blocks was copied over
    *       it rather than leaving the whole chunk undrawn.
    *   <li>{@code estimated} -- see-through the whole way down with no floor stored; drawn from the
    *       deepest block we did see rather than left blank. Ocean, almost always.
    *   <li>{@code void} -- genuinely empty down to the world bottom. Painted as void.
    * </ul>
    */
   private String columnSummary() {
      return "void=" + this.columnsVoid
         + " filled=" + this.columnsFilled
         + " probed=" + this.columnsProbed
         + " estimated=" + this.columnsEstimated
         + " noData=" + this.columnsNoData
         + " gap=" + this.columnsGap
         + " shallow=" + this.columnsShallow;
   }

   private static String describeServer() {
      try {
         Minecraft mc = Minecraft.getInstance();
         if (mc.getConnection() == null) {
            return "<no connection>";
         }

         return mc.isLocalServer() ? "integrated" : String.valueOf(mc.getCurrentServer() == null ? "<unknown>" : mc.getCurrentServer().ip);
      } catch (Throwable t) {
         return "<threw " + t + ">";
      }
   }

   // ------------------------------------------------------------------- tick

   public void tick() {
      MapProcessor mp = XaeroBridge.processor();

      if (this.phase == Phase.IDLE) {
         if (this.config.reclaimNearPlayer && mp != null) {
            this.reclaim.tick(mp);
         }

         this.tickAuto(mp);
         return;
      }

      if (mp == null) {
         this.abort("Xaero's session went away");
         return;
      }

      if (WorldMap.crashHandler != null && WorldMap.crashHandler.getCrashedBy() != null) {
         this.abort("Xaero's map crashed: " + WorldMap.crashHandler.getCrashedBy());
         return;
      }

      if (!this.voxy.stillValid()) {
         this.abort("the client changed worlds");
         return;
      }

      String why = XaeroBridge.whyNotWritable(mp);
      if (why != null) {
         // Transient by nature (a save in progress, a world update). Idle rather than abort.
         this.pauseReason = why;
         return;
      }

      this.pauseReason = null;

      if (this.checkIdentityDrift(mp)) {
         return;
      }

      if (this.config.reclaimNearPlayer) {
         this.reclaim.tick(mp);
      }

      switch (this.phase) {
         case ENUMERATING:
            this.tickEnumerating(mp);
            break;
         case PALETTE:
            this.tickPalette(mp);
            break;
         case RUNNING:
            this.tickRunning(mp);
            break;
         default:
            break;
      }

      this.maybeShowProgress();
   }

   /**
    * Starts a sweep on its own after joining a world, and again whenever Voxy has ingested new
    * ground since the last pass.
    *
    * <p>Everything here is best-effort and silent: the conditions it waits on (region detection,
    * multiworld confirmation, Voxy having a database for the dimension) are all normal states
    * shortly after joining, not errors worth telling the player about.
    */
   private void tickAuto(MapProcessor mp) {
      if (!this.config.autoStart || this.autoSuspended || mp == null) {
         return;
      }

      Minecraft mc = Minecraft.getInstance();
      if (mc.level == null || mc.player == null) {
         // Left the world: re-arm so rejoining sweeps again. The baseline is reloaded from disk
         // on the next join, so dropping the in-memory copy costs nothing.
         this.joinedAt = 0L;
         this.lastAutoFinishedAt = 0L;
         this.lastFingerprints = null;
         this.fingerprintIdentity = null;
         return;
      }

      long now = System.currentTimeMillis();

      if (this.joinedAt == 0L) {
         this.joinedAt = now;
         return;
      }

      // Fingerprints are per world/dimension/multiworld. Changing dimension must not let one
      // dimension's baseline decide which of another's regions get skipped.
      String identity = mp.getCurrentWorldId() + " / " + mp.getCurrentDimId() + " / " + mp.getCurrentMWId();
      if (!identity.equals(this.fingerprintIdentity)) {
         this.fingerprintIdentity = identity;
         this.lastFingerprints = FingerprintStore.load(mp);
         this.lastAutoFinishedAt = 0L;
         Log.dev(
            "auto: baseline for [" + identity + "] "
               + (this.lastFingerprints == null ? "not found, the first sweep will be a full one" : "loaded, " + this.lastFingerprints.size() + " regions")
         );
      }

      if (now - this.joinedAt < this.config.autoStartDelaySeconds * 1000L) {
         return;
      }

      if (this.lastAutoFinishedAt != 0L) {
         if (now - this.lastAutoFinishedAt < RESCAN_FLOOR_MILLIS) {
            return;
         }
      }

      if (!XaeroBridge.writable(mp)) {
         return;
      }

      if (XaeroBridge.isUsingWorldSave(mp) && this.config.skipWorldSaveDimensions) {
         return;
      }

      if (XaeroBridge.isCaveLike(mp) && this.config.skipCaveLikeDimensions) {
         return;
      }

      if (VoxySource.peek(mc.level) == null) {
         return;
      }

      this.autoRun = true;
      String result = this.begin(null, null, false, false, this.lastFingerprints == null ? "automatic sweep" : "automatic rescan");

      if (this.phase == Phase.IDLE) {
         // Refused for some reason; back off a full rescan interval rather than retrying per tick.
         this.autoRun = false;
         this.lastAutoFinishedAt = now;
         Log.diag("automatic sweep not started: " + result);
      }
   }

   private void tickEnumerating(MapProcessor mp) {
      if (this.worker.enumerating()) {
         return;
      }

      Throwable err = this.worker.error();
      if (err != null) {
         this.abort("could not read Voxy's section index: " + err);
         return;
      }

      if (!this.builtFullIndex) {
         // Cheap pass first: per-region hashes only, no column map. In steady state the answer is
         // "nothing changed", and this is the entire cost of a rescan.
         var scanned = this.worker.scan();
         if (scanned == null) {
            this.abort("Voxy returned no section fingerprints");
            return;
         }

         this.recordVoxyGrowth(scanned.sectionCount());

         if (!this.anyRegionChanged(scanned.fingerprints())) {
            Log.diag("rescan: nothing changed across " + scanned.fingerprints().size() + " regions");
            this.teardown("complete");
            return;
         }

         // Something moved, so the column map is worth the second it costs.
         this.builtFullIndex = true;
         this.worker.requestEnumerate(false);
         return;
      }

      this.index = this.worker.index();
      if (this.index == null) {
         this.abort("Voxy returned no section index");
         return;
      }

      this.recordVoxyGrowth(this.worker.scan().sectionCount());
      this.buildRegionQueue();
      Log.dev(
         "indexed " + this.index.sectionCount() + " LOD-0 sections in " + this.index.columnCount() + " columns across "
            + this.regionsTotal + " map regions"
      );

      // Reclassifying ~3000 block states costs about a second of sliced main-thread work, and the
      // answer only changes when Voxy ingests a block or biome it has never seen. Reuse the last
      // snapshot when the palette is the same size it was, which on a rescan is almost always.
      if (this.cachedPalette != null
         && this.cachedPaletteIdentity != null
         && this.cachedPaletteIdentity.equals(this.fingerprintIdentity)
         && this.paletteMatches(this.cachedPalette)) {
         this.usePalette(mp, this.cachedPalette);
         Log.diag("reusing the cached palette (" + this.cachedPalette.blockCount() + " block states)");
         return;
      }

      this.paletteBuilder = PaletteSnapshot.builder(this.voxy.engine(), mp, mp.getWorld());
      this.paletteIsRefresh = false;
      this.phase = Phase.PALETTE;
   }

   /** Whether a snapshot still covers everything Voxy's mapper knows about. */
   private boolean paletteMatches(PaletteSnapshot snapshot) {
      try {
         var mapper = this.voxy.engine().getMapper();
         return mapper.getStateEntries().length == snapshot.blockCount() && mapper.getBiomeEntries().length == snapshot.biomeCount();
      } catch (Throwable t) {
         return false;
      }
   }

   private it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap pendingFingerprints;

   /**
    * Regions whose Voxy fingerprint moved since we last authored them, and whose own tiles may
    * therefore be replaced on this pass.
    *
    * <p>This exists because tiles are written once and then never revisited: {@code ScannedProbe}
    * refuses any loaded tile, ours included, so a tile authored from a half-delivered voxel stack
    * is frozen exactly as wrong as it was written. That never mattered while the only source of
    * Voxy data was the player walking around -- data arrived before the ground was mapped, not
    * after. With VoxyServer streaming a stored world in (SS7.18), it arrives continuously and
    * bottom-up, so a column can resolve to stone one pass and grass the next.
    */
   private final LongOpenHashSet staleRegions = new LongOpenHashSet();

   /**
    * Regions whose last pass produced output we already know was provisional, and how many
    * consecutive passes that has been true for.
    *
    * <p>Fingerprints alone cannot close this loop. They are computed from the section index, which
    * cannot see unflushed data (§7.15), so a region can be written from half-delivered voxels,
    * banked, and then never look changed again -- the data that would have corrected it was already
    * there, just invisible to the thing deciding what to re-sweep. That is why repair appeared to
    * stop partway: everything still wrong had gone quiet.
    *
    * <p>So the decision to come back is made on our own output instead. A region is provisional if
    * it left chunks unwritten ({@code skippedIncomplete}) or if the scanner had to reach past the
    * index to find sections ({@code probedSections}) -- both conditions that improve as delivery
    * completes, and both directly observed rather than inferred.
    */
   private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap provisionalPasses =
      new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();

   /**
    * How many consecutive provisional passes a region gets before it is left alone.
    *
    * <p>Needed because the frontier is permanently provisional: at the edge of Voxy's coverage
    * chunks are genuinely missing and always will be, so an uncapped rule would re-sweep the map's
    * border forever -- the same failure to settle that §7.1 had to fix once already. Any real
    * change to the region's data resets the budget, so ground that is still arriving keeps healing;
    * only ground that has stopped changing while still incomplete gives up.
    */
   private static final int MAX_PROVISIONAL_PASSES = 8;

   /**
    * Regions reconciled per pass when the index is known to be behind.
    *
    * <p>Every other mechanism here decides *which columns* to trust; this one exists because the
    * decision that comes first -- which regions to sweep at all -- is made from fingerprints, and
    * fingerprints are computed from the same index that cannot see unflushed data (§7.15). So a
    * region that received sections but is not next to the player is never queued: the only thing
    * that would queue it is the very signal that is blind to it. Measured on 2026-08-05 with the
    * generation rate raised: 466,105 sections delivered, 457,511 visible, 71 MB of write-ahead log
    * against 930 MB of flushed tables, and the sweep reporting "nothing changed across 213 regions"
    * the whole time.
    *
    * <p>So when the index claims nothing moved, a rotating slice of the map is swept anyway rather
    * than idling. Small, because each one is marked stale and therefore skips the §5.1 shortcut.
    */
   private static final int RECONCILE_SLICE = 4;

   /** Where the rotating reconciliation has reached. */
   private int reconcileCursor;

   /**
    * Whether the last pass had to reach past the index for section data.
    *
    * <p>This is the evidence that the index is behind, observed rather than assumed: {@code
    * probeStack} and {@code probeAbove} only count when a section is found that the index did not
    * know about. When it is zero the index is trustworthy and reconciliation is pure waste.
    */
   private boolean indexWasBehind;

   private void noteProvisional(int rx, int rz) {
      long key = SectionIndex.regionKey(rx, rz);
      boolean probed = this.scanner != null && this.scanner.probedSections > 0;
      boolean provisional = this.writer.skippedIncomplete > 0 || probed;

      // Any probe that found something means the index did not know about a section that exists,
      // so its verdict on what changed cannot be trusted this pass or the next.
      if (probed) {
         this.indexWasBehind = true;
      }

      if (!provisional) {
         this.provisionalPasses.remove(key);
         return;
      }

      int passes = this.provisionalPasses.get(key) + 1;

      if (passes >= MAX_PROVISIONAL_PASSES) {
         this.provisionalPasses.remove(key);
      } else {
         this.provisionalPasses.put(key, passes);
      }
   }

   private void buildRegionQueue() {
      LongArrayList regions = new LongArrayList();

      if (this.explicitRegions != null) {
         regions.addAll(this.explicitRegions);
      } else {
         regions.addAll(this.index.regions());
      }

      // On an automatic rescan, drop regions whose Voxy content is byte-for-byte what it was last
      // time. Re-walking a region costs about a second even with nothing to write, so without
      // this the background pass would never be idle.
      it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap fingerprints = this.worker.scan().fingerprints();

      this.staleRegions.clear();

      if (this.autoRun && this.config.autoRescanOnlyChangedRegions && this.lastFingerprints != null && this.explicitRegions == null) {
         LongArrayList changed = new LongArrayList();

         for (int i = 0; i < regions.size(); i++) {
            long key = regions.getLong(i);
            if (fingerprints.get(key) != this.lastFingerprints.get(key)) {
               changed.add(key);

               // Changed means Voxy has data here it did not have when we last authored. Anything
               // we wrote from the old data is suspect, so allow it to be replaced on this pass.
               //
               // Only the fingerprint-driven set, deliberately. addRegionsNearPlayer below adds the
               // player's 3x3 unconditionally (SS7.15) and those regions would then be rewritten on
               // every single pass forever, which is the cost the --reauthor sweep exists to avoid
               // paying continuously. They heal one pass later instead, once RocksDB flushes and
               // the fingerprint moves with it.
               this.staleRegions.add(key);

               // Real new data here, so any earlier incompleteness is worth re-examining from a
               // full budget rather than from however much of one was left.
               this.provisionalPasses.remove(key);
            }
         }

         int changedByIndex = changed.size();

         // Regions we already know we got wrong, whether or not the index noticed anything move.
         // Marked stale as well as queued: the tiles are already there, so without that
         // isFullyMapped would skip every one of them and the pass would achieve nothing.
         int provisional = 0;

         for (long key : this.provisionalPasses.keySet()) {
            if (!this.staleRegions.contains(key)) {
               changed.add(key);
               this.staleRegions.add(key);
               provisional++;
            }
         }

         // The index says nothing moved -- but if the last pass found sections it did not know
         // about, that claim is worth nothing. Reconcile a slice rather than believing it.
         int reconciled = 0;

         // Consumed and cleared here, so it always describes the pass that just finished. Left set,
         // it would keep reconciling long after the database had caught up.
         boolean wasBehind = this.indexWasBehind;
         this.indexWasBehind = false;

         if (changedByIndex == 0 && wasBehind && !regions.isEmpty()) {
            for (int i = 0; i < RECONCILE_SLICE && i < regions.size(); i++) {
               long key = regions.getLong(this.reconcileCursor % regions.size());
               this.reconcileCursor++;

               if (!this.staleRegions.contains(key)) {
                  changed.add(key);
                  this.staleRegions.add(key);
                  reconciled++;
               }
            }
         }

         this.addRegionsNearPlayer(changed);

         if (changed.isEmpty()) {
            Log.diag("rescan: nothing changed across " + regions.size() + " regions");
         } else if (changedByIndex == 0 && reconciled > 0) {
            Log.dev(
               "rescan: index reports no change across " + regions.size()
                  + " regions but was behind last pass; reconciling " + reconciled
                  + " (cursor " + (this.reconcileCursor % Math.max(1, regions.size())) + ")"
            );
         } else {
            int nearPlayer = changed.size() - changedByIndex - provisional;

            Log.dev(
               "rescan: " + changedByIndex + " of " + regions.size() + " regions changed since the last pass"
                  + (provisional > 0 ? " (+" + provisional + " still provisional)" : "")
                  + (nearPlayer > 0 ? " (+" + nearPlayer + " near the player)" : "")
            );
         }

         regions = changed;
      }

      this.pendingFingerprints = fingerprints;

      // Deliberately not sorted. Ordering here would fix the spiral around wherever the player
      // happened to be when the sweep started; takeNearestRegion picks against where they are now.
      this.remaining.clear();
      this.remaining.addAll(regions);
      this.retryNotBefore.clear();
      this.regionsTotal = this.remaining.size();

      LocalPlayer player = Minecraft.getInstance().player;
      this.queuedAroundX = player == null ? 0 : player.blockPosition().getX() >> 9;
      this.queuedAroundZ = player == null ? 0 : player.blockPosition().getZ() >> 9;
   }

   /**
    * Whether the sweep should stop and re-aim at where the player is now.
    *
    * <p>{@link #takeNearestRegion()} keeps the spiral centred on the player, but only over the
    * regions already in the queue -- and that set was fixed when the sweep started, by a fingerprint
    * diff against Voxy's data at that moment. Ground the player teleports to is not in it and cannot
    * be, because Voxy has not ingested it yet. So the spiral tracks the player perfectly and still
    * appears to lag by however long the sweep runs, which on a big rescan is minutes.
    *
    * <p>Ending the sweep early costs almost nothing now: regions are banked as they finish, so
    * nothing already done is repeated, and everything not reached is still seen as changed next
    * pass. The next rescan re-enumerates -- about a second -- and rebuilds the queue around the new
    * position.
    *
    * <p>Gated on a rescan interval having passed, so a player walking across a region boundary does
    * not restart the sweep every few seconds.
    */
   private boolean shouldRetarget() {
      if (!this.autoRun || this.explicitRegions != null || this.remaining.isEmpty()) {
         return false;
      }

      LocalPlayer player = Minecraft.getInstance().player;
      if (player == null) {
         return false;
      }

      if (System.currentTimeMillis() - this.startedAt < RESCAN_FLOOR_MILLIS) {
         return false;
      }

      return (player.blockPosition().getX() >> 9) != this.queuedAroundX || (player.blockPosition().getZ() >> 9) != this.queuedAroundZ;
   }

   /**
    * Adds the regions around the player whether or not the index thinks they changed.
    *
    * <p>The index cannot see freshly ingested ground -- see {@code ColumnScanner.probeStack} -- so
    * on a young database the region the player is standing in never registers as changed and never
    * gets swept, which is precisely where the interesting ground is. Sweeping its neighbourhood
    * unconditionally costs almost nothing: the tile chunks that are already mapped are skipped
    * before they are scanned, and the ones that are not are the ones worth looking at.
    *
    * <p>One region either side covers 1536 blocks, comfortably past any render distance, so
    * anything Voxy ingests from loaded chunks lands inside it.
    */
   private void addRegionsNearPlayer(LongArrayList regions) {
      LocalPlayer player = Minecraft.getInstance().player;
      if (player == null) {
         return;
      }

      int prx = player.blockPosition().getX() >> 9;
      int prz = player.blockPosition().getZ() >> 9;
      LongOpenHashSet present = new LongOpenHashSet(regions);

      for (int dx = -1; dx <= 1; dx++) {
         for (int dz = -1; dz <= 1; dz++) {
            long key = SectionIndex.regionKey(prx + dx, prz + dz);
            if (present.add(key)) {
               regions.add(key);
            }
         }
      }
   }

   /**
    * Takes the region nearest the player <em>now</em>.
    *
    * <p>The whole point of the periodic rescan is that mapped ground grows outward from wherever
    * the player is. A queue ordered once at sweep start cannot do that: any sweep with real work in
    * it outlives the rescan interval, so it goes on spiralling around a position the player left
    * minutes ago, and a teleport strands it entirely on the far side of the world.
    *
    * <p>A linear scan of a few hundred keys, once per region opened -- a few microseconds against
    * the ~1.5 s the region itself costs. Sorting would be cheaper on paper and wrong in practice.
    */
   private long takeNearestRegion() {
      LocalPlayer player = Minecraft.getInstance().player;
      int prx = player == null ? 0 : player.blockPosition().getX() >> 9;
      int prz = player == null ? 0 : player.blockPosition().getZ() >> 9;
      long now = System.currentTimeMillis();

      int best = 0;
      long bestScore = Long.MAX_VALUE;

      for (int i = 0; i < this.remaining.size(); i++) {
         long key = this.remaining.getLong(i);
         long score = dist2(key, prx, prz);

         // A region that just failed to open is pushed behind everything else for a moment, so a
         // stubborn one cannot burn its whole retry budget in three consecutive ticks. Nearest
         // still wins among regions that are all cooling down.
         if (now < this.retryNotBefore.get(key)) {
            score += RETRY_PENALTY;
         }

         if (score < bestScore) {
            bestScore = score;
            best = i;
         }
      }

      long key = this.remaining.getLong(best);

      // Swap-remove: position carries no meaning once selection is by live distance.
      int last = this.remaining.size() - 1;
      this.remaining.set(best, this.remaining.getLong(last));
      this.remaining.removeLong(last);
      return key;
   }

   /**
    * Tracks how fast Voxy's stored section count is moving.
    *
    * <p>"Is Voxy still working?" has come up twice and both times it was answered by eyeballing
    * numbers across log lines. It is the one question this mod is in a position to answer for free
    * -- it counts Voxy's sections once a second anyway -- so it should just say. A count that is
    * climbing means Voxy is ingesting; a flat one while you move means it is not.
    */
   private void recordVoxyGrowth(int sections) {
      long now = System.currentTimeMillis();

      if (this.voxySampleAt == 0L) {
         this.voxySampleAt = now;
         this.voxySampleSections = sections;
      }

      this.voxySections = sections;
      long elapsed = now - this.voxySampleAt;

      if (elapsed >= VOXY_GROWTH_WINDOW_MILLIS) {
         this.voxyGrowthPerMinute = (sections - this.voxySampleSections) * 60000.0 / elapsed;
         this.voxySampleAt = now;
         this.voxySampleSections = sections;
      }
   }

   private String voxyGrowth() {
      if (this.voxySections < 0) {
         return "voxy: not sampled yet";
      }

      return "voxy: " + this.voxySections + " LOD-0 sections stored"
         + (this.voxyGrowthPerMinute < 0.0
            ? " (measuring the rate...)"
            : String.format(", %+.0f/min over the last %ds", this.voxyGrowthPerMinute, VOXY_GROWTH_WINDOW_MILLIS / 1000L));
   }

   /** Whether any region's hash differs from the banked baseline. */
   private boolean anyRegionChanged(it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap scanned) {
      if (this.lastFingerprints == null) {
         return true;
      }

      for (it.unimi.dsi.fastutil.longs.Long2LongMap.Entry e : scanned.long2LongEntrySet()) {
         if (this.lastFingerprints.get(e.getLongKey()) != e.getLongValue()) {
            return true;
         }
      }

      return false;
   }

   private static MapProcessor mpOrNull() {
      return XaeroBridge.processor();
   }

   /**
    * Watches for Xaero's world/dimension/multiworld ids changing under a running sweep.
    *
    * <p>This is the leading suspect for multiplayer never showing anything. Xaero confirms the
    * multiworld id asynchronously on a server, and {@code MapSaveLoad}'s save drain ends with
    * {@code if (!mapProcessor.isEqual(region.getWorldId(), region.getDimId(), region.getMwId()))
    * region.clearRegion(...)} -- so if the id changes after we opened a region, everything we
    * wrote is discarded rather than kept, silently.
    *
    * @return true if the sweep was aborted
    */
   private boolean checkIdentityDrift(MapProcessor mp) {
      String now = mp.getCurrentWorldId() + " / " + mp.getCurrentDimId() + " / " + mp.getCurrentMWId();

      if (this.identityAtStart == null) {
         this.identityAtStart = now;
         return false;
      }

      if (this.identityAtStart.equals(now)) {
         return false;
      }

      Log.warn(
         "IDENTITY DRIFT: Xaero's ids changed mid-sweep, from [" + this.identityAtStart + "] to [" + now
            + "]. Any region written under the old ids is cleared by Xaero after saving instead of being kept."
      );
      Log.warn("  This is very likely why multiplayer never shows anything. Aborting so nothing more is wasted.");
      this.abort("Xaero's world identity changed mid-sweep");
      return true;
   }

   private static long dist2(long key, int prx, int prz) {
      long dx = SectionIndex.regionX(key) - prx;
      long dz = SectionIndex.regionZ(key) - prz;
      return dx * dx + dz * dz;
   }

   private void tickPalette(MapProcessor mp) {
      long deadline = System.nanoTime() + 5_000_000L;

      if (!this.paletteBuilder.advance(deadline)) {
         return;
      }

      PaletteSnapshot snapshot = this.paletteBuilder.finish();
      this.paletteBuilder = null;
      this.usePalette(mp, snapshot);

      if (!this.paletteIsRefresh) {
         Log.dev("classified " + snapshot.blockCount() + " block states and " + snapshot.biomeCount() + " biomes");
      }
   }

   private void usePalette(MapProcessor mp, PaletteSnapshot snapshot) {
      this.palette = snapshot;
      this.cachedPalette = snapshot;
      this.cachedPaletteIdentity = this.fingerprintIdentity;

      this.writer = new TileWriter(mp, snapshot, this.worldMinY, this.reauthor, this.config.incompleteChunkTolerance);
      this.scanner = new ColumnScanner(this.voxy.engine(), this.index, snapshot, this.worldMinY, this.worldMaxY, this.config.maxOverlays);
      this.worker.setScanner(this.scanner);
      this.phase = Phase.RUNNING;
   }

   private void tickRunning(MapProcessor mp) {
      if (this.shouldRetarget()) {
         LocalPlayer player = Minecraft.getInstance().player;
         Log.dev(
            "re-targeting: the player is at r(" + (player.blockPosition().getX() >> 9) + "," + (player.blockPosition().getZ() >> 9)
               + ") but this sweep was queued around r(" + this.queuedAroundX + "," + this.queuedAroundZ + ") with "
               + this.remaining.size() + " regions left"
         );
         this.teardown("player moved");

         // Do not sit out another rescan interval: re-aiming is the whole point.
         this.lastAutoFinishedAt = 0L;
         return;
      }

      this.pollPendingSaves();
      this.tickPreload(mp);

      if (this.phase != Phase.RUNNING) {
         // A palette refresh was triggered while filling the preload queue.
         return;
      }

      if (this.current == null && !this.promoteNextRegion(mp)) {
         return;
      }

      RegionSession session = this.current;

      switch (session.state()) {
         case OPENING:
         case LOADING:
            // Only reachable when the promoted session had not finished loading. It is now the
            // one the whole sweep is waiting on, so it gets the priority a preload does not.
            session.prepare(true);
            if (session.state() == RegionSession.State.RETRY) {
               this.closeRegion(true);
            } else if (session.state() == RegionSession.State.FAILED) {
               this.closeRegion(false);
            }

            return;
         case APPLYING:
            if (session.applyStalled()) {
               Log.warn(
                  "r(" + session.regionX + "," + session.regionZ + ") APPLY_STALLED: nothing written in "
                     + this.config.loadTimeoutSeconds + "s of being the region under the writer; requeuing."
                     + " pendingTileChunks=" + this.pendingTileChunks.size()
                     + " inFlight=" + this.inFlight
                     + " queuedResults=" + this.worker.queuedResults()
                     + " heldPayload=" + (this.stalled != null)
                     + " " + Diagnostics.regionState(session.region())
               );
               this.closeRegion(true);
               return;
            }

            this.tickApply(mp, session);
            return;
         case SETTLING:
            // Holds the region open until its textures have uploaded. Without this the forced
            // save clears beingWritten, postUpload demotes the region, and every tile chunk that
            // had not been rebuilt yet is stranded until the world is reloaded. See DIAGNOSIS.md.
            session.pollSettle();
            return;
         default:
            this.closeRegion(false);
      }
   }

   /**
    * Gets the next regions loading while the current one is still being written -- section 5.3.
    *
    * <p>A region cannot be written to until Xaero's loader has taken it to load state 2, and that
    * costs 250-700 ms of nothing but waiting: {@code MapSaveLoad.run} completes one region load per
    * pass, {@code MapProcessor.run} sleeps 40-100 ms between passes, and a region needs two of
    * them. Serially that is a quarter to a third of every region's wall time. Started early it is
    * free, because it overlaps the apply and settle of the region in front.
    *
    * <p>Only the <em>loading</em> is overlapped. Exactly one region is ever written to at a time,
    * which leaves the locking argument, the single {@link TileWriter}, and the worker's
    * one-region-at-a-time generation counter exactly as they were.
    *
    * <p>{@code maxOpenRegions} is the depth, and it bounds memory as much as it bounds throughput:
    * every session alive has {@code beingWritten} set and so pins a region and its textures.
    */
   private void tickPreload(MapProcessor mp) {
      // Advance what is already in flight first, so a session that finishes loading this tick can
      // be promoted in the same tick.
      for (java.util.Iterator<RegionSession> it = this.preloading.iterator(); it.hasNext();) {
         RegionSession session = it.next();
         session.prepare(false);

         if (session.state() == RegionSession.State.RETRY) {
            it.remove();
            this.requeueRegion(session);
         } else if (session.state() == RegionSession.State.FAILED) {
            it.remove();
            this.regionsDone++;
            Log.warn("r(" + session.regionX + "," + session.regionZ + ") could not be opened: " + session.failure());
         } else {
            // Loaded but not promoted yet, which can be a whole region's worth of time away.
            session.keepAlive();
         }
      }

      int depth = Math.max(1, this.config.maxOpenRegions) - 1;

      while (this.phase == Phase.RUNNING
         && this.preloading.size() < depth
         && !this.remaining.isEmpty()
         && this.pendingSaves.size() < MAX_PENDING_SAVES) {
         RegionSession session = this.beginRegion(mp);
         if (session == null) {
            return;
         }

         this.preloading.add(session);
      }
   }

   /** Takes the best session already in flight, or starts one if there is nothing to take. */
   private boolean promoteNextRegion(MapProcessor mp) {
      RegionSession chosen = null;

      // Prefer one that has finished loading, which is the entire point of preloading.
      for (RegionSession session : this.preloading) {
         if (session.state() == RegionSession.State.APPLYING) {
            chosen = session;
            break;
         }
      }

      if (chosen == null && !this.preloading.isEmpty()) {
         // Still loading. Take the oldest and wait on it the way the serial version did.
         chosen = this.preloading.peek();
      }

      if (chosen != null) {
         this.preloading.remove(chosen);
         this.startWriting(chosen);
         return true;
      }

      if (this.remaining.isEmpty()) {
         if (!this.pendingSaves.isEmpty()) {
            // Let the last saves land before tearing the sweep down, so the teardown diagnostics
            // and the "did it reach disk" check still mean something.
            return false;
         }

         this.teardown("complete");
         return false;
      }

      if (this.pendingSaves.size() >= MAX_PENDING_SAVES) {
         // Every unsaved region is still pinned. Wait rather than pin more.
         return false;
      }

      RegionSession session = this.beginRegion(mp);
      if (session == null) {
         return false;
      }

      this.startWriting(session);
      return true;
   }

   /** Per-region writing state. Deliberately untouched while a session is only preloading. */
   private void startWriting(RegionSession session) {
      this.pendingTileChunks.clear();

      LongOpenHashSet tileChunks = this.index.tileChunksIn(session.regionX, session.regionZ);

      if (tileChunks.isEmpty()) {
         // The index knows nothing here, which on a young database means "not flushed yet" rather
         // than "empty". Offer the whole region; the scanner probes by key and drops what is
         // genuinely absent.
         for (int ltcX = 0; ltcX < 8; ltcX++) {
            for (int ltcZ = 0; ltcZ < 8; ltcZ++) {
               tileChunks.add(SectionIndex.regionKey((session.regionX << 3) + ltcX, (session.regionZ << 3) + ltcZ));
            }
         }
      }

      for (long tc : tileChunks) {
         if (this.tileChunkFilter == null || this.tileChunkFilter.contains(tc)) {
            this.pendingTileChunks.add(tc);
         }
      }

      // It asked from the back of the queue while it was only a preload; now everything waits
      // on it.
      session.prioritiseLoad();

      // Starts the stall clock. Until now the session may have been sitting loaded in the preload
      // queue, and time spent queueing is not time Xaero spent refusing us.
      session.beginWriting();

      this.authored.open(session.regionX, session.regionZ);

      // Voxy's data under this region moved since we last wrote it, so whatever we authored from
      // the old data may be wrong -- not missing, wrong, which is worse because a tile that exists
      // is never revisited. Replace our own tiles here; Xaero's are still refused.
      this.writer.setRegionStale(this.staleRegions.contains(SectionIndex.regionKey(session.regionX, session.regionZ)));

      this.writer.resetCounters();
      this.scanner.resetCounters();
      this.regionStartedAt = session.createdAt();
      this.current = session;
   }

   private void requeueRegion(RegionSession session) {
      long key = SectionIndex.regionKey(session.regionX, session.regionZ);
      int attempts = this.retries.merge(key, 1, Integer::sum);

      if (attempts <= MAX_REGION_RETRIES) {
         this.remaining.add(key);
         this.retryNotBefore.put(key, System.currentTimeMillis() + RETRY_COOLDOWN_MILLIS);
      } else {
         this.regionsDone++;
         Log.warn(
            "r(" + session.regionX + "," + session.regionZ + ") giving up after " + MAX_REGION_RETRIES + " attempts: " + session.failure()
         );
      }
   }

   /**
    * Watches the forced saves the sweep no longer waits on.
    *
    * <p>The list is also the memory bound that {@code FLUSHING} used to provide: a region stays
    * pinned by {@code beingWritten} until its save lands, so letting an unbounded number pile up
    * would pin an unbounded number of regions. Past the cap the sweep simply stops opening new
    * ones, which is the same backpressure the blocking wait gave -- just only when it is needed
    * rather than on every single region.
    */
   private void pollPendingSaves() {
      long now = System.currentTimeMillis();

      this.pendingSaves.removeIf(session -> {
         if (session.saveFinished()) {
            if (session.reportOnSave && session.region() != null) {
               Log.diag("  r(" + session.regionX + "," + session.regionZ + ") " + Diagnostics.saveFile(session.region()));
            }

            return true;
         }

         if (now - session.flushRequestedAt() > session.saveTimeoutMillis()) {
            Log.warn(
               "r(" + session.regionX + "," + session.regionZ + ") SAVE_TIMEOUT after "
                  + (now - session.flushRequestedAt()) / 1000L
                  + "s; the data is still in memory and queued, continuing. "
                  + (session.region() == null ? "<no region>" : Diagnostics.regionState(session.region()))
            );
            return true;
         }

         return false;
      });
   }

   /**
    * Takes the next region and starts it loading. Nothing is written to it here.
    *
    * @return the new session, or null if the sweep should pause instead -- a palette refresh, or a
    *     region Voxy turned out to have nothing in
    */
   private RegionSession beginRegion(MapProcessor mp) {
      if (this.config.paletteRefreshRegions > 0
         && this.regionsDone > 0
         && this.regionsDone % this.config.paletteRefreshRegions == 0
         && this.voxy.engine().getMapper().getStateEntries().length != this.palette.blockCount()) {
         // Voxy ingested new blocks while we were running; re-snapshot before continuing.
         this.paletteBuilder = PaletteSnapshot.builder(this.voxy.engine(), mp, mp.getWorld());
         this.paletteIsRefresh = true;
         this.phase = Phase.PALETTE;
         return null;
      }

      long key = this.takeNearestRegion();
      int rx = SectionIndex.regionX(key);
      int rz = SectionIndex.regionZ(key);

      // Not checked for emptiness any more: tileChunksIn comes from the same index that cannot see
      // unflushed sections, so an empty answer does not mean an empty region. startWriting falls
      // back to every tile chunk in the region when the index has nothing.


      return new RegionSession(
         mp,
         rx,
         rz,
         this.config.loadTimeoutSeconds,
         this.config.settleTimeoutSeconds,
         this.config.saveTimeoutSeconds,
         this.config.rebuildBuffersInline
      );
   }

   private void tickApply(MapProcessor mp, RegionSession session) {
      if (this.config.pauseWhenFpsBelow > 0 && Minecraft.getInstance().getFps() < this.config.pauseWhenFpsBelow) {
         // Surfaced explicitly: otherwise a machine that never reaches the threshold just looks
         // like the sweep is broken.
         this.pauseReason = "framerate below " + this.config.pauseWhenFpsBelow + " (pauseWhenFpsBelow in the config)";
         return;
      }

      this.pauseReason = null;

      // Keep the worker fed without letting it run away from us.
      while (!this.pendingTileChunks.isEmpty()) {
         long tc = this.pendingTileChunks.peek();
         int tcX = SectionIndex.regionX(tc);
         int tcZ = SectionIndex.regionZ(tc);

         // Nothing to write here, so there is nothing worth reading either. This is what keeps a
         // sweep of an already-mapped world from taking minutes and starving the newly ingested
         // ground the player is actually walking through.
         if (!this.writer.reauthoring() && session.isFullyMapped(tcX, tcZ)) {
            this.pendingTileChunks.poll();
            this.writer.countFullyMapped();
            continue;
         }

         if (!this.worker.submit(tcX, tcZ)) {
            break;
         }

         this.pendingTileChunks.poll();
         this.inFlight++;
      }

      Minecraft mc = Minecraft.getInstance();
      LocalPlayer player = mc.player;
      int playerChunkX = player == null ? 0 : player.blockPosition().getX() >> 4;
      int playerChunkZ = player == null ? 0 : player.blockPosition().getZ() >> 4;

      long applyStartNanos = System.nanoTime();
      this.budget.start(this.config.applyBudgetMillis);

      while (!this.budget.exhausted()) {
         ColumnData data = this.stalled;
         this.stalled = null;

         if (data == null) {
            SweepWorker.Result result = this.worker.poll();
            if (result == null) {
               break;
            }

            this.inFlight--;
            data = result.data();
            if (data == null) {
               continue;
            }
         }

         int authoredBefore = this.writer.authored;

         if (!session.apply(data, this.writer)) {
            // Xaero is busy with this region; hold the payload and try again next tick.
            this.stalled = data;
            break;
         }

         if (this.writer.authored > authoredBefore) {
            this.recordAuthored(data);
         }
      }

      this.budget.finish(this.config.applyBudgetMillis);
      this.perf.recordApply(System.nanoTime() - applyStartNanos);

      if (this.pendingTileChunks.isEmpty() && this.inFlight == 0 && this.stalled == null && this.worker.queuedResults() == 0) {
         session.beginSettle();
      }
   }

   /**
    * Records that a region has been swept, region by region rather than only at the end.
    *
    * <p>Banking only on a completed sweep meant a sweep that was interrupted -- by disconnecting,
    * most obviously -- threw away everything it had done, so the next session started the same
    * full pass again and never converged. A region is finished the moment it closes, so that is
    * when it should count.
    *
    * @param deferredMissing chunks left to Xaero that have no tile yet. Any at all and the region
    *     is not finished, so its entry is dropped instead: Voxy stops changing a region once the
    *     player walks away, so a banked fingerprint would go stable and the rescan would never
    *     return to author those chunks after they unload. It settles by itself -- once the ground
    *     is drawn there is nothing deferred and the region goes quiet.
    */
   private void bankRegion(int rx, int rz, int deferredMissing) {
      // A tile-chunk-scoped sweep visited a slice of the region, so it cannot vouch for the rest.
      if (this.pendingFingerprints == null || this.tileChunkFilter != null) {
         return;
      }

      if (this.lastFingerprints == null) {
         this.lastFingerprints = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
         this.lastFingerprints.defaultReturnValue(0L);
      }

      long key = SectionIndex.regionKey(rx, rz);

      if (deferredMissing > 0) {
         this.lastFingerprints.remove(key);
      } else {
         this.lastFingerprints.put(key, this.pendingFingerprints.get(key));
      }

      this.fingerprintsDirty = true;
   }

   /**
    * Writes the baseline out.
    *
    * <p>Also called every {@link #FINGERPRINT_SAVE_INTERVAL_REGIONS} regions, not just at the end.
    * A disconnect tears the sweep down after Xaero's session has already gone, so there is no
    * processor left to derive the file path from -- saving only then would lose the whole pass
    * every time, which is precisely how a full sweep ends up repeating on every join.
    */
   private void persistFingerprints() {
      if (!this.fingerprintsDirty || this.lastFingerprints == null) {
         return;
      }

      MapProcessor mp = mpOrNull();
      if (mp == null) {
         return;
      }

      FingerprintStore.save(mp, this.lastFingerprints);
      this.fingerprintsDirty = false;
   }

   private void recordAuthored(ColumnData data) {
      for (int insideX = 0; insideX < 4; insideX++) {
         for (int insideZ = 0; insideZ < 4; insideZ++) {
            this.authored.mark((data.tileChunkX << 2) + insideX, (data.tileChunkZ << 2) + insideZ);
         }
      }
   }

   private void closeRegion(boolean requeue) {
      RegionSession session = this.current;
      this.current = null;
      this.stalled = null;
      this.inFlight = 0;

      if (session == null) {
         return;
      }

      if (this.worker != null) {
         this.worker.bumpGeneration();
      }

      if (requeue) {
         if (this.authored != null) {
            this.authored.close();
         }

         this.requeueRegion(session);
         return;
      }

      this.regionsDone++;
      this.chunksAuthored += this.writer.authored;
      this.chunksExisting += this.writer.existing;
      this.chunksIncomplete += this.writer.skippedIncomplete;
      this.chunksSkippedLoaded += this.writer.skippedLoaded;

      if (this.scanner != null) {
         this.columnsVoid += this.scanner.voidColumns;
         this.columnsEstimated += this.scanner.estimatedColumns;
         this.columnsNoData += this.scanner.noDataColumns;
         this.columnsGap += this.scanner.gapColumns;
         this.columnsShallow += this.scanner.shallowColumns;
         this.columnsFilled += this.scanner.filledColumns;
         this.columnsProbed += this.scanner.probedSections;
      }

      this.noteProvisional(session.regionX, session.regionZ);
      this.bankRegion(session.regionX, session.regionZ, this.writer.deferredMissing);

      if (this.regionsDone % FINGERPRINT_SAVE_INTERVAL_REGIONS == 0) {
         this.persistFingerprints();
      }

      if (this.authored != null) {
         this.authored.close();
      }

      if (session.region() != null && session.flushRequestedAt() != 0L) {
         this.pendingSaves.add(session);
      }

      if (this.config.verbose) {
         int considered = this.writer.authored + this.writer.existing + this.writer.skippedIncomplete + this.writer.skippedLoaded;
         Log.dev(
            String.format(
               "r(%d,%d)%s %dms [load=%d apply=%d settle=%d] tiles: existing=%d authored=%d incomplete=%d loaded=%d(%d blank) est=%d coverage=%d/%d rebuild=%.1fms%s%s",
               session.regionX,
               session.regionZ,
               this.writer.reauthoring() ? " rewrite" : "",
               System.currentTimeMillis() - this.regionStartedAt,
               session.loadWaitMillis,
               session.applyMillis,
               session.settleWaitMillis,
               this.writer.existing,
               this.writer.authored,
               this.writer.skippedIncomplete,
               this.writer.skippedLoaded,
               this.writer.deferredMissing,
               this.scanner == null ? 0 : this.scanner.estimatedColumns,
               this.writer.authored + this.writer.existing,
               Math.max(considered, 1),
               session.bufferRebuildNanos / 1_000_000.0,
               session.settleTimedOut ? " SETTLE_TIMEOUT(pending=" + session.notUploadedAtFlush + ")" : "",
               session.failure() == null ? "" : " failure=" + session.failure()
            )
         );
      }

      // The state at flush is the whole ball game for "did it actually render". Log it for the
      // first region of a sweep as a baseline, and thereafter only for regions that look wrong.
      // The "did it persist" half moved to reportSaved, since the save no longer happens before
      // this point.
      MapRegion r = session.region();
      boolean anomalous = session.settleTimedOut || session.failure() != null;

      if (r != null && this.writer.authored > 0 && (anomalous || this.regionsDone <= 1)) {
         session.reportOnSave = true;
         Log.diag("  r(" + session.regionX + "," + session.regionZ + ") at flush: " + Diagnostics.regionState(r));
         Log.diag("  r(" + session.regionX + "," + session.regionZ + ") " + Diagnostics.tileChunkSummary(r));

         String identity = Diagnostics.identity(mpOrNull(), r);
         Log.diag("  r(" + session.regionX + "," + session.regionZ + ") ids: " + identity);
         if (identity.endsWith("match=false")) {
            // MapSaveLoad's drain calls region.clearRegion(...) instead of keeping the data when
            // these diverge. In multiplayer the multiworld id can be confirmed mid-session.
            Log.warn(
               "r(" + session.regionX + "," + session.regionZ + ") IDENTITY MISMATCH -- Xaero will clear this region"
                  + " after saving instead of keeping it. This is the most likely cause of data never appearing."
            );
         }
      }

      if (this.writer.authored == 0 && this.writer.existing == 0 && this.writer.skippedLoaded == 0 && this.writer.skippedIncomplete > 0) {
         Log.warn(
            "r(" + session.regionX + "," + session.regionZ + ") produced nothing: Voxy's vertical data was incomplete for every chunk"
         );
      }
   }

   /**
    * Periodic progress, to the log rather than the HUD.
    *
    * <p>The action bar version of this was clutter: a sweep is background work the player did not
    * ask for on any particular tick, and it now runs automatically, so it would be on screen most
    * of the time. Everything it showed is in /voxymap status on demand.
    */
   private void maybeShowProgress() {
      long now = System.currentTimeMillis();
      if (now - this.lastProgressMessage < PROGRESS_LOG_INTERVAL_MILLIS) {
         return;
      }

      this.lastProgressMessage = now;

      if (this.phase != Phase.RUNNING) {
         return;
      }

      Log.dev(
         String.format(
            "progress: regions %d/%d | authored %d existing %d incomplete %d | queue %d/%d | %s",
            this.regionsDone,
            this.regionsTotal,
            this.chunksAuthored,
            this.chunksExisting,
            this.chunksIncomplete,
            this.worker == null ? 0 : this.worker.queuedResults(),
            this.worker == null ? 0 : this.worker.resultCapacity(),
            this.perf.summary()
         )
      );
   }

   // ----------------------------------------------------------------- status

   public List<String> status() {
      List<String> out = new ArrayList<>();
      Minecraft mc = Minecraft.getInstance();
      ClientLevel level = mc.level;
      MapProcessor mp = XaeroBridge.processor();

      out.add("phase: " + this.phase + (this.pauseReason == null ? "" : " (paused: " + this.pauseReason + ")"));
      out.add(
         "auto: " + (this.config.autoStart ? this.autoSuspended ? "suspended (/voxymap auto on to resume)" : "on" : "disabled in config")
            + (this.lastFingerprints == null ? ", no baseline yet" : ", baseline of " + this.lastFingerprints.size() + " regions")
      );

      if (level == null) {
         out.add("world: none");
      } else {
         var engine = this.voxy.isOpen() ? this.voxy.engine() : VoxySource.peek(level);
         out.add("voxy engine: " + (engine == null ? "ABSENT for this dimension" : "present"));
         out.add("world y range: " + level.getMinY() + " .. " + (level.getMaxY() + 1));
      }

      out.add(this.voxyGrowth());

      if (this.index != null) {
         out.add("indexed: " + this.index.sectionCount() + " LOD-0 sections, " + this.index.columnCount() + " columns");
      }

      if (mp == null) {
         out.add("xaero: no session");
      } else {
         String why = XaeroBridge.whyNotWritable(mp);
         out.add("xaero writable: " + (why == null ? "yes" : "NO -- " + why));
         out.add("region detection complete: " + mp.getMapSaveLoad().isRegionDetectionComplete());
         out.add("multiworld writable: " + mp.isCurrentMultiworldWritable());
         out.add("map locked: " + mp.isCurrentMapLocked());
         out.add("cache-only: " + (mp.getMapWorld() == null ? "?" : mp.getMapWorld().isCacheOnlyMode()));
         out.add("using world save: " + XaeroBridge.isUsingWorldSave(mp));
         out.add("surface cave start: " + XaeroBridge.surfaceCaveStart(mp));

         // The boundary between "Xaero's ground" and "ours". Chunks that fall in neither are the
         // black holes this line exists to make visible.
         int writeDistance = XaeroCoverage.writeDistance(mp);
         out.add(
            "xaero write distance: " + (writeDistance == XaeroCoverage.UNKNOWN ? "unknown -- we author everything" : writeDistance + " chunks")
               + " (minus a one-chunk edge ring Xaero never writes)"
               + ", loadNewChunks=" + XaeroCoverage.willCreateNewTiles(mp)
               + " updateChunks=" + XaeroCoverage.willUpdateExistingTiles(mp)
         );
         out.add("world/dim/mw id: " + mp.getCurrentWorldId() + " / " + mp.getCurrentDimId() + " / " + mp.getCurrentMWId());
      }

      if (this.phase != Phase.IDLE) {
         out.add("regions: " + this.regionsDone + "/" + this.regionsTotal);
         out.add(
            "chunks: authored=" + this.chunksAuthored + " existing=" + this.chunksExisting + " incomplete=" + this.chunksIncomplete
               + " alreadyLoaded=" + this.chunksSkippedLoaded
         );
         out.add("elapsed: " + (System.currentTimeMillis() - this.startedAt) / 1000L + "s");
         if (this.current != null) {
            out.add("current region: r(" + this.current.regionX + "," + this.current.regionZ + ") " + this.current.state());
         }
      }

      out.add("reclaimed near player (this session): " + this.reclaim.reclaimed);

      if (dev.local.voxymap.MapWipe.armed()) {
         out.add("WIPE ARMED -- disconnect to erase this world's map data.");
      }

      if (this.phase != Phase.IDLE) {
         out.add("columns: " + this.columnSummary());
         out.add("preloading: " + this.preloading.size() + " | pending saves: " + this.pendingSaves.size()
            + " | maxOpenRegions=" + this.config.maxOpenRegions);
      }

      if (this.phase != Phase.IDLE && this.worker != null) {
         out.add("handoff queue: " + this.worker.queuedResults() + "/" + this.worker.resultCapacity());
         out.add("last apply tick: " + String.format("%.2f ms", this.budget.lastSpentMillis()));
      }

      out.add(this.perf.summary());
      return out;
   }
}
