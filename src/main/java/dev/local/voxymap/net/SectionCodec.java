package dev.local.voxymap.net;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;

/**
 * Turns a Voxy section into bytes the other machine can rebuild, and back.
 *
 * <h2>Why a section cannot simply be copied</h2>
 *
 * <p>A voxel is a packed long holding a block id, a biome id and a light value, and those ids are
 * <em>per engine</em>: they are handed out by {@link Mapper} in the order that engine happened to
 * meet each block. The server's id 41 and the client's id 41 are unrelated. Shipping raw longs
 * would paint one world's terrain with another world's palette.
 *
 * <p>So each section travels with a translation table. Block states are identified by their entry
 * in the game's own block-state registry, which is derived from the same registries on both ends,
 * and biomes by their registry name. Neither depends on anything Voxy decided locally.
 *
 * <h2>Shape of the payload</h2>
 *
 * <pre>
 *   long   section key
 *   int    block palette length, then that many ints  (server block id -> global block state id)
 *   int    biome palette length, then that many UTF strings (server biome id -> biome name)
 *   int    uncompressed length in bytes
 *   bytes  deflated voxel data
 * </pre>
 *
 * <p>A section is {@link WorldSection#SECTION_VOLUME} longs -- 256 KB raw -- which is far past what
 * a packet should carry, so the voxel array is deflated. Terrain sections are overwhelmingly
 * repeated values and compress to a small fraction of that; the palettes are a few dozen entries.
 */
public final class SectionCodec {
   /** Cheap default: fast enough to run on a server tick budget, still an order of magnitude. */
   private static final int COMPRESSION_LEVEL = Deflater.BEST_SPEED;

   /**
    * Per-thread scratch, because every section used to allocate its own 256 KB.
    *
    * <p>A section is a quarter of a megabyte raw, and the round trip touched that size four times:
    * the encoder built one buffer, the decoder built another to inflate into, and each end built
    * compressor state. Profiled on a singleplayer world, this class alone accounted for 9 GB of
    * allocation in 90 seconds -- 71% of everything the mod allocated -- and in singleplayer all of
    * it was to move a section between two Voxy engines inside one JVM.
    *
    * <p>None of those buffers outlive the call. The one array that does escape is the {@code long[]}
    * in {@link Decoded}, which the caller keeps, so that one is still allocated per section.
    *
    * <p>Thread-local rather than pooled: encode runs on the server thread, decode on the netty and
    * client threads, so there is no contention to manage and nothing to return. {@code Deflater}
    * and {@code Inflater} hold native memory, which is exactly why creating one per section was
    * worse than the byte counts suggest -- they are reset here instead, and never {@code end()}ed
    * because the thread keeps using them.
    */
   private static final class Scratch {
      private final Deflater deflater = new Deflater(COMPRESSION_LEVEL);
      private final Inflater inflater = new Inflater();
      private byte[] raw = new byte[0];
      private byte[] chunk = new byte[16384];

      byte[] raw(int length) {
         if (this.raw.length < length) {
            this.raw = new byte[length];
         }

         return this.raw;
      }
   }

   private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

   private SectionCodec() {
   }

   /** The two lookup tables that travel with a section. */
   public record Palettes(int[] blockStateIds, String[] biomeNames) {
   }

   /**
    * Reads a section into portable form.
    *
    * @param blockIdToState resolves a local block id to a global block-state id
    * @param biomeIdToName resolves a local biome id to a biome registry name
    * @return the encoded section, or null if it held nothing worth sending
    */
   public static byte[] encode(
      WorldSection section,
      java.util.function.IntUnaryOperator blockIdToState,
      java.util.function.IntFunction<String> biomeIdToName
   ) {
      long[] data = section._unsafeGetRawDataArray();

      // Local id -> index in the palette we are about to build. Sized for the worst case rather
      // than grown, because this runs per section on a worker.
      it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap blockSeen = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();
      it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap biomeSeen = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();
      it.unimi.dsi.fastutil.ints.IntArrayList blockIds = new it.unimi.dsi.fastutil.ints.IntArrayList();
      it.unimi.dsi.fastutil.ints.IntArrayList biomeIds = new it.unimi.dsi.fastutil.ints.IntArrayList();

      blockSeen.defaultReturnValue(-1);
      biomeSeen.defaultReturnValue(-1);

      for (long voxel : data) {
         int block = Mapper.getBlockId(voxel);
         int biome = Mapper.getBiomeId(voxel);

         if (blockSeen.get(block) < 0) {
            blockSeen.put(block, blockIds.size());
            blockIds.add(block);
         }

         if (biomeSeen.get(biome) < 0) {
            biomeSeen.put(biome, biomeIds.size());
            biomeIds.add(biome);
         }
      }

      ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
      ByteBuffer header = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
      header.putLong(section.key);
      out.writeBytes(header.array());

      writeInt(out, blockIds.size());

      for (int i = 0; i < blockIds.size(); i++) {
         writeInt(out, blockIdToState.applyAsInt(blockIds.getInt(i)));
      }

      writeInt(out, biomeIds.size());

      for (int i = 0; i < biomeIds.size(); i++) {
         String name = biomeIdToName.apply(biomeIds.getInt(i));
         byte[] utf = (name == null ? "" : name).getBytes(java.nio.charset.StandardCharsets.UTF_8);
         writeInt(out, utf.length);
         out.writeBytes(utf);
      }

      // The voxel array, with block and biome ids replaced by palette indices so the client never
      // has to guess what a raw id meant. Written into thread-local scratch: it is consumed by the
      // deflater before this returns and never escapes.
      int rawLength = data.length * Long.BYTES;
      byte[] raw = SCRATCH.get().raw(rawLength);
      ByteBuffer voxels = ByteBuffer.wrap(raw, 0, rawLength).order(ByteOrder.BIG_ENDIAN);

      for (long voxel : data) {
         int block = blockSeen.get(Mapper.getBlockId(voxel));
         int biome = biomeSeen.get(Mapper.getBiomeId(voxel));
         int light = Mapper.getLightId(voxel);

         // Palette indices are small; packing them the same way Voxy packs real ids keeps the
         // array uniform and therefore compressible.
         voxels.putLong(Mapper.withLight(Mapper.withBlockBiome(0L, block, biome), light));
      }

      writeInt(out, rawLength);
      deflateInto(raw, rawLength, out);

      return out.toByteArray();
   }

   /** The inverse of {@link #encode}, minus the id translation the caller must supply. */
   public record Decoded(long key, int[] blockStateIds, String[] biomeNames, long[] voxels) {
   }

   public static Decoded decode(byte[] payload) {
      ByteBuffer in = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);

      long key = in.getLong();

      int[] blockStateIds = new int[in.getInt()];

      for (int i = 0; i < blockStateIds.length; i++) {
         blockStateIds[i] = in.getInt();
      }

      String[] biomeNames = new String[in.getInt()];

      for (int i = 0; i < biomeNames.length; i++) {
         byte[] utf = new byte[in.getInt()];
         in.get(utf);
         biomeNames[i] = new String(utf, java.nio.charset.StandardCharsets.UTF_8);
      }

      int rawLength = in.getInt();

      // Inflated straight out of the payload into thread-local scratch. Neither a copy of the
      // compressed bytes nor a fresh 256 KB destination is needed: the only thing that outlives
      // this call is the long[] below, which the caller keeps.
      byte[] raw = inflateInto(payload, in.position(), in.remaining(), rawLength);
      ByteBuffer voxelBuf = ByteBuffer.wrap(raw, 0, rawLength).order(ByteOrder.BIG_ENDIAN);
      long[] voxels = new long[rawLength / Long.BYTES];

      for (int i = 0; i < voxels.length; i++) {
         voxels[i] = voxelBuf.getLong();
      }

      return new Decoded(key, blockStateIds, biomeNames, voxels);
   }

   private static void writeInt(ByteArrayOutputStream out, int value) {
      out.write(value >>> 24);
      out.write(value >>> 16);
      out.write(value >>> 8);
      out.write(value);
   }

   /** Compresses straight into the payload stream, so no intermediate array is built at all. */
   private static void deflateInto(byte[] raw, int length, ByteArrayOutputStream out) {
      Scratch scratch = SCRATCH.get();
      Deflater deflater = scratch.deflater;

      // reset(), not end(): the thread keeps this one. end() frees the native state and the next
      // section would have to build it again, which is most of what made this expensive.
      deflater.reset();
      deflater.setInput(raw, 0, length);
      deflater.finish();

      while (!deflater.finished()) {
         int n = deflater.deflate(scratch.chunk);
         out.write(scratch.chunk, 0, n);
      }
   }

   /**
    * @return thread-local scratch holding {@code rawLength} inflated bytes, valid until this
    *     thread decodes again
    */
   private static byte[] inflateInto(byte[] payload, int offset, int length, int rawLength) {
      Scratch scratch = SCRATCH.get();
      Inflater inflater = scratch.inflater;

      // reset(), not end(), for the same reason as the deflater.
      inflater.reset();
      inflater.setInput(payload, offset, length);

      byte[] raw = scratch.raw(rawLength);
      int written = 0;

      try {
         while (written < rawLength && !inflater.finished()) {
            int n = inflater.inflate(raw, written, rawLength - written);

            if (n == 0) {
               break;
            }

            written += n;
         }
      } catch (DataFormatException e) {
         throw new IllegalStateException("corrupt LOD section payload", e);
      }

      // The buffer is reused, so anything short of a full inflate would otherwise be read as the
      // tail of whatever section this thread decoded last -- terrain from somewhere else entirely.
      if (written < rawLength) {
         java.util.Arrays.fill(raw, written, rawLength, (byte) 0);
      }

      return raw;
   }
}
