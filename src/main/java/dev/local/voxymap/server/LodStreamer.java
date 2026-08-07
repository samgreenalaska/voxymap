package dev.local.voxymap.server;

import dev.local.voxymap.VoxyMapConfig;
import dev.local.voxymap.net.LodHash;
import dev.local.voxymap.net.LodProtocol;
import dev.local.voxymap.net.SectionCodec;
import dev.local.voxymap.util.Log;
import dev.local.voxymap.util.RadialOffsets;
import dev.local.voxymap.voxy.SectionIndex;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Sends stored LOD to the players near it, minus whatever they already have.
 *
 * <p>Each ready player gets a nearest-first walk outward through the section columns around them,
 * using the same {@link RadialOffsets} table as chunk generation, so the LOD front is a disc rather
 * than a square and the ground closest to the player always arrives first.
 *
 * <p>What is sent is whatever the server's own engine has already stored -- {@link ChunkIngest}
 * fills that as chunks load, and {@link ChunkPregen} keeps loading chunks past view distance. This
 * class never voxelizes anything itself; it reads, encodes and posts.
 *
 * <h2>Not sending the same section twice</h2>
 *
 * <p>Protocol 1 kept a per-player set of section keys in server memory. It was right about the
 * danger it was avoiding -- a persistent server-side record of what a client has is what made a wipe
 * unrecoverable in §7.16 -- and wrong about the only way to avoid it. Dying on disconnect meant
 * every reconnect re-sent the entire working set, measured at 412 MB in one session and 1.1 GB in
 * another. Being a set rather than a comparison meant a section, once sent, was never reconsidered:
 * build something outside view distance and the far view of it stayed frozen until the player
 * reconnected.
 *
 * <p>Both come from asking the wrong question. "Have I sent this?" is server bookkeeping and can be
 * wrong in the direction that loses ground. "Does the client's copy differ from mine?" is a
 * comparison of two content hashes, and it answers the reconnect case and the staleness case with
 * the same test:
 *
 * <ul>
 *   <li>the client declares a hash per column at join, rolled up per region ({@link LodProtocol});
 *   <li>{@link LodContentIndex} hashes the server's own columns and notices when they change;
 *   <li>a column is sent when the two hashes differ, and not otherwise.
 * </ul>
 *
 * <p>Nothing here remembers anything across a disconnect. {@link PlayerState} still dies with the
 * connection; what replaced the lost knowledge is the client saying what it holds, which is the only
 * end that can know.
 */
public final class LodStreamer {
   private final VoxyMapConfig config;
   private final Map<UUID, PlayerState> players = new HashMap<>();
   private final Map<WorldIdentifier, LodContentIndex> indexes = new HashMap<>();

   private int tickCounter;
   private long sectionsSent;
   private long bytesSent;
   private long columnsScanned;
   private long columnsFound;
   private long columnsSkipped;
   private long columnsSent;
   private long batchesSent;
   private long batchesRefused;
   private long batchBytesSent;
   private long detailRequests;
   private String lastExplanation;

   /**
    * Says why nothing is being sent, once per distinct reason.
    *
    * <p>"sectionsSent=0" on its own cannot distinguish "no engine", "engine but no stored sections
    * near the player", and "sections found but the client already has them" -- and guessing between
    * those has cost several round trips already.
    *
    * <p>The reason must not carry anything that changes every pass. It did: the cursor position was
    * part of the string, so no two reasons were ever equal and this logged ten lines a second for as
    * long as the walk was over empty ground -- which is most of it. Progress belongs in
    * {@link #status()}, which is asked for rather than shouted.
    */
   private void explainOnce(String reason) {
      if (!reason.equals(this.lastExplanation)) {
         this.lastExplanation = reason;
         Log.info("lod stream idle: " + reason);
      }
   }

   /** Translation tables for one engine, valid while its mapper has not grown. */
   private record Tables(int blockCount, int biomeCount, int[] blockIdToState, String[] biomeIdToName, int[] biomeIdToNameHash) {
   }

   private final Map<WorldIdentifier, Tables> tableCache = new HashMap<>();

   private Tables tablesFor(WorldIdentifier key, Mapper mapper) {
      int blocks = mapper.getStateEntries().length;
      int biomes = mapper.getBiomeEntries().length;
      Tables cached = this.tableCache.get(key);

      if (cached != null && cached.blockCount() == blocks && cached.biomeCount() == biomes) {
         return cached;
      }

      String[] names = biomeTable(mapper);
      int[] nameHashes = new int[names.length];

      for (int i = 0; i < names.length; i++) {
         nameHashes[i] = LodHash.name(names[i]);
      }

      Tables fresh = new Tables(blocks, biomes, blockTable(mapper), names, nameHashes);
      this.tableCache.put(key, fresh);
      return fresh;
   }

   private LodContentIndex indexFor(WorldIdentifier key, WorldEngine engine) {
      LodContentIndex index = this.indexes.computeIfAbsent(key, k -> new LodContentIndex());
      index.attach(engine);
      return index;
   }

   private static final class PlayerState {
      /** The protocol handshake passed. Streaming still waits for the declaration. */
      boolean ready;

      /** The roll-up arrived complete, or we gave up waiting for it. */
      boolean declared;

      /**
       * Which world the declaration was about.
       *
       * <p>A player through a portal is looking at a different Voxy database with its own column
       * keys, and the numbers from the old one mean nothing against it -- they would not even be
       * wrong in a detectable way, since a column key says nothing about which dimension it is in.
       * So the declaration is thrown away and asked for again.
       */
      Identifier declaredDimension;

      long helloAtNanos;

      /** Region key to the client's roll-up. An absent region means the client has nothing there. */
      final Long2LongOpenHashMap clientRegions = new Long2LongOpenHashMap();

      /**
       * Column key to the content hash we believe the client holds.
       *
       * <p>Seeded from the declaration -- directly where a region's detail was asked for, wholesale
       * where the roll-up already agreed -- and extended as columns are sent. This is the whole of
       * what replaced the old "already sent" set, and the difference is that every entry is a claim
       * about content rather than about an event, so it can be compared again later.
       */
      final Long2LongOpenHashMap believed = new Long2LongOpenHashMap();

      /** Regions whose per-column picture is settled, one way or another. */
      final LongOpenHashSet resolved = new LongOpenHashSet();

      /** Regions asked about and not yet answered. Their columns are skipped meanwhile. */
      final LongOpenHashSet awaiting = new LongOpenHashSet();

      /** Regions the walk wants detail for, drained into a request at the end of the pass. */
      final LongArrayList wantDetail = new LongArrayList();

      long oldestRequestNanos;

      int cursor;
      long cursorOrigin = Long.MIN_VALUE;
      long restartAfterNanos;

      /** Cursor position where this lap last found a stored column. See the early-wrap note. */
      int lastFindCursor;

      /** Laps completed, so every Nth can be a full one. */
      int lap;

      PlayerState() {
         this.clientRegions.defaultReturnValue(0L);
         this.believed.defaultReturnValue(0L);
      }

      /** Back to knowing nothing about this client, for a fresh declaration. */
      void forget() {
         this.declared = false;
         this.declaredDimension = null;
         this.clientRegions.clear();
         this.believed.clear();
         this.resolved.clear();
         this.awaiting.clear();
         this.wantDetail.clear();
         this.oldestRequestNanos = 0L;
         this.cursor = 0;
         this.lastFindCursor = 0;
         this.restartAfterNanos = 0L;
      }
   }

   /**
    * How far past the last find to keep walking before concluding the frontier is behind us.
    *
    * <p>Roughly a ring's worth of columns at the radius the generator reaches, so a gap in the
    * stored data does not end the lap early, but open space beyond the frontier does.
    */
   private static final int EARLY_WRAP_GAP = 3000;

   /**
    * Every Nth lap ignores {@link #EARLY_WRAP_GAP} and sweeps the whole radius.
    *
    * <p>Early wrapping assumes stored ground is a disc around the player, which is true of anything
    * the pregenerator made. It is not true of ground ingested in an earlier session somewhere else,
    * which is still sitting in the server's LOD store. A full lap now and then picks that up.
    */
   private static final int FULL_LAP_EVERY = 8;

   /**
    * How long to wait before walking the radius again once a pass has finished.
    *
    * <p>A finished scan is not a finished job. Ingest keeps storing sections behind the cursor --
    * that is the whole point of running a pregenerator -- so a cursor that stops at the end of the
    * table sends whatever existed when it swept past and then nothing, forever. Restarting picks up
    * everything voxelized since, and now also everything that has <em>changed</em> since, which is
    * the other half of what the re-walk is for.
    */
   private static final long RESCAN_AFTER_NANOS = 15L * 1_000_000_000L;

   /**
    * How long to wait for the client's declaration before streaming anyway.
    *
    * <p>The client sends one unconditionally, even when it has nothing, so this only fires if it
    * crashed, hung, or is old enough not to know it should. Streaming without a declaration is the
    * safe answer -- it re-sends what the player may already have, which is the behaviour this whole
    * change replaced and is merely wasteful.
    */
   private static final long DECLARATION_TIMEOUT_NANOS = 45L * 1_000_000_000L;

   /** How long to wait for the detail on a region before assuming the client has nothing there. */
   private static final long DETAIL_TIMEOUT_NANOS = 20L * 1_000_000_000L;

   public LodStreamer(VoxyMapConfig config) {
      this.config = config;
   }

   public void register() {
      ServerPlayNetworking.registerGlobalReceiver(LodProtocol.Hello.TYPE, (payload, context) -> {
         ServerPlayer player = context.player();
         boolean ok = payload.protocol() == LodProtocol.PROTOCOL;

         context.server().execute(() -> {
            if (ok) {
               PlayerState state = this.players.computeIfAbsent(player.getUUID(), k -> new PlayerState());
               state.ready = true;
               state.helloAtNanos = System.nanoTime();
               Log.info("player " + player.getGameProfile().name() + " ready for LOD streaming, protocol " + payload.protocol()
                  + "; waiting for them to declare what they already have");
            } else {
               Log.warn(
                  "player " + player.getGameProfile().name() + " has an incompatible voxymap protocol (theirs="
                     + payload.protocol() + ", ours=" + LodProtocol.PROTOCOL + "); not streaming LOD"
               );
            }

            ServerPlayNetworking.send(player, new LodProtocol.Ready(LodProtocol.PROTOCOL, ok));
         });
      });

      ServerPlayNetworking.registerGlobalReceiver(LodProtocol.Have.TYPE, (payload, context) -> {
         ServerPlayer player = context.player();
         context.server().execute(() -> this.onHave(player, payload));
      });

      // This was written and never wired, which quietly turned the already-sent set from
      // per-session into per-server-process. It also stopped `players` ever shrinking, so the status
      // line counted everyone who had ever joined.
      net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register(
         (handler, server) -> this.onDisconnect(handler.getPlayer())
      );
   }

   public void onDisconnect(ServerPlayer player) {
      this.players.remove(player.getUUID());
   }

   /**
    * The client saying what it holds, at one scale or the other.
    *
    * <p>Everything a client claims is taken at face value, and that is safe for the same reason the
    * whole design is: a client can only lie itself out of data it wanted. The server never records
    * any of it, so the next join starts from whatever the client says then.
    */
   private void onHave(ServerPlayer player, LodProtocol.Have payload) {
      PlayerState state = this.players.get(player.getUUID());

      if (state == null) {
         return;
      }

      // A declaration made for another world says nothing about this one. Dropping it leaves the
      // player undeclared, which re-sends -- the safe direction.
      if (!player.level().dimension().identifier().equals(payload.dimension())) {
         return;
      }

      if (payload.scope() == LodProtocol.SCOPE_REGION) {
         for (int i = 0; i < payload.keys().length; i++) {
            state.clientRegions.put(payload.keys()[i], payload.hashes()[i]);
         }

         if (payload.last()) {
            state.declared = true;
            state.declaredDimension = payload.dimension();
            Log.info(
               "player " + player.getGameProfile().name() + " declares LOD in " + state.clientRegions.size()
                  + " regions; streaming only what differs"
            );
         }

         return;
      }

      for (int i = 0; i < payload.keys().length; i++) {
         state.believed.put(payload.keys()[i], payload.hashes()[i]);
      }

      for (long region : payload.regions()) {
         state.awaiting.remove(region);
         state.resolved.add(region);
      }

      state.oldestRequestNanos = state.awaiting.isEmpty() ? 0L : System.nanoTime();
   }

   /**
    * Forgets what every player was believed to hold, so the next lap re-sends all of it.
    *
    * <p>The escape hatch for a delta that has gone wrong. It cannot be reached by accident and it
    * only ever costs bandwidth -- there is no state here whose loss can hide data from a client.
    */
   public int resend() {
      int n = 0;

      for (PlayerState state : this.players.values()) {
         n += state.believed.size();
         state.believed.clear();
         state.resolved.clear();
         state.awaiting.clear();
         state.wantDetail.clear();
         state.clientRegions.clear();
         state.cursor = 0;
         state.lastFindCursor = 0;
         state.restartAfterNanos = 0L;
      }

      return n;
   }

   public void tick(MinecraftServer server) {
      if (++this.tickCounter < Math.max(1, this.config.lodStreamIntervalTicks)) {
         return;
      }

      this.tickCounter = 0;

      if (this.config.lodStreamRadiusSections <= 0) {
         return;
      }

      var instance = VoxyCommon.getInstance();

      if (instance == null) {
         return;
      }

      for (ServerPlayer player : server.getPlayerList().getPlayers()) {
         PlayerState state = this.players.get(player.getUUID());

         if (state == null || !state.ready) {
            continue;
         }

         Identifier here = player.level().dimension().identifier();

         if (state.declared && !here.equals(state.declaredDimension)) {
            state.forget();
            state.helloAtNanos = System.nanoTime();
            Log.info("player " + player.getGameProfile().name() + " changed world; waiting for a fresh declaration for " + here);
         }

         if (!state.declared) {
            if (System.nanoTime() - state.helloAtNanos < DECLARATION_TIMEOUT_NANOS) {
               continue;
            }

            state.declared = true;
            state.declaredDimension = here;
            Log.warn(
               "player " + player.getGameProfile().name() + " never declared what LOD they hold; sending everything in range"
            );
         }

         try {
            this.streamTo(instance, player, state);
         } catch (Throwable t) {
            Log.warn("LOD streaming failed for " + player.getGameProfile().name(), t);
         }
      }
   }

   private void streamTo(me.cortex.voxy.commonImpl.VoxyInstance instance, ServerPlayer player, PlayerState state) {
      ServerLevel level = player.level();
      WorldIdentifier id = WorldIdentifier.of(level);
      WorldEngine engine = instance.getNullable(id);

      if (engine == null || !engine.isLive()) {
         this.explainOnce("no live Voxy engine for " + id.getWorldId() + " (engine=" + (engine == null ? "null" : "not live") + ")");
         return;
      }

      Mapper mapper = engine.getMapper();

      // Cached against the mapper's size rather than rebuilt every pass. Rebuilding meant a full
      // walk of every block state Voxy has ever seen, per player, ten times a second -- the same
      // mistake PaletteSnapshot already had to fix once (§5.5), and for the same reason.
      Tables tables = this.tablesFor(id, mapper);
      int[] blockIdToState = tables.blockIdToState();
      String[] biomeIdToName = tables.biomeIdToName();
      int[] biomeIdToNameHash = tables.biomeIdToNameHash();

      LodContentIndex index = this.indexFor(id, engine);

      // Before anything is compared against the cache. An invalidation queued during the last pass
      // is a column whose cached hash is now a statement about content that no longer exists.
      index.drainInvalidations();

      BlockPos pos = player.blockPosition();
      int centreX = pos.getX() >> 5;
      int centreZ = pos.getZ() >> 5;
      long origin = (((long) centreX) << 32) | (centreZ & 0xFFFFFFFFL);

      // Same reasoning as the pregenerator: a cursor is an index measured from a centre, so it
      // means a different column once the player moves.
      if (origin != state.cursorOrigin) {
         state.cursorOrigin = origin;
         state.cursor = 0;
         state.lastFindCursor = 0;
         state.restartAfterNanos = 0L;
      }

      // A region asked about and never answered would otherwise be skipped forever.
      if (!state.awaiting.isEmpty() && state.oldestRequestNanos != 0L
         && System.nanoTime() - state.oldestRequestNanos >= DETAIL_TIMEOUT_NANOS) {
         Log.warn("player " + player.getGameProfile().name() + " did not answer for " + state.awaiting.size()
            + " regions; treating them as holding nothing");
         state.resolved.addAll(state.awaiting);
         state.awaiting.clear();
         state.oldestRequestNanos = 0L;
      }

      int[] offsets = RadialOffsets.forRadius(this.config.lodStreamRadiusSections);

      // Wrap early once the walk is clearly past the edge of what exists.
      //
      // The offset table is a disc of lodStreamRadiusSections; the server only *generates* out to
      // generationRadiusChunks. At the shipped values -- 96 sections streamed against 64 chunks,
      // which is 32 sections, generated -- only the inner 11% of a lap can ever hold anything. The
      // budget is spent walking the other 89% at 24 columns a pass, so a lap takes two minutes, and
      // ground the pregenerator just made is not picked up until the cursor comes back round.
      //
      // So: once we are a full ring past the last thing we found, stop waiting on empty space and
      // go back to the middle, where the generator is still filling in behind us.
      boolean fullLap = (state.lap % FULL_LAP_EVERY) == 0;

      // Not while a question is outstanding. Columns skipped waiting for a reply do not move
      // lastFindCursor, so a walk that has just asked about a run of regions looks -- for as long
      // as the reply takes -- exactly like a walk over open space, and would restart the lap on
      // ground it has not actually examined yet.
      if (!fullLap && state.awaiting.isEmpty() && state.cursor > 0 && state.cursor - state.lastFindCursor > EARLY_WRAP_GAP) {
         state.cursor = 0;
         state.lastFindCursor = 0;
         state.lap++;
         state.restartAfterNanos = 0L;
      }

      if (state.cursor >= offsets.length) {
         long nowNanos = System.nanoTime();

         if (state.restartAfterNanos == 0L) {
            state.restartAfterNanos = nowNanos + RESCAN_AFTER_NANOS;
         }

         if (nowNanos - state.restartAfterNanos >= 0L) {
            state.cursor = 0;
            state.lastFindCursor = 0;
            state.lap++;
            state.restartAfterNanos = 0L;
         } else {
            return;
         }
      }

      int minSy = level.getMinY() >> 5;
      int maxSy = (level.getMaxY() - 1) >> 5;

      Batch batch = new Batch();

      // Bound the WORK, not the output. This loop used to run until it had filled a batch, which
      // meant that when nothing was found -- a fresh store, or ground the server has not voxelized
      // yet -- it walked the entire offset table times the full Y range: some 350,000 section
      // lookups per pass, per player, on the server thread. That is what took the tick to twenty
      // seconds, and the lag then starved chunk generation through the TPS backoff.
      int columnBudget = Math.max(1, this.config.maxSectionsPerStreamPass);
      int columnsExamined = 0;
      int foundThisPass = 0;
      int awaitedThisPass = 0;

      // The column is the unit, and it is whole or it is nothing.
      //
      // The byte cap decides whether to START a column, never whether to abandon one half sent.
      // Breaking out mid-column left the client holding the top of a stack it will not draw, with
      // the cursor already past it, so the rest only arrived on the next lap. A column that runs
      // past the cap flushes what it has and carries on into the next batch; batching is transport,
      // and the client writes each section on its own regardless of which packet carried it.
      int bytesThisPass = 0;

      while (columnsExamined < columnBudget && state.cursor < offsets.length && bytesThisPass < LodProtocol.MAX_BATCH_BYTES) {
         int packed = offsets[state.cursor++];
         int sx = centreX + RadialOffsets.dx(packed);
         int sz = centreZ + RadialOffsets.dz(packed);
         columnsExamined++;

         long columnKey = SectionIndex.columnKey(sx, sz);
         long regionKey = SectionIndex.regionKey(sx >> 4, sz >> 4);

         if (!state.resolved.contains(regionKey)) {
            if (state.awaiting.contains(regionKey)) {
               // Asked and not answered yet. The reply usually lands well before the walk has
               // crossed the region, and whatever it misses comes round on the next lap.
               this.columnsSkipped++;
               awaitedThisPass++;
               continue;
            }

            if (!this.resolveRegion(index, state, regionKey)) {
               state.awaiting.add(regionKey);
               state.wantDetail.add(regionKey);
               this.columnsSkipped++;
               awaitedThisPass++;
               continue;
            }
         }

         long serverHash = index.hash(engine, columnKey, sx, sz, minSy, maxSy, blockIdToState, biomeIdToNameHash);

         if (serverHash == 0L) {
            continue;
         }

         // Where the frontier is: ground we hold, whether or not the client needs it. Counting only
         // what was sent would make the lap wrap the moment the client caught up.
         foundThisPass++;

         if (serverHash == state.believed.get(columnKey)) {
            this.columnsSkipped++;
            continue;
         }

         // The whole stored stack, top to bottom -- NOT the surface only.
         //
         // The client refuses to draw a chunk whose Voxy stack stops short of the world floor,
         // because that is exactly what a column still arriving looks like, and it has no other way
         // to tell "deliberately truncated" from "half delivered" (ColumnScanner: `completeToBottom`,
         // and §7.19 for why the distinction has to exist).
         for (int sy = maxSy; sy >= minSy; sy--) {
            WorldSection section = engine.acquireIfExists(0, sx, sy, sz);

            if (section == null) {
               continue;
            }

            try {
               byte[] encoded = SectionCodec.encode(
                  section,
                  localId -> globalStateId(blockIdToState, localId),
                  localId -> localId >= 0 && localId < biomeIdToName.length ? biomeIdToName[localId] : ""
               );

               batch.sections.add(encoded);
               batch.bytes += encoded.length;
               bytesThisPass += encoded.length;
               this.sectionsSent++;
               this.bytesSent += encoded.length;
            } finally {
               section.release();
            }

            if (batch.bytes >= LodProtocol.MAX_BATCH_BYTES) {
               this.send(player, level, batch);
               batch = new Batch();
            }
         }

         // Only now, with every section of the column in a batch that has been posted or is about
         // to be. The receipt rides with the last of them, so a connection that dies part way
         // leaves the client having recorded nothing for this column.
         batch.doneColumns.add(columnKey);
         batch.doneHashes.add(serverHash);
         state.believed.put(columnKey, serverHash);
         this.columnsSent++;
      }

      this.columnsScanned += columnsExamined;
      this.columnsFound += foundThisPass;

      if (foundThisPass > 0) {
         state.lastFindCursor = state.cursor;
      }

      if (foundThisPass == 0) {
         // No cursor, no centre, no column count: everything here changes every pass, and a reason
         // that changes every pass is a reason that is logged every pass.
         this.explainOnce(
            awaitedThisPass > 0
               ? "waiting on the client to say which columns it holds in the regions being walked"
               : "the walk is over ground the server has not voxelized -- nothing to send"
         );
      }

      this.send(player, level, batch);
      this.requestDetail(player, level, state);
   }

   /**
    * Decides whether a region's per-column picture can be settled without asking the client.
    *
    * @return true if {@link PlayerState#resolved} now covers it
    */
   private boolean resolveRegion(LodContentIndex index, PlayerState state, long regionKey) {
      long claimed = state.clientRegions.get(regionKey);

      if (claimed == 0L) {
         // The client declared nothing here, so there is nothing to reconcile and nothing to ask.
         // This is the whole of the post-wipe path: a client that declared an empty map costs zero
         // round trips and simply receives everything.
         state.resolved.add(regionKey);
         return true;
      }

      if (index.regionRollUp(regionKey) != claimed) {
         return false;
      }

      // The two folds agree, so every column in the region hashes the same on both machines. Our
      // own hashes are therefore theirs, and the region is settled without a byte on the wire --
      // which is what makes a reconnect to a server that is already warm cost nothing.
      index.adoptRegionInto(regionKey, state.believed);
      state.resolved.add(regionKey);
      return true;
   }

   private void requestDetail(ServerPlayer player, ServerLevel level, PlayerState state) {
      if (state.wantDetail.isEmpty()) {
         return;
      }

      int n = Math.min(state.wantDetail.size(), LodProtocol.MAX_DETAIL_REGIONS);
      long[] regions = new long[n];

      for (int i = 0; i < n; i++) {
         regions[i] = state.wantDetail.getLong(i);
      }

      state.wantDetail.removeElements(0, n);

      if (state.oldestRequestNanos == 0L) {
         state.oldestRequestNanos = System.nanoTime();
      }

      this.detailRequests++;

      if (ServerPlayNetworking.canSend(player, LodProtocol.NeedDetail.TYPE)) {
         ServerPlayNetworking.send(player, new LodProtocol.NeedDetail(level.dimension().identifier(), regions));
      } else {
         // An old client cannot answer. Treat them as holding nothing rather than waiting out the
         // timeout for every region in the radius.
         state.awaiting.removeAll(new LongOpenHashSet(regions));
         state.resolved.addAll(new LongOpenHashSet(regions));
      }
   }

   /** One batch under construction: the sections, and the receipts for whole columns inside it. */
   private static final class Batch {
      final List<byte[]> sections = new ArrayList<>();
      final LongArrayList doneColumns = new LongArrayList();
      final LongArrayList doneHashes = new LongArrayList();
      int bytes;

      boolean isEmpty() {
         return this.sections.isEmpty() && this.doneColumns.isEmpty();
      }
   }

   /** Posts one batch, if there is one. Counts what was offered as well as what actually left. */
   private void send(ServerPlayer player, ServerLevel level, Batch batch) {
      if (batch.isEmpty()) {
         return;
      }

      boolean canSend = ServerPlayNetworking.canSend(player, LodProtocol.Sections.TYPE);

      // sectionsSent counts what was encoded, not what left the machine -- so "3012 sent" and
      // "1 batch received" could both be true with the send quietly going nowhere. Log the first
      // few actual sends with their size and whether the client has even declared the channel.
      if (this.batchesSent < 5) {
         Log.info(
            "lod stream: sending batch " + (this.batchesSent + 1) + " of " + batch.sections.size()
               + " sections, " + batch.bytes + " bytes, canSend=" + canSend
         );
      }

      this.batchesSent++;
      this.batchBytesSent += batch.bytes;

      if (!canSend) {
         this.batchesRefused++;
         return;
      }

      Identifier dimension = level.dimension().identifier();
      ServerPlayNetworking.send(
         player,
         new LodProtocol.Sections(dimension, batch.sections, batch.doneColumns.toLongArray(), batch.doneHashes.toLongArray())
      );
   }

   /** Local block id -> global block-state id, built once per pass. */
   private static int[] blockTable(Mapper mapper) {
      Mapper.StateEntry[] entries = mapper.getStateEntries();
      int[] table = new int[entries.length];

      for (int i = 0; i < entries.length; i++) {
         BlockState state = mapper.getBlockStateFromBlockId(i);
         table[i] = state == null ? -1 : Block.BLOCK_STATE_REGISTRY.getId(state);
      }

      return table;
   }

   private static String[] biomeTable(Mapper mapper) {
      Mapper.BiomeEntry[] entries = mapper.getBiomeEntries();
      String[] table = new String[entries.length];

      for (Mapper.BiomeEntry entry : entries) {
         if (entry != null && entry.id >= 0 && entry.id < table.length) {
            table[entry.id] = entry.biome;
         }
      }

      return table;
   }

   private static int globalStateId(int[] table, int localId) {
      return localId >= 0 && localId < table.length ? table[localId] : -1;
   }

   public String status() {
      // Where each walk has got to. It used to be shouted from explainOnce ten times a second
      // because it was part of the dedupe key; it belongs here, where it is asked for.
      int columns = RadialOffsets.forRadius(this.config.lodStreamRadiusSections).length;
      StringBuilder walks = new StringBuilder();

      for (PlayerState state : this.players.values()) {
         walks.append(walks.isEmpty() ? "" : ",")
            .append(state.cursor).append('/').append(columns)
            .append(" lastFind=").append(state.lastFindCursor)
            .append(" lap=").append(state.lap)
            .append(" declaredRegions=").append(state.clientRegions.size())
            .append(" believed=").append(state.believed.size())
            .append(" awaiting=").append(state.awaiting.size());
      }

      StringBuilder content = new StringBuilder();

      for (Map.Entry<WorldIdentifier, LodContentIndex> e : this.indexes.entrySet()) {
         content.append(content.isEmpty() ? "" : ", ").append(e.getValue().status());
      }

      return "lod stream: players=" + this.players.size()
         + " sectionsSent=" + this.sectionsSent
         + " bytesSent=" + this.bytesSent
         + " columnsScanned=" + this.columnsScanned
         + " columnsFound=" + this.columnsFound
         + " columnsSent=" + this.columnsSent
         + " columnsSkipped=" + this.columnsSkipped
         + " detailRequests=" + this.detailRequests
         + " batches=" + this.batchesSent
         + " batchesRefused=" + this.batchesRefused
         + " batchBytes=" + this.batchBytesSent
         + " codecEncoded=" + LodProtocol.Sections.ENCODED.get()
         + " content=[" + content + "]"
         + " walk=[" + walks + "]";
   }
}
