package dev.local.voxymap.lod;

/**
 * Unpacks the section keys the wire format carries, without Voxy.
 *
 * <p>The same field layout as {@code WorldEngine.getX/getY/getZ}: level in bits 60..63, y a signed
 * byte in 52..59, z a signed 24-bit field in 28..51, x the same in 4..27. See {@link Voxel} for why
 * this is mirrored here rather than called through.
 */
public final class SectionKey {
   private SectionKey() {
   }

   public static int x(long key) {
      return (int)(key << 36 >> 40);
   }

   public static int y(long key) {
      return (int)(key << 4 >> 56);
   }

   public static int z(long key) {
      return (int)(key << 12 >> 40);
   }

   public static int level(long key) {
      return (int)(key >> 60 & 15L);
   }
}
