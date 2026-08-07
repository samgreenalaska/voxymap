package dev.local.voxymap.client;

import dev.local.voxymap.MapWipe;
import dev.local.voxymap.net.LodProtocol;
import dev.local.voxymap.net.SectionCodec;
import dev.local.voxymap.util.Log;
import dev.local.voxymap.voxy.SectionIndex;
import dev.local.voxymap.voxy.VoxySource;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.nio.file.Path;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.voxelization.WorldVoxilizedSectionMipper;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldUpdater;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Receives LOD sections from a voxymap server and writes them into the client's Voxy database.
 *
 * <p>This is the other half of {@code dev.local.voxymap.server.LodStreamer}, and the reason the map
 * bridge has anything to draw on a multiplayer server: without it, Voxy only ever knows the ground
 * the client itself has streamed, which is what render distance already showed you.
 *
 * <h2>Saying what is already held</h2>
 *
 * <p>The client is the only thing that knows what is in the client's database, so it is the client
 * that says. At join it works out a content hash per section column ({@link LodDeclareWorker}),
 * declares a per-region roll-up of that, answers the server's questions about individual regions,
 * and records each column as it arrives ({@link LodHaveStore}). A server that already has what the
 * client has sends nothing; one whose ground has changed sends the changed columns and nothing else.
 *
 * <p>Every step of that is written to fail towards <em>under</em>-declaring. If the store cannot be
 * read, if Voxy's engine is not up in time, if the section index cannot confirm a column, if the
 * connection drops mid-column: the client claims less than it holds and the server re-sends. The
 * opposite mistake -- claiming ground that is not there -- is unrecoverable without deleting files
 * by hand, which is exactly what §7.16 cost.
 *
 * <h2>Translating ids</h2>
 *
 * <p>Sections arrive with two lookup tables rather than raw ids, because block and biome ids are
 * assigned per engine and the server's numbering means nothing here (see {@link SectionCodec}).
 * Each section is rebuilt against this client's own {@link Mapper}: block states come back through
 * the game's block-state registry, biomes through their registry name. A palette entry that cannot
 * be resolved -- a block from a mod this client lacks -- becomes air rather than something wrong.
 */
public final class LodClient {
   private static boolean streaming;
   private static long sectionsReceived;
   private static long sectionsWritten;
   private static long unresolved;
   private static boolean announcedFirstBatch;
   private static long lastReportNanos;
   private static long batchesSeen;
   private static long dropped;
   private static boolean verifiedWriteback;
   private static String lastExplanation;

   // ------------------------------------------------------------------ declaration

   private enum Phase {
      /** Not in a world, or nothing to do. */
      IDLE,
      /** Joined; waiting for a level and a Voxy engine to read. */
      WAITING,
      /** The index walk is running on its own thread. */
      READING,
      /** The roll-up has been sent. */
      DECLARED
   }

   private static Phase phase = Phase.IDLE;
   private static LodDeclareWorker worker;
   private static LodHaveStore store = LodHaveStore.detached();

   /**
    * Which world the current declaration is about.
    *
    * <p>Voxy keeps one database per dimension and this store is per database, so a portal invalidates
    * the whole declaration -- and silently, because a column key carries no dimension and the
    * Overworld's numbers would be answered against the Nether's without anything looking wrong.
    * A dimension change is not a join, so nothing else notices it; this is what does.
    */
   private static Identifier declaredFor;

   private static long joinedAtNanos;
   private static long lastSaveNanos;
   private static int declaredRegions;
   private static int detailReplies;

   /**
    * Heights inserted so far for a column whose receipt has not arrived yet.
    *
    * <p>A column is sent whole but can span several batches, so what the client actually holds is
    * only known once the last of them lands. Recorded from the sections that were really written,
    * not from what the server said it sent, so a section that failed to decode leaves a gap in the
    * bitset and the next session re-fetches the column.
    */
   private static final Long2LongOpenHashMap arrivingHeights = new Long2LongOpenHashMap();

   /** Columns something went wrong with this session. Never recorded, so they are re-sent. */
   private static final LongOpenHashSet failedColumns = new LongOpenHashSet();

   /**
    * How long to wait for Voxy to have an engine for this world before giving up and declaring
    * nothing. Generous: the cost of waiting is a slow start, the cost of giving up is a resend.
    */
   private static final long DECLARE_TIMEOUT_NANOS = 30L * 1_000_000_000L;

   /** How often the store is written back while sections are still arriving. */
   private static final long SAVE_EVERY_NANOS = 60L * 1_000_000_000L;

   /**
    * Says why a batch was thrown away, once per distinct reason.
    *
    * <p>Every early return here used to be silent, so a client receiving batches and discarding all
    * of them looked identical to a client receiving none -- and the server's "sectionsSent" was
    * happily counting them as delivered.
    */
   private static void explainOnce(String reason) {
      if (!reason.equals(lastExplanation)) {
         lastExplanation = reason;
         Log.warn("dropping streamed LOD: " + reason);
      }
   }

   /** How often the receiver reports itself while sections are arriving. */
   private static final long REPORT_EVERY_NANOS = 30L * 1_000_000_000L;

   private LodClient() {
   }

   public static void register() {
      ClientPlayNetworking.registerGlobalReceiver(LodProtocol.Ready.TYPE, (payload, context) -> context.client().execute(() -> {
         streaming = payload.accepted();

         if (payload.accepted()) {
            Log.info("server accepted LOD streaming (protocol " + payload.protocol() + ")");
         } else {
            Log.warn(
               "server refused LOD streaming: protocol mismatch (theirs=" + payload.protocol()
                  + ", ours=" + LodProtocol.PROTOCOL + "). The map will only show ground you load yourself."
            );
         }
      }));

      ClientPlayNetworking.registerGlobalReceiver(LodProtocol.Sections.TYPE, (payload, context) ->
         context.client().execute(() -> receive(payload)));

      ClientPlayNetworking.registerGlobalReceiver(LodProtocol.NeedDetail.TYPE, (payload, context) ->
         context.client().execute(() -> answerDetail(payload)));

      // Announce as soon as the connection is up. The server answers with Ready either way, so a
      // server without voxymap simply never replies and nothing further happens.
      ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
         streaming = false;
         announcedFirstBatch = false;
         verifiedWriteback = false;
         sectionsReceived = 0;
         sectionsWritten = 0;
         unresolved = 0;
         declaredRegions = 0;
         detailReplies = 0;
         arrivingHeights.clear();
         failedColumns.clear();
         store = LodHaveStore.detached();
         declaredFor = null;
         worker = null;
         phase = Phase.WAITING;
         joinedAtNanos = System.nanoTime();
         lastSaveNanos = joinedAtNanos;

         // What the two ends actually agreed to carry. A channel missing here explains a silent
         // drop far better than any counter downstream of it.
         Log.info(
            "LOD channels: canSend(hello)=" + ClientPlayNetworking.canSend(LodProtocol.Hello.TYPE)
               + " canSend(have)=" + ClientPlayNetworking.canSend(LodProtocol.Have.TYPE)
               + " canReceive(sections)=" + ClientPlayNetworking.canSend(LodProtocol.Sections.TYPE)
               + " canReceive(ready)=" + ClientPlayNetworking.canSend(LodProtocol.Ready.TYPE)
         );

         if (ClientPlayNetworking.canSend(LodProtocol.Hello.TYPE)) {
            ClientPlayNetworking.send(new LodProtocol.Hello(LodProtocol.PROTOCOL));
         } else {
            phase = Phase.IDLE;
            Log.info("server does not have voxymap installed; no LOD will be streamed");
         }
      });

      ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
         saveStore("disconnecting");
         phase = Phase.IDLE;
         worker = null;
      });
   }

   public static boolean streaming() {
      return streaming;
   }

   /**
    * Client tick.
    *
    * <p>Drives the declaration, which cannot be done from the join event: Voxy has no engine for the
    * world at that point, and the index walk takes seconds and must not happen on this thread.
    */
   public static void tick() {
      if (phase == Phase.IDLE) {
         return;
      }

      Minecraft mc = Minecraft.getInstance();

      if (mc.level != null && declaredFor != null && !mc.level.dimension().identifier().equals(declaredFor)) {
         saveStore("leaving " + declaredFor);
         redeclare();
      }

      switch (phase) {
         case WAITING -> tickWaiting();
         case READING -> tickReading();
         case DECLARED -> {
            if (System.nanoTime() - lastSaveNanos >= SAVE_EVERY_NANOS) {
               saveStore(null);
            }
         }
         default -> {
         }
      }
   }

   /** Starts over for a different world: new database, new store, nothing carried across. */
   private static void redeclare() {
      store = LodHaveStore.detached();
      declaredFor = null;
      worker = null;
      // Column keys say nothing about which dimension they are in, so in-flight state from the old
      // world would attach itself to whatever column happens to have the same coordinates here.
      arrivingHeights.clear();
      failedColumns.clear();
      declaredRegions = 0;
      phase = Phase.WAITING;
      joinedAtNanos = System.nanoTime();
   }

   private static void tickWaiting() {
      Minecraft mc = Minecraft.getInstance();

      if (mc.level == null) {
         return;
      }

      boolean timedOut = System.nanoTime() - joinedAtNanos >= DECLARE_TIMEOUT_NANOS;

      // peek() rather than open(): this is only asking whether there is anything to read yet, and
      // taking a reference here would be taking one on the client thread, which is the thread Voxy's
      // shutdown spins on. LodDeclareWorker takes the reference, off-thread, where it is safe.
      if (VoxySource.peek(mc.level) == null) {
         if (timedOut) {
            declareNothing("Voxy has no database for this world yet");
         }

         return;
      }

      String worldId;
      Path folder;

      try {
         worldId = WorldIdentifier.of(mc.level).getWorldId();
         folder = MapWipe.voxySaveFolder();
      } catch (Throwable t) {
         declareNothing("could not work out which Voxy database this world uses (" + t + ")");
         return;
      }

      if (folder == null || worldId == null || worldId.isEmpty()) {
         if (timedOut) {
            declareNothing("could not work out where Voxy keeps this world");
         }

         return;
      }

      worker = new LodDeclareWorker(mc.level, folder, worldId);
      phase = Phase.READING;
   }

   private static void tickReading() {
      LodDeclareWorker w = worker;

      if (w == null) {
         declareNothing("the index walk went missing");
         return;
      }

      if (!w.done()) {
         if (System.nanoTime() - joinedAtNanos >= DECLARE_TIMEOUT_NANOS) {
            // Let it finish and be discarded rather than cancelling it; it releases Voxy itself.
            worker = null;
            declareNothing("reading Voxy's section index took too long");
         }

         return;
      }

      worker = null;
      LodDeclareWorker.Result result = w.result();

      if (result == null) {
         declareNothing(w.error() == null ? "the index walk was abandoned" : "the index walk failed");
         return;
      }

      store = result.store();
      declaredRegions = result.regions().size();

      Log.info(
         "declaring " + result.declared() + " LOD columns in " + declaredRegions + " regions to the server"
            + (result.dropped() > 0 ? " (" + result.dropped() + " stored columns are no longer in Voxy's database and were dropped)" : "")
            + ", read in " + result.millis() + " ms"
      );

      send(result.regions());
      phase = Phase.DECLARED;
   }

   /** Declares an empty map, which is always safe: the server re-sends and nothing is lost. */
   private static void declareNothing(String why) {
      Log.info("declaring no stored LOD to the server (" + why + "); it will re-send what it has");
      store = LodHaveStore.detached();
      declaredRegions = 0;
      send(new Long2LongOpenHashMap());
      phase = Phase.DECLARED;
   }

   /** Posts the roll-up, split into as many messages as it takes, the last one flagged. */
   private static void send(Long2LongOpenHashMap regions) {
      Minecraft mc = Minecraft.getInstance();

      if (mc.level == null || !ClientPlayNetworking.canSend(LodProtocol.Have.TYPE)) {
         return;
      }

      Identifier dimension = mc.level.dimension().identifier();
      declaredFor = dimension;
      long[] keys = new long[regions.size()];
      long[] hashes = new long[regions.size()];
      int n = 0;

      for (Long2LongOpenHashMap.Entry e : regions.long2LongEntrySet()) {
         keys[n] = e.getLongKey();
         hashes[n] = e.getLongValue();
         n++;
      }

      int sent = 0;

      do {
         int size = Math.min(LodProtocol.MAX_HAVE_ENTRIES, n - sent);
         boolean last = sent + size >= n;

         ClientPlayNetworking.send(new LodProtocol.Have(
            dimension,
            LodProtocol.SCOPE_REGION,
            NO_LONGS,
            java.util.Arrays.copyOfRange(keys, sent, sent + size),
            java.util.Arrays.copyOfRange(hashes, sent, sent + size),
            last
         ));

         sent += size;
      } while (sent < n);
   }

   private static final long[] NO_LONGS = new long[0];

   /**
    * Answers "which columns do you hold in these regions?".
    *
    * <p>Answered from the declared store rather than from anything measured now, so the reply cannot
    * disagree with the roll-up that prompted it. Regions the client has nothing in are still named
    * in the reply -- that is how the server learns to stop waiting on them.
    */
   private static void answerDetail(LodProtocol.NeedDetail payload) {
      Minecraft mc = Minecraft.getInstance();

      if (mc.level == null || !ClientPlayNetworking.canSend(LodProtocol.Have.TYPE)) {
         return;
      }

      Identifier dimension = mc.level.dimension().identifier();

      if (!dimension.equals(payload.dimension())) {
         return;
      }

      LongOpenHashSet wanted = new LongOpenHashSet(payload.regions());
      Long2ObjectOpenHashMap<LongArrayList> buckets = new Long2ObjectOpenHashMap<>();

      for (long region : payload.regions()) {
         buckets.put(region, new LongArrayList());
      }

      for (Long2LongOpenHashMap.Entry e : store.columns().long2LongEntrySet()) {
         long col = e.getLongKey();
         long region = SectionIndex.regionKey(SectionIndex.columnX(col) >> 4, SectionIndex.columnZ(col) >> 4);

         if (wanted.contains(region)) {
            LongArrayList bucket = buckets.get(region);
            bucket.add(col);
            bucket.add(e.getLongValue());
         }
      }

      // One message per group of regions small enough to fit, so every message settles whole
      // regions. A region split across two messages would leave the server unable to say when it
      // had the whole answer.
      LongArrayList groupRegions = new LongArrayList();
      LongArrayList groupKeys = new LongArrayList();
      LongArrayList groupHashes = new LongArrayList();

      for (long region : payload.regions()) {
         LongArrayList bucket = buckets.get(region);
         int entries = bucket.size() / 2;

         if (!groupRegions.isEmpty() && groupKeys.size() + entries > LodProtocol.MAX_HAVE_ENTRIES) {
            sendDetail(dimension, groupRegions, groupKeys, groupHashes);
            groupRegions.clear();
            groupKeys.clear();
            groupHashes.clear();
         }

         groupRegions.add(region);

         for (int i = 0; i < bucket.size(); i += 2) {
            groupKeys.add(bucket.getLong(i));
            groupHashes.add(bucket.getLong(i + 1));
         }
      }

      if (!groupRegions.isEmpty()) {
         sendDetail(dimension, groupRegions, groupKeys, groupHashes);
      }
   }

   private static void sendDetail(Identifier dimension, LongArrayList regions, LongArrayList keys, LongArrayList hashes) {
      detailReplies++;
      ClientPlayNetworking.send(new LodProtocol.Have(
         dimension,
         LodProtocol.SCOPE_COLUMN,
         regions.toLongArray(),
         keys.toLongArray(),
         hashes.toLongArray(),
         false
      ));
   }

   private static void saveStore(String why) {
      lastSaveNanos = System.nanoTime();

      // A wipe is armed: the database this describes is about to be deleted, so writing the file
      // back would recreate exactly the claim the wipe exists to destroy. MapWipe deletes it with
      // the rest of Voxy's folder, and this makes sure nothing races that.
      if (MapWipe.armed()) {
         return;
      }

      if (!store.dirty()) {
         return;
      }

      store.save();

      if (why != null) {
         Log.info("saved " + store.size() + " streamed LOD columns while " + why);
      }
   }

   // ------------------------------------------------------------------ receiving

   private static void receive(LodProtocol.Sections payload) {
      batchesSeen++;
      Minecraft mc = Minecraft.getInstance();

      if (mc.level == null) {
         dropped++;
         explainOnce("no client level");
         report();
         return;
      }

      // Sections are filed by dimension, so one arriving late after a portal cannot be written into
      // the wrong world's database.
      if (!mc.level.dimension().identifier().equals(payload.dimension())) {
         dropped++;
         explainOnce("dimension mismatch: batch is for " + payload.dimension() + ", client is in " + mc.level.dimension().identifier());
         report();
         return;
      }

      WorldEngine engine = WorldIdentifier.ofEngineNullable(mc.level);

      if (engine == null || !engine.isLive()) {
         dropped++;
         explainOnce("no live Voxy engine on the client (engine=" + (engine == null ? "null" : "not live") + ")");
         report();
         return;
      }

      Mapper mapper = engine.getMapper();

      if (!announcedFirstBatch) {
         announcedFirstBatch = true;
         Log.info("first LOD batch received from the server (" + payload.sections().size() + " sections)");
      }

      boolean anyFailed = false;

      for (byte[] raw : payload.sections()) {
         try {
            SectionCodec.Decoded decoded = SectionCodec.decode(raw);
            write(engine, mapper, mc, decoded);
            sectionsReceived++;

            long column = SectionIndex.columnKey(WorldEngine.getX(decoded.key()), WorldEngine.getZ(decoded.key()));
            int sy = WorldEngine.getY(decoded.key());

            if (sy >= -32 && sy <= 31) {
               arrivingHeights.put(column, arrivingHeights.get(column) | 1L << (sy + 32));
            }
         } catch (Throwable t) {
            anyFailed = true;
            Log.warn("could not apply a streamed LOD section", t);
         }
      }

      if (anyFailed) {
         // Which column the bad section belonged to is not knowable when the decode itself threw,
         // and a column missing one section is exactly the case the height bitset cannot catch --
         // the hash covers the whole stack either way. So every column still in flight is poisoned.
         // It costs re-fetching a handful of columns and it cannot record one that is not all there.
         failedColumns.addAll(arrivingHeights.keySet());
      }

      recordReceipts(payload);
      report();
   }

   /**
    * Banks the columns the server says are complete.
    *
    * <p>Only ever from the heights that were actually written, and only for a batch nothing went
    * wrong in. This is the single place the client's claim about its own contents grows, and it is
    * deliberately downstream of the insert rather than of the packet.
    */
   private static void recordReceipts(LodProtocol.Sections payload) {
      for (int i = 0; i < payload.doneColumns().length; i++) {
         long column = payload.doneColumns()[i];
         long ySet = arrivingHeights.remove(column);

         // remove(), not contains(): clearing the poison here is what lets the same column be
         // recorded if the server sends it again.
         if (failedColumns.remove(column) || ySet == 0L) {
            continue;
         }

         store.put(column, payload.doneHashes()[i], ySet);
      }
   }

   /** Periodic summary, called from every path so a stall still reports itself. */
   private static void report() {
      long now = System.nanoTime();

      if (now - lastReportNanos >= REPORT_EVERY_NANOS) {
         lastReportNanos = now;
         Log.info(status());
      }
   }

   private static void write(WorldEngine engine, Mapper mapper, Minecraft mc, SectionCodec.Decoded decoded) {
      int[] blockIds = new int[decoded.blockStateIds().length];

      for (int i = 0; i < blockIds.length; i++) {
         BlockState state = Block.BLOCK_STATE_REGISTRY.byId(decoded.blockStateIds()[i]);

         if (state == null) {
            unresolved++;
            blockIds[i] = 0;
         } else {
            blockIds[i] = mapper.getIdForBlockState(state);
         }
      }

      int[] biomeIds = new int[decoded.biomeNames().length];
      var biomes = mc.level.registryAccess().lookupOrThrow(Registries.BIOME);

      for (int i = 0; i < biomeIds.length; i++) {
         String name = decoded.biomeNames()[i];
         Holder<Biome> holder = null;

         if (name != null && !name.isEmpty()) {
            Identifier location = Identifier.tryParse(name);

            if (location != null) {
               holder = biomes.get(ResourceKey.create(Registries.BIOME, location)).orElse(null);
            }
         }

         if (holder == null) {
            unresolved++;
            biomeIds[i] = 0;
         } else {
            biomeIds[i] = mapper.getIdForBiome(holder);
         }
      }

      // Translate the whole 32^3 stack into this client's ids first. Same maths as before; only
      // the destination changed.
      long[] source = decoded.voxels();
      long[] translated = new long[source.length];

      for (int i = 0; i < source.length; i++) {
         long voxel = source[i];
         int block = Mapper.getBlockId(voxel);
         int biome = Mapper.getBiomeId(voxel);
         int light = Mapper.getLightId(voxel);

         translated[i] = Mapper.withLight(
            Mapper.withBlockBiome(
               0L,
               block >= 0 && block < blockIds.length ? blockIds[block] : 0,
               biome >= 0 && biome < biomeIds.length ? biomeIds[biome] : 0
            ),
            light
         );
      }

      // Insert through Voxy's own pipeline instead of writing the raw array.
      //
      // Poking _unsafeGetRawDataArray() and marking dirty populates LOD level 0 and NOTHING ELSE.
      // Voxy's renderer is hierarchical: it descends a quadtree from coarse nodes, and levels 1..4
      // are what it draws everything beyond the innermost ring from. With those empty, streamed
      // ground rendered only in the small band where level 0 is used directly -- about 8 chunks,
      // against a configured render distance of 7 x 512 blocks. The map was right the whole time
      // because the map reads the database, which had the level-0 data all along.
      //
      // VoxelIngestService.processJob is the reference: convert, then mipSection, then
      // WorldUpdater.insertUpdate. The mip step is the one that was missing.
      //
      // A VoxelizedSection is a 16^3 unit (level 0 occupies indices 0..4095), so one streamed 32^3
      // section is eight of them. Reused across the eight rather than reallocated, the way Voxy's
      // own ingest keeps one per thread.
      int sx = WorldEngine.getX(decoded.key());
      int sy = WorldEngine.getY(decoded.key());
      int sz = WorldEngine.getZ(decoded.key());

      VoxelizedSection scratch = VoxelizedSection.createEmpty();

      for (int oct = 0; oct < 8; oct++) {
         int ox = (oct & 1) * 16;
         int oy = ((oct >> 1) & 1) * 16;
         int oz = ((oct >> 2) & 1) * 16;

         scratch.zero();
         int nonAir = 0;

         for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
               for (int x = 0; x < 16; x++) {
                  // WorldSection.getIndex is (y<<10)|(z<<5)|x over 32^3; VoxelizedSection level 0
                  // is (y<<8)|(z<<4)|x over 16^3. Same Y-major order, different edge length.
                  long v = translated[((oy + y) << 10) | ((oz + z) << 5) | (ox + x)];
                  scratch.section[(y << 8) | (z << 4) | x] = v;

                  if (Mapper.getBlockId(v) != 0) {
                     nonAir++;
                  }
               }
            }
         }

         scratch.lvl0NonAirCount = nonAir;
         // Chunk-section coordinates: 16-block units, so twice the 32-block section index.
         scratch.setPosition((sx << 1) | (ox >> 4), (sy << 1) | (oy >> 4), (sz << 1) | (oz >> 4));

         WorldVoxilizedSectionMipper.mipSection(scratch, mapper);
         WorldUpdater.insertUpdate(engine, scratch);
      }

      sectionsWritten++;

      if (!verifiedWriteback) {
         verifiedWriteback = true;
         Log.info("first streamed section inserted through the mip pipeline: key=" + decoded.key()
            + " section=" + sx + "," + sy + "," + sz + " voxel[0]=" + (translated.length > 0 ? translated[0] : 0L));
      }
   }

   public static String status() {
      return "lod client: streaming=" + streaming
         + " declare=" + phase
         + " declaredRegions=" + declaredRegions
         + " held=" + store.size()
         + " detailReplies=" + detailReplies
         + " codecDecoded=" + LodProtocol.Sections.DECODED.get()
         + " batches=" + batchesSeen
         + " dropped=" + dropped
         + " received=" + sectionsReceived
         + " written=" + sectionsWritten
         + " unresolvedPaletteEntries=" + unresolved;
   }
}
