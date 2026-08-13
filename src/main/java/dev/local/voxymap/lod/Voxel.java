package dev.local.voxymap.lod;

/**
 * The packed-long voxel layout the wire format carries, written out without Voxy.
 *
 * <p>Byte-for-byte {@code me.cortex.voxy.common.world.other.Mapper}'s layout: block id in bits
 * 27..46, biome id in 47..55, light in 56..63. It has to be, because these are the longs
 * {@link dev.local.voxymap.net.SectionCodec} puts on the wire, and the encoder is a Voxy server
 * calling Voxy's own {@code Mapper}. This is a mirror of that layout, not a format of our own.
 *
 * <h2>Why a mirror rather than the real thing</h2>
 *
 * <p>Voxy does not run on Windows-on-ARM, so the client that most needs streamed LOD is the one
 * that cannot load the class this layout came from. Every other part of the receive path was
 * already Voxy-free — {@code SectionCodec.decode} names no Voxy type, and neither does the packet
 * — so these five accessors were the whole of the coupling. Mirroring them costs a comment saying
 * they must not drift; the alternative was a second wire format for the same bytes.
 *
 * <p>The ids inside a decoded section are <em>palette indices</em>, not the server's ids: the codec
 * rewrites them on the way out. So block 3 here means "the fourth entry of this section's block
 * palette", which resolves through the game's own block-state registry.
 */
public final class Voxel {
   public static final int BLOCK_SHIFT = 27;
   public static final long BLOCK_MASK = 0xFFFFFL;

   public static final int BIOME_SHIFT = 47;
   public static final long BIOME_MASK = 0x1FFL;

   public static final int LIGHT_SHIFT = 56;
   public static final long LIGHT_MASK = 0xFFL;

   /** A section is 32 blocks on a side. */
   public static final int SECTION_SIDE = 32;
   public static final int SECTION_VOLUME = SECTION_SIDE * SECTION_SIDE * SECTION_SIDE;

   private Voxel() {
   }

   public static int blockId(long voxel) {
      return (int)(voxel >> BLOCK_SHIFT & BLOCK_MASK);
   }

   public static int biomeId(long voxel) {
      return (int)(voxel >> BIOME_SHIFT & BIOME_MASK);
   }

   /** Both light values in one byte, sky in the low nibble and block in the high one. */
   public static int light(long voxel) {
      return (int)(voxel >> LIGHT_SHIFT & LIGHT_MASK);
   }

   public static int blockLight(long voxel) {
      return light(voxel) >>> 4 & 15;
   }

   public static int skyLight(long voxel) {
      return light(voxel) & 15;
   }

   /** Index of a voxel inside a section's {@code long[]}: y-major, the way Voxy stores it. */
   public static int index(int x, int y, int z) {
      return (y << 10) | (z << 5) | x;
   }

   /** The inverse: used by the encoder to write palette indices in the same layout. */
   public static long pack(int blockId, int biomeId, int light) {
      return (Integer.toUnsignedLong(blockId) & BLOCK_MASK) << BLOCK_SHIFT
         | (Integer.toUnsignedLong(biomeId) & BIOME_MASK) << BIOME_SHIFT
         | (light & LIGHT_MASK) << LIGHT_SHIFT;
   }
}
