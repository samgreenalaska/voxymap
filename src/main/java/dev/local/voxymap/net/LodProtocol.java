package dev.local.voxymap.net;

import io.netty.handler.codec.DecoderException;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.resources.Identifier;

/**
 * The wire protocol between voxymap's server half and its client half.
 *
 * <p>Five messages. The client says hello when it joins and declares what it already has; the server
 * asks for detail where the declaration and its own store disagree; the server answers with batches
 * of sections. Nothing else is negotiated -- per-player preferences, config push and live retuning
 * can all be done through {@code /voxymapserver}, which already runs server-side.
 *
 * <p>{@link #PROTOCOL} is checked on both ends. A mismatch disables streaming for that player with
 * a message rather than sending them sections they will decode wrongly, because the failure mode of
 * a silently incompatible section format is a corrupted map database rather than an error.
 *
 * <h2>The declaration, and which end is allowed to remember it</h2>
 *
 * <p>Protocol 1 deduplicated with a per-player set of section keys held in server memory. It died on
 * disconnect, so every reconnect re-sent the entire working set -- 412 MB in one measured session,
 * 1.1 GB in another -- and, because a key once in the set was never reconsidered, ground that
 * changed after it was sent stayed frozen on the client until the next reconnect.
 *
 * <p>Protocol 2 moves the record to the client, where it belongs. The client persists a content hash
 * per section column beside Voxy's own database, declares a roll-up of it at join, and the server
 * sends only what the two ends disagree about. The direction matters: a server-side record of what a
 * client has is precisely the shape of bug that made a client wipe unrecoverable in SS7.16 -- the
 * server skipped exactly the data the client had lost, and the only fix was deleting a file over
 * SSH. Only the client knows what is in the client's database, so only the client gets to say.
 *
 * <p>Every failure path here is built to <em>under</em>-declare. An unreadable store, an engine that
 * is not up yet, a column the section index cannot confirm, a batch that arrives after the world
 * closed: all of them declare less than the client holds. Under-declaring costs a redundant resend.
 * Over-declaring loses ground permanently, and no amount of bandwidth saved is worth that.
 */
public final class LodProtocol {
   // createType() takes a *path* and defaults the namespace to minecraft:, which turns
   // "voxymap:lod_hello" into the invalid identifier "minecraft:voxymap:lod_hello". Building the
   // Type from an explicit namespace + path is the only way to own a namespace.
   /**
    * Raise whenever the section format, the message shapes, or {@link LodHash}'s definition change.
    *
    * <p>It is also written into the client's hash store, so raising it discards every stored
    * declaration -- which is the point: a hash is only meaningful against the definition that
    * produced it, and a stale one would be a claim about content the client cannot back up.
    */
   public static final int PROTOCOL = 2;

   /** {@link Have} carries per-region roll-ups. */
   public static final byte SCOPE_REGION = 0;

   /** {@link Have} carries the per-column detail for the regions it names. */
   public static final byte SCOPE_COLUMN = 1;

   private LodProtocol() {
   }

   /**
    * Declares the payload types to Fabric.
    *
    * <p>Must run on both sides and before anything is sent -- a payload whose type is not
    * registered is rejected at encode time. Called from the {@code main} entrypoint, which Loader
    * runs on a dedicated server and on a client alike, so the two ends cannot disagree about the
    * protocol by construction.
    */
   public static void registerTypes() {
      PayloadTypeRegistry.serverboundPlay().register(Hello.TYPE, Hello.CODEC);
      // registerLarge, not register. Fabric keeps a maximum size per payload type and only splits
      // packets for types declared large; a plain register leaves the default, and anything past it
      // never reaches the receiver -- silently, with no error on either side. A batch of 48 encoded
      // sections is around 60 KB, so all but the smallest were being discarded in transit while the
      // sender counted them as delivered.
      //
      // The declaration needs it just as badly, in the other direction: vanilla's ceiling for a
      // SERVERBOUND custom payload is 32767 bytes, which a few hundred region roll-ups clear.
      PayloadTypeRegistry.serverboundPlay().registerLarge(Have.TYPE, Have.CODEC, MAX_PACKET_BYTES);
      PayloadTypeRegistry.clientboundPlay().register(Ready.TYPE, Ready.CODEC);
      PayloadTypeRegistry.clientboundPlay().register(NeedDetail.TYPE, NeedDetail.CODEC);
      PayloadTypeRegistry.clientboundPlay().registerLarge(Sections.TYPE, Sections.CODEC, MAX_PACKET_BYTES);
   }

   /**
    * Client to server, once per join.
    *
    * <p>Deliberately still one field. A protocol-2 client always follows this with a {@link Have} --
    * an empty one if it has nothing to declare or could not read its store -- so there is nothing to
    * announce here, and leaving the shape alone means a protocol-1 server rejects it on the version
    * number rather than failing to decode it.
    *
    * @param protocol the client's {@link #PROTOCOL}
    */
   public record Hello(int protocol) implements CustomPacketPayload {
      public static final Type<Hello> TYPE = new Type<>(Identifier.fromNamespaceAndPath("voxymap", "lod_hello"));

      public static final StreamCodec<RegistryFriendlyByteBuf, Hello> CODEC = CustomPacketPayload.codec(
         (payload, buf) -> buf.writeVarInt(payload.protocol()),
         buf -> new Hello(buf.readVarInt())
      );

      @Override
      public Type<? extends CustomPacketPayload> type() {
         return TYPE;
      }
   }

   /**
    * Server to client: the server's verdict on whether streaming is on.
    *
    * @param protocol the server's {@link #PROTOCOL}, so the client can say which side is stale
    * @param accepted whether sections will follow
    */
   public record Ready(int protocol, boolean accepted) implements CustomPacketPayload {
      public static final Type<Ready> TYPE = new Type<>(Identifier.fromNamespaceAndPath("voxymap", "lod_ready"));

      public static final StreamCodec<RegistryFriendlyByteBuf, Ready> CODEC = CustomPacketPayload.codec(
         (payload, buf) -> {
            buf.writeVarInt(payload.protocol());
            buf.writeBoolean(payload.accepted());
         },
         buf -> new Ready(buf.readVarInt(), buf.readBoolean())
      );

      @Override
      public Type<? extends CustomPacketPayload> type() {
         return TYPE;
      }
   }

   /**
    * Client to server: what this client already holds.
    *
    * <p>Two scales in one message, because they are the same statement at different resolutions and
    * a second payload type would only duplicate the bounds checking.
    *
    * <ul>
    *   <li>{@link #SCOPE_REGION}: {@code keys} are region keys and {@code hashes} their roll-ups.
    *       Sent unprompted at join, split across as many messages as it takes, the last one flagged.
    *       This is the whole point of the roll-up: a few hundred entries for a map that would take
    *       tens of thousands of columns to describe outright.
    *   <li>{@link #SCOPE_COLUMN}: the answer to a {@link NeedDetail}. {@code regions} names every
    *       region this message settles -- including ones the client turned out to have nothing in,
    *       which is why the region list is separate from the keys rather than derived from them.
    *       {@code keys} are column keys.
    * </ul>
    *
    * @param last region scope only: no further roll-up messages are coming. The server does not
    *     start streaming until it sees this, because a partial roll-up read as complete would look
    *     exactly like a client that has nothing.
    */
   public record Have(Identifier dimension, byte scope, long[] regions, long[] keys, long[] hashes, boolean last)
      implements CustomPacketPayload {
      public static final Type<Have> TYPE = new Type<>(Identifier.fromNamespaceAndPath("voxymap", "lod_have"));

      public static final StreamCodec<RegistryFriendlyByteBuf, Have> CODEC = CustomPacketPayload.codec(
         (payload, buf) -> {
            buf.writeIdentifier(payload.dimension());
            buf.writeByte(payload.scope());
            writeLongs(buf, payload.regions());
            writeLongs(buf, payload.keys());
            writeLongs(buf, payload.hashes());
            buf.writeBoolean(payload.last());
         },
         buf -> {
            Identifier dimension = buf.readIdentifier();
            byte scope = buf.readByte();
            long[] regions = readLongs(buf);
            long[] keys = readLongs(buf);
            long[] hashes = readLongs(buf);

            if (keys.length != hashes.length) {
               throw new DecoderException("lod_have: " + keys.length + " keys against " + hashes.length + " hashes");
            }

            return new Have(dimension, scope, regions, keys, hashes, buf.readBoolean());
         }
      );

      @Override
      public Type<? extends CustomPacketPayload> type() {
         return TYPE;
      }
   }

   /**
    * Server to client: name the columns you hold in these regions.
    *
    * <p>Asked only where the roll-ups disagree, which after the first lap is the frontier and
    * nothing else. A region the client did not declare at all needs no request -- the server already
    * knows the answer is "nothing", which is what makes a wiped client cost zero round trips and
    * simply receive everything.
    */
   public record NeedDetail(Identifier dimension, long[] regions) implements CustomPacketPayload {
      public static final Type<NeedDetail> TYPE = new Type<>(Identifier.fromNamespaceAndPath("voxymap", "lod_need_detail"));

      public static final StreamCodec<RegistryFriendlyByteBuf, NeedDetail> CODEC = CustomPacketPayload.codec(
         (payload, buf) -> {
            buf.writeIdentifier(payload.dimension());
            writeLongs(buf, payload.regions());
         },
         buf -> new NeedDetail(buf.readIdentifier(), readLongs(buf))
      );

      @Override
      public Type<? extends CustomPacketPayload> type() {
         return TYPE;
      }
   }

   /**
    * Server to client: a batch of encoded sections for one dimension.
    *
    * <p>Batched because a single section is small once deflated and a packet per section would
    * spend more on framing than payload. The server keeps a batch under {@link #MAX_BATCH_BYTES}.
    *
    * @param dimension which world these belong to, so a client mid-portal cannot mis-file them
    * @param sections each entry as produced by {@link SectionCodec#encode}
    * @param doneColumns columns whose <em>last</em> section is in this batch, so the client may now
    *     record itself as holding them. A column bigger than a batch spans several messages and its
    *     receipt lands on the last of them; a connection that dies in between leaves the client
    *     having recorded nothing for that column, which costs one redundant resend and never a hole.
    * @param doneHashes the content hash to record against each of {@code doneColumns}
    */
   public record Sections(Identifier dimension, List<byte[]> sections, long[] doneColumns, long[] doneHashes)
      implements CustomPacketPayload {
      public static final Type<Sections> TYPE = new Type<>(Identifier.fromNamespaceAndPath("voxymap", "lod_sections"));

      /**
       * How many times each half of the codec has actually run.
       *
       * <p>The one measurement that separates "the application never sent it" from "the network
       * never delivered it". Every counter either side of this pair can read healthy while the
       * packets themselves go nowhere, which is exactly what happened: the sender counted encoded
       * sections and the receiver counted handled batches, and neither noticed the gap between.
       */
      public static final java.util.concurrent.atomic.AtomicLong ENCODED = new java.util.concurrent.atomic.AtomicLong();
      public static final java.util.concurrent.atomic.AtomicLong DECODED = new java.util.concurrent.atomic.AtomicLong();

      public static final StreamCodec<RegistryFriendlyByteBuf, Sections> CODEC = CustomPacketPayload.codec(
         (payload, buf) -> {
            ENCODED.incrementAndGet();
            buf.writeIdentifier(payload.dimension());
            buf.writeVarInt(payload.sections().size());

            for (byte[] section : payload.sections()) {
               buf.writeByteArray(section);
            }

            writeLongs(buf, payload.doneColumns());
            writeLongs(buf, payload.doneHashes());
         },
         buf -> {
            DECODED.incrementAndGet();
            Identifier dimension = buf.readIdentifier();
            int count = buf.readVarInt();

            if (count < 0 || count > MAX_ENTRIES) {
               throw new DecoderException("lod_sections: " + count + " sections in one batch");
            }

            List<byte[]> sections = new java.util.ArrayList<>(Math.min(count, 1024));

            for (int i = 0; i < count; i++) {
               sections.add(buf.readByteArray());
            }

            long[] doneColumns = readLongs(buf);
            long[] doneHashes = readLongs(buf);

            if (doneColumns.length != doneHashes.length) {
               throw new DecoderException("lod_sections: " + doneColumns.length + " done columns against " + doneHashes.length + " hashes");
            }

            return new Sections(dimension, sections, doneColumns, doneHashes);
         }
      );

      @Override
      public Type<? extends CustomPacketPayload> type() {
         return TYPE;
      }
   }

   // ------------------------------------------------------------------ shared framing

   /** Ceiling on any counted list, so a corrupt length cannot ask either end for a huge array. */
   private static final int MAX_ENTRIES = 1 << 20;

   private static void writeLongs(RegistryFriendlyByteBuf buf, long[] values) {
      buf.writeVarInt(values.length);

      for (long v : values) {
         buf.writeLong(v);
      }
   }

   private static long[] readLongs(RegistryFriendlyByteBuf buf) {
      int n = buf.readVarInt();

      if (n < 0 || n > MAX_ENTRIES) {
         throw new DecoderException("voxymap: implausible list length " + n);
      }

      long[] out = new long[n];

      for (int i = 0; i < n; i++) {
         out[i] = buf.readLong();
      }

      return out;
   }

   /**
    * What a single batch is allowed to carry.
    *
    * <p>Kept well under {@link #MAX_PACKET_BYTES} so the framing, the dimension id and the per-entry
    * lengths cannot push a batch past what was declared.
    */
   public static final int MAX_BATCH_BYTES = 96 * 1024;

   /**
    * The size declared to Fabric for the payloads that are split.
    *
    * <p>Vanilla's own ceiling for a clientbound custom payload is 1 MB; this stays comfortably
    * below it while leaving room above {@link #MAX_BATCH_BYTES}.
    */
   public static final int MAX_PACKET_BYTES = 256 * 1024;

   /**
    * Entries per {@link Have}, whichever scale. 16 bytes each, so this is about 96 KB of payload --
    * the same budget a section batch gets, and well inside what was declared above.
    */
   public static final int MAX_HAVE_ENTRIES = 6000;

   /**
    * Regions per {@link NeedDetail}.
    *
    * <p>Bounds the burst the client answers with: a region holds at most 256 columns, so 32 regions
    * is at most 8192 entries of reply, spread over two messages. Small enough that a request never
    * blocks the walk for long, large enough that crossing new ground does not turn into a request
    * per region.
    */
   public static final int MAX_DETAIL_REGIONS = 32;
}
