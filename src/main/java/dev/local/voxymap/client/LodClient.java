package dev.local.voxymap.client;

import dev.local.voxymap.MapWipe;
import dev.local.voxymap.lod.Backends;
import dev.local.voxymap.lod.SectionKey;
import dev.local.voxymap.net.LodProtocol;
import dev.local.voxymap.net.SectionCodec;
import dev.local.voxymap.util.Log;
import dev.local.voxymap.voxy.SectionIndex;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

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
   /**
    * Which database arriving sections are written into.
    *
    * <p>The wire format does not change with it: a section is a palette of global block-state ids,
    * a palette of biome names and a packed {@code long[]}, and {@link SectionCodec#decode} names no
    * Voxy type at all. That is what made a Distant Horizons client possible without touching the
    * server -- the bytes were already backend-neutral, and only the last step, the insert, was not.
    */
   private static Backends backend = Backends.NONE;

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
   private static StreamIngest ingest;
   private static StreamIngest.Declaration worker;
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

   /**
    * @param chosen which database sections go into. Both implementations are named here and
    *     nowhere else on this path, so a client missing one of the mods never loads its classes.
    */
   public static void register(Backends chosen) {
      backend = chosen;
      ingest = switch (chosen) {
         case VOXY -> new VoxyStreamIngest();
         // Receipts come back asynchronously on this one: a column is held once DH has accepted the
         // chunks built from it, and anything banked earlier would be recording an intention.
         case DH -> new DhStreamIngest(LodClient::bankReceipt);
         case NONE -> null;
      };

      if (ingest == null) {
         return;
      }

      registerReceivers();
   }

   private static void registerReceivers() {
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
         ingest.reset();
         sectionsReceived = 0;
         sectionsWritten = 0;
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
         // Part-built columns belong to the world that is going away. Anything still queued would
         // otherwise be built against the next world's level and handed to the wrong database.
         ingest.reset();
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
      ingest.reset();
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

      // Asked rather than opened: this only wants to know whether there is anything to read yet.
      // Taking a Voxy reference here would take one on the client thread, which is the thread
      // Voxy's shutdown spins on. The declare worker takes it off-thread, where it is safe.
      if (!ingest.ready(mc.level)) {
         if (timedOut) {
            declareNothing(backend.display() + " has no database for this world yet");
         }

         return;
      }

      String worldId;
      Path folder;

      try {
         worldId = ingest.worldId(mc.level);
         folder = ingest.storeFolder(mc.level);
      } catch (Throwable t) {
         declareNothing("could not work out which " + backend.display() + " database this world uses (" + t + ")");
         return;
      }

      if (folder == null || worldId == null || worldId.isEmpty()) {
         if (timedOut) {
            declareNothing("could not work out where " + backend.display() + " keeps this world");
         }

         return;
      }

      worker = ingest.declare(mc.level, folder, worldId);
      phase = Phase.READING;
   }

   private static void tickReading() {
      StreamIngest.Declaration w = worker;

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
            + (result.dropped() > 0 ? " (" + result.dropped() + " stored columns could not be confirmed in the LOD database and will be re-sent)" : "")
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

      if (ingest == null || !ingest.ready(mc.level)) {
         dropped++;
         explainOnce("no live " + backend.display() + " database on the client");
         report();
         return;
      }

      if (!announcedFirstBatch) {
         announcedFirstBatch = true;
         Log.info("first LOD batch received from the server (" + payload.sections().size() + " sections)");
      }

      boolean anyFailed = false;

      for (byte[] raw : payload.sections()) {
         try {
            SectionCodec.Decoded decoded = SectionCodec.decode(raw);

            if (!ingest.write(mc.level, decoded)) {
               anyFailed = true;
               continue;
            }

            sectionsReceived++;
            sectionsWritten++;

            long column = SectionIndex.columnKey(SectionKey.x(decoded.key()), SectionKey.z(decoded.key()));
            int sy = SectionKey.y(decoded.key());

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

      recordReceipts(mc, payload);
      report();
   }

   /**
    * Banks the columns the server says are complete.
    *
    * <p>Only ever from the heights that were actually written, and only for a batch nothing went
    * wrong in. This is the single place the client's claim about its own contents grows, and it is
    * deliberately downstream of the insert rather than of the packet.
    *
    * <p>"Downstream of the insert" is further away on some backends than others. Voxy holds a
    * column the moment it is written and says so; Distant Horizons is handed whole chunks built on
    * a worker, so it banks the receipt itself once it has taken them. Either way the record grows
    * only from what a database confirmed, never from what a packet claimed.
    */
   private static void recordReceipts(Minecraft mc, LodProtocol.Sections payload) {
      for (int i = 0; i < payload.doneColumns().length; i++) {
         long column = payload.doneColumns()[i];
         long ySet = arrivingHeights.remove(column);

         // remove(), not contains(): clearing the poison here is what lets the same column be
         // recorded if the server sends it again.
         if (failedColumns.remove(column) || ySet == 0L) {
            continue;
         }

         if (ingest.columnComplete(mc.level, column, payload.doneHashes()[i], ySet)) {
            store.put(column, payload.doneHashes()[i], ySet);
         }
      }
   }

   /** Called by a backend that finishes a column later than the packet that carried it. */
   private static void bankReceipt(long columnKey, long hash, long ySet) {
      store.put(columnKey, hash, ySet);
   }

   /**
    * What this client holds, for the DH backend to build a sweep index out of.
    *
    * <p>Called from the sweep worker, so it is a snapshot rather than the live map -- see
    * {@link LodHaveStore#heightsSnapshot}.
    */
   public static Long2LongOpenHashMap heldColumns() {
      return store.heightsSnapshot();
   }

   /** Periodic summary, called from every path so a stall still reports itself. */
   private static void report() {
      long now = System.nanoTime();

      if (now - lastReportNanos >= REPORT_EVERY_NANOS) {
         lastReportNanos = now;
         Log.info(status());
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
         + " into=" + backend.display()
         + (ingest == null ? "" : ingest.status());
   }
}
