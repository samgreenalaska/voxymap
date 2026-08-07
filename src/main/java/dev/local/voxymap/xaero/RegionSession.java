package dev.local.voxymap.xaero;

import dev.local.voxymap.util.Log;
import dev.local.voxymap.voxy.ColumnData;
import xaero.map.MapProcessor;
import xaero.map.region.MapRegion;
import xaero.map.region.MapTileChunk;
import xaero.map.region.MapUpdateFastConfig;

/**
 * Owns exactly one {@link MapRegion} from open to flush. Main thread only.
 *
 * <p>The single most important rule in this whole mod lives here: <em>never write into a region
 * whose load state is not 2</em>. A region created by {@code getLeafMapRegion(..., true)} starts
 * at load state 0 with an empty tile array. Writing into it would shadow whatever is on disk and
 * then overwrite it at the next save -- the one genuinely unrecoverable failure mode.
 *
 * <p>Getting a region to load state 2 takes two passes through Xaero's loader, because
 * {@code shouldLoadProperly} is {@code loadState == 4 && isBeingWritten}: the first request walks
 * 0 -> 3 -> 4, the second actually reads the tiles. That is exactly what {@code MapWriter} does
 * every frame, so we do the same thing and simply wait.
 *
 * <p>The state machine is OPENING -> LOADING -> APPLYING -> SETTLING -> DONE. SETTLING is what
 * makes the tiles appear live: see DIAGNOSIS.md. Briefly, {@code beingWritten} is the only thing
 * keeping a region from being demoted and cleaned by {@code LeafRegionTexture.postUpload}, and
 * forcing the save clears it -- so flushing before the textures have uploaded strands every tile
 * chunk that had not been rebuilt yet. The forced save itself is fire-and-forget; the controller
 * watches for it landing out of band.
 */
public final class RegionSession {
   public enum State {
      OPENING,
      LOADING,
      APPLYING,
      SETTLING,
      DONE,
      RETRY,
      FAILED
   }

   public final int regionX;
   public final int regionZ;

   private final MapProcessor mp;
   private final long loadTimeoutMillis;
   private final long settleTimeoutMillis;
   private final long saveTimeoutMillis;
   private final boolean rebuildBuffersInline;

   private MapRegion region;
   private State state = State.OPENING;
   private long stateSince = System.currentTimeMillis();

   /** When this session became the one being written to, or 0 while it is still only a preload. */
   private long writingSince;
   private final long createdAt = System.currentTimeMillis();
   private String failure;

   /** Which of the region's 64 tile chunks we touched, indexed localX * 8 + localZ. */
   private final boolean[] touched = new boolean[64];

   public int tilesAuthored;
   public int tileChunksApplied;
   public long bufferRebuildNanos;

   // Diagnostics captured along the way, so the per-region log line can explain itself.
   public int loadStateAtOpen = -1;
   public int settleWaitMillis;
   // Per-phase wall time. A region that authors nothing still costs about a second, and these
   // are what say which phase that second is going into.
   public int loadWaitMillis;
   public int applyMillis;
   public int notUploadedAtFlush;
   public boolean settleTimedOut;

   /** When the forced save was requested, for the controller's out-of-band completion watch. */
   private long flushRequestedAt;

   /** Set by the controller when this region's save is worth reporting once it lands. */
   public boolean reportOnSave;

   public RegionSession(
      MapProcessor mp, int regionX, int regionZ, int loadTimeoutSeconds, int settleTimeoutSeconds, int saveTimeoutSeconds, boolean rebuildBuffersInline
   ) {
      this.mp = mp;
      this.regionX = regionX;
      this.regionZ = regionZ;
      this.loadTimeoutMillis = loadTimeoutSeconds * 1000L;
      this.settleTimeoutMillis = settleTimeoutSeconds * 1000L;
      this.saveTimeoutMillis = saveTimeoutSeconds * 1000L;
      this.rebuildBuffersInline = rebuildBuffersInline;
   }

   public State state() {
      return this.state;
   }

   public String failure() {
      return this.failure;
   }

   public MapRegion region() {
      return this.region;
   }

   /** When the session was created, which is when its load started -- possibly well before it
    * became the region being written to. The per-region timing is measured from here. */
   public long createdAt() {
      return this.createdAt;
   }

   private void moveTo(State next) {
      this.state = next;
      this.stateSince = System.currentTimeMillis();
   }

   private long inStateMillis() {
      return System.currentTimeMillis() - this.stateSince;
   }

   /**
    * Time spent actually being written to, as opposed to time spent in {@code APPLYING}.
    *
    * <p>A preload reaches {@code APPLYING} when it finishes loading and then queues, so
    * {@link #inStateMillis()} counts the wait as work. That inflated every {@code apply=} figure
    * for a preloaded region and, worse, drove {@link #applyStalled()}.
    */
   private long writingMillis() {
      long from = this.writingSince == 0L ? this.stateSince : Math.max(this.writingSince, this.stateSince);
      return System.currentTimeMillis() - from;
   }

   /**
    * Re-asserts the pin on a region that has finished loading but is not being written to yet.
    *
    * <p>With pipelining, a session can sit in {@code APPLYING} for a whole region's worth of time
    * before it is promoted. {@code beingWritten} is what keeps {@code postUpload} from demoting and
    * cleaning it in the meantime, and {@code registerVisit} is the same insurance {@code pollSettle}
    * takes. Cheap, and the failure it prevents is a region that silently stops being writable.
    */
   public void keepAlive() {
      MapRegion r = this.region;

      if (r == null || this.state != State.APPLYING) {
         return;
      }

      synchronized (r) {
         r.registerVisit();
         r.setBeingWritten(true);
      }
   }

   /**
    * Drives OPENING and LOADING. Returns true once the region is ready to be written to.
    *
    * @param prioritise whether this region is the one the sweep is actually waiting on. See
    *     {@link #doLoad} -- {@code addToLoad} with priority moves a region to index 0 of Xaero's
    *     load queue, so the <em>last</em> prioritised request wins. Preloads must not use it or
    *     they push the region we are blocked on further back every time one starts.
    */
   public boolean prepare(boolean prioritise) {
      switch (this.state) {
         case OPENING:
            this.doOpen(prioritise);
            return this.state == State.APPLYING;
         case LOADING:
            this.doLoad(prioritise);
            return this.state == State.APPLYING;
         case APPLYING:
            return true;
         default:
            return false;
      }
   }

   /**
    * Jumps this region to the front of Xaero's load queue, ignoring the usual re-request gate.
    *
    * <p>Called when a session that was only preloading becomes the one being written to: it asked
    * politely from the back of the queue, and now the whole sweep is waiting on it.
    * {@code addToLoad} with priority is idempotent -- it removes and re-inserts at index 0.
    */
   public void prioritiseLoad() {
      MapRegion r = this.region;

      if (r == null || this.state != State.LOADING) {
         return;
      }

      synchronized (r) {
         if (r.getLoadState() != 2) {
            r.setBeingWritten(true);
            this.mp.getMapSaveLoad().requestLoad(r, "voxymap", true);
         }
      }
   }

   private void doOpen(boolean prioritise) {
      MapRegion r;

      try {
         // create = true is safe here and only here: we are on the main thread, which is what
         // getLeafMapRegion asserts before it will fabricate a region.
         r = this.mp.getLeafMapRegion(XaeroBridge.SURFACE_LAYER, this.regionX, this.regionZ, true);
      } catch (Throwable t) {
         this.failure = "getLeafMapRegion threw: " + t;
         Log.warn("r(" + this.regionX + "," + this.regionZ + ") could not be opened", t);
         this.moveTo(State.FAILED);
         return;
      }

      if (r == null) {
         // Region detection has not finished. Try again later rather than giving up.
         this.failure = "region detection incomplete";
         this.moveTo(State.RETRY);
         return;
      }

      this.region = r;
      this.loadStateAtOpen = r.getLoadState();
      this.moveTo(State.LOADING);
      this.doLoad(prioritise);
   }

   private void doLoad(boolean prioritise) {
      MapRegion r = this.region;

      if (r.getLoadState() == 2 && r.isResting()) {
         this.loadWaitMillis = (int)this.inStateMillis();
         this.moveTo(State.APPLYING);
         return;
      }

      if (this.inStateMillis() > this.loadTimeoutMillis) {
         this.failure = "LOAD_TIMEOUT (" + Diagnostics.regionState(r) + ")";
         Log.warn("r(" + this.regionX + "," + this.regionZ + ") " + this.failure + ", requeuing");
         this.moveTo(State.RETRY);
         return;
      }

      // MapSaveLoad.run's toLoad loop stops after one *successful* region load per pass, and
      // MapProcessor.run sleeps 40-100 ms between passes -- so queue position is most of the load
      // time, and a region needs two passes to reach load state 2.
      //
      // Priority is not free to hand out. addToLoad(prioritize = true) does
      // `toLoad.remove(region); toLoad.add(0, region)`, so the most recent prioritised request
      // wins and everything else shuffles back. With several sessions in flight, prioritising all
      // of them means each new preload displaces the region the sweep is actually blocked on --
      // which is exactly backwards, and it is why load times stayed high when pipelining and
      // prioritised loads first landed together. Only the region being waited on asks for
      // priority; preloads take the tail and get there in their own time.
      //
      // canRequestReload_unsynced keeps this from spamming the queue every tick.
      synchronized (r) {
         if (r.canRequestReload_unsynced() && r.getLoadState() != 2) {
            r.setBeingWritten(true);
            this.mp.getMapSaveLoad().requestLoad(r, "voxymap", prioritise);
         }
      }
   }

   /**
    * Whether every one of a tile chunk's 16 tiles is already present and loaded.
    *
    * <p>Asked before the tile chunk is handed to the worker, because scanning it would be pure
    * waste: {@code TileWriter} would read all four LOD-0 stacks, walk 4096 columns, allocate a
    * {@code ColumnData}, pass it across the queue, and then decline to write a single tile. On an
    * already-mapped world that is essentially the entire cost of a sweep -- it was 850 ms of the
    * ~1.2 s each fully-mapped region took, and with ~300 regions that is five minutes during which
    * newly ingested Voxy ground cannot be picked up at all.
    *
    * <p>Conservative: anything other than a definite "all sixteen are there" answers false and the
    * tile chunk is scanned as before.
    */
   public boolean isFullyMapped(int tileChunkX, int tileChunkZ) {
      MapRegion r = this.region;
      if (r == null || this.state != State.APPLYING) {
         return false;
      }

      MapTileChunk tc;

      synchronized (r) {
         if (r.getLoadState() != 2 || !r.isResting() || r.isRefreshing()) {
            return false;
         }

         tc = r.getChunk(tileChunkX & 7, tileChunkZ & 7);
      }

      if (tc == null || tc.getLoadState() != 2) {
         return false;
      }

      for (int insideX = 0; insideX < 4; insideX++) {
         for (int insideZ = 0; insideZ < 4; insideZ++) {
            if (!ScannedProbe.exists(tc, insideX, insideZ)) {
               return false;
            }
         }
      }

      return true;
   }

   /**
    * Applies one scanned tile chunk.
    *
    * @return true if it was written; false means "not right now" and the caller should stop
    *     applying for this tick without dropping the payload
    */
   public boolean apply(ColumnData data, TileWriter writer) {
      MapRegion r = this.region;
      if (r == null || this.state != State.APPLYING) {
         return false;
      }

      int localTcX = data.tileChunkX & 7;
      int localTcZ = data.tileChunkZ & 7;

      // Lock order copied verbatim from MapWriter: processor pause, then region writer pause,
      // then the region itself for structural changes.
      synchronized (this.mp.renderThreadPauseSync) {
         if (this.mp.isWritingPaused() || this.mp.isWaitingForWorldUpdate()) {
            return false;
         }

         synchronized (r.writerThreadPauseSync) {
            if (r.isWritingPaused()) {
               return false;
            }

            MapTileChunk tc;
            boolean created = false;

            synchronized (r) {
               if (r.getLoadState() != 2 || !r.isResting() || r.isRefreshing()) {
                  return false;
               }

               r.registerVisit();
               r.setBeingWritten(true);
               tc = r.getChunk(localTcX, localTcZ);
               if (tc == null) {
                  tc = new MapTileChunk(r, data.tileChunkX, data.tileChunkZ);
                  r.setChunk(localTcX, localTcZ, tc);
                  tc.setLoadState((byte)2);
                  r.setAllCachePrepared(false);
                  created = true;
               }
            }

            if (tc.getLoadState() != 2) {
               return false;
            }

            if (tc.getLeafTexture().shouldDownloadFromPBO()) {
               // Xaero is mid-readback on this texture; come back to it later.
               return false;
            }

            int wrote = writer.writeTileChunk(tc, data);
            this.tilesAuthored += wrote;
            this.tileChunksApplied++;

            if (wrote > 0) {
               tc.setChanged(true);
               tc.setHasHadTerrain();
               this.touched[localTcX * 8 + localTcZ] = true;

               // Flag it either way, so that if the inline rebuild below fails or is disabled,
               // Xaero's own render-thread pass still picks it up.
               tc.setToUpdateBuffers(true);

               if (this.rebuildBuffersInline) {
                  this.rebuildBuffers(tc);
               }
            }

            if (created) {
               if (tc.includeInSave()) {
                  tc.setHasHadTerrain();
               }

               this.mp.getMapRegionHighlightsPreparer().prepare(r, localTcX, localTcZ, false);

               if (!tc.includeInSave() && !tc.hasHighlightsIfUndiscovered()) {
                  // We created it and then wrote nothing; do not leave an empty shell behind.
                  synchronized (r) {
                     r.setChunk(localTcX, localTcZ, null);
                  }
               }
            }

            return true;
         }
      }
   }

   /**
    * Rebuilds the tile chunk's texture immediately, the way {@code MapWriter.writeChunk} does for
    * the tile chunk it has just finished.
    *
    * <p>Relying on the deferred flag alone is what stranded the tiles: Xaero's render-thread pass
    * only gets {@code timeAvailable / 4} per frame across every region, and the region stops being
    * processed at all once it is demoted -- which our own forced save triggers. Doing it here puts
    * the rebuild under our tick budget instead, where we control it.
    */
   private void rebuildBuffers(MapTileChunk tc) {
      long start = System.nanoTime();

      try {
         tc.updateBuffers(
            this.mp,
            this.mp.getWorldBlockTintProvider(),
            this.mp.getOverlayManager(),
            false,
            this.mp.getBlockStateShortShapeCache(),
            new MapUpdateFastConfig(this.mp)
         );
         tc.setChanged(false);
      } catch (Throwable t) {
         // Leave toUpdateBuffers set so Xaero's own pass can retry.
         Log.warn("r(" + this.regionX + "," + this.regionZ + ") inline buffer rebuild failed for tile chunk " + tc.getX() + "," + tc.getZ(), t);
      }

      this.bufferRebuildNanos += System.nanoTime() - start;
   }

   /**
    * Whether applying has been stuck for so long that the region is not coming back.
    *
    * <p>{@link #apply} answers "not right now" for a whole family of transient Xaero states, and
    * the controller correctly just tries again next tick. But if one of those states is not
    * transient -- the region demoted out from under us, say -- nothing ever advances and the sweep
    * wedges silently. The load timeout is a reasonable bound: a region takes well under a second of
    * applying in practice.
    */
   /**
    * Marks this session as the one the sweep is now writing to.
    *
    * <p>The distinction matters because a session reaches {@code APPLYING} as soon as Xaero has
    * loaded it, which for a preload is well before anything is written to it -- see
    * {@code SweepController.tickPreload}, where a loaded session waits "a whole region's worth of
    * time" for its turn. Timing the stall from {@code stateSince} therefore measured how long the
    * region queued, not how long it was refused.
    */
   public void beginWriting() {
      this.writingSince = System.currentTimeMillis();
   }

   /**
    * Whether Xaero has refused every tile chunk for the whole timeout.
    *
    * <p>Timed from the later of "became the session being written" and "finished loading", so a
    * region that sat in the preload queue is judged on the time it was actually offered work. It
    * used to be timed from entry to {@code APPLYING}, which meant a slow region in front of it
    * could exhaust the timeout on its behalf: the sweep then closed and requeued it before a single
    * tile chunk was handed over, burnt its three retries the same way, and gave up on it entirely.
    * That is where the bursts of identical-timestamp warnings came from -- one per parked preload,
    * drained on consecutive ticks.
    */
   public boolean applyStalled() {
      if (this.state != State.APPLYING || this.writingSince == 0L || this.tileChunksApplied != 0) {
         return false;
      }

      return System.currentTimeMillis() - Math.max(this.writingSince, this.stateSince) > this.loadTimeoutMillis;
   }

   /** Applying is finished; hold the region open until its textures have actually uploaded. */
   public void beginSettle() {
      if (this.state != State.APPLYING) {
         return;
      }

      if (this.tilesAuthored == 0) {
         // Nothing was written, so there is nothing to render or force out to disk.
         //
         // Deliberately do NOT clear beingWritten here. MapSaveLoad's drain throws
         // "Saving a weird region" if a queued region is not beingWritten, and updateSave runs on
         // the processor thread -- so clearing it from the main thread can race a region that was
         // enqueued a moment earlier and take Xaero's whole map down with it.
         //
         // Leaving it set is exactly what MapWriter does; Xaero's own 60s save cycle will pick
         // the region up and clear the flag itself.
         this.applyMillis = (int)this.writingMillis();
         this.moveTo(State.DONE);
         return;
      }

      // Request the refresh BEFORE settling, not after.
      //
      // handleRefresh re-runs setTile across the region (repopulating the leaf texture's height
      // and biome grids), re-flags every tile chunk for a buffer update, and sets shouldCache +
      // recacheHasBeenRequested -- which is what regenerates the zoomed-out branch textures.
      // Doing it after the settle left ~60 of 64 tile chunks freshly dirtied at the exact moment
      // beingWritten was cleared, which is the same stranding window all over again. Settling
      // afterwards means everything really is uploaded before we let go of the region.
      this.applyMillis = (int)this.writingMillis();

      MapRegion r = this.region;
      if (r == null) {
         this.moveTo(State.DONE);
         return;
      }

      try {
         r.requestRefresh(this.mp, false);
      } catch (Throwable t) {
         Log.warn("r(" + this.regionX + "," + this.regionZ + ") could not request a refresh", t);
      }

      this.moveTo(State.SETTLING);
   }

   /** @return true once the region is ready to be flushed. */
   public boolean pollSettle() {
      MapRegion r = this.region;
      if (r == null) {
         this.moveTo(State.DONE);
         return true;
      }

      // Keep the region pinned. Without this, postUpload demotes it to load state 3 and cleans
      // the tile chunks out from under us the moment a second passes since the last visit.
      synchronized (r) {
         r.registerVisit();
         r.setBeingWritten(true);
      }

      int pending = 0;

      // The refresh re-flags every tile chunk, so wait it out before judging "uploaded".
      if (r.isRefreshing()) {
         pending++;
      }

      for (int i = 0; i < 8; i++) {
         for (int j = 0; j < 8; j++) {
            if (!this.touched[i * 8 + j]) {
               continue;
            }

            MapTileChunk tc = r.getChunk(i, j);
            if (tc == null) {
               continue;
            }

            try {
               if (!tc.getLeafTexture().isUploaded()) {
                  pending++;
               }
            } catch (Throwable ignored) {
            }
         }
      }

      if (pending == 0) {
         this.settleWaitMillis = (int)this.inStateMillis();
         this.beginFlush();
         return true;
      }

      if (this.inStateMillis() > this.settleTimeoutMillis) {
         this.settleWaitMillis = (int)this.inStateMillis();
         this.notUploadedAtFlush = pending;
         this.settleTimedOut = true;
         Log.warn(
            "r(" + this.regionX + "," + this.regionZ + ") SETTLE_TIMEOUT: " + pending
               + " tile chunk textures never uploaded; flushing anyway. " + Diagnostics.regionState(r)
         );
         this.beginFlush();
         return true;
      }

      return false;
   }

   /**
    * Asks Xaero to save the region, and stops there.
    *
    * <p>Waiting for the save to land cost 260-680 ms per region -- a quarter of the whole sweep --
    * and bought nothing. The tiles are in memory and the region is queued; nothing about the next
    * region depends on the previous one reaching disk, and {@code MapWriter} never waits either.
    * The wait is now the controller's {@code PendingSave} list, which watches for the save
    * completing without holding the sweep up. That also keeps the memory bound honest, because it
    * is what stops an unbounded number of regions sitting mid-save.
    */
   private void beginFlush() {
      MapRegion r = this.region;
      if (r == null) {
         this.moveTo(State.DONE);
         return;
      }

      // updateSave only enqueues once currentTime - lastSaveTime >= saveTime (60s, or 10s with
      // force-fast-writing). Zeroing it makes the region eligible on the very next tick.
      r.setLastSaveTime(0L);
      this.flushRequestedAt = System.currentTimeMillis();
      this.moveTo(State.DONE);
   }

   /**
    * Whether Xaero has finished writing the region out.
    *
    * <p>{@code MapSaveLoad.saveRegion} clears {@code beingWritten} itself once the write succeeded,
    * and if the region saved empty Xaero deletes the file and drops the region instead -- so a
    * region that is no longer the one the processor holds is also finished.
    */
   public boolean saveFinished() {
      MapRegion r = this.region;
      if (r == null || !r.isBeingWritten()) {
         return true;
      }

      try {
         if (this.mp.getLeafMapRegion(XaeroBridge.SURFACE_LAYER, this.regionX, this.regionZ, false) != r) {
            return true;
         }
      } catch (Throwable ignored) {
      }

      // Keep nudging in case the processor tick raced past our first zeroing.
      r.setLastSaveTime(0L);
      return false;
   }

   public long flushRequestedAt() {
      return this.flushRequestedAt;
   }

   public long saveTimeoutMillis() {
      return this.saveTimeoutMillis;
   }

   /** Emergency teardown on abort: stop pinning the region but leave its data intact. */
   public void abandon() {
      MapRegion r = this.region;
      if (r != null && this.tilesAuthored > 0) {
         r.setLastSaveTime(0L);
      }

      this.region = null;
      this.moveTo(State.DONE);
   }
}
