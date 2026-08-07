package dev.local.voxymap.net;

import me.cortex.voxy.common.world.other.Mapper;

/**
 * Content hashes for streamed LOD, at three scales: section, column, region.
 *
 * <h2>Why a hash rather than a revision number</h2>
 *
 * <p>Both ends write into their own copy of the same world. Inside view distance the client
 * voxelizes real blocks for itself, so its copy of a section can be <em>newer</em> than the
 * server's; the server ingests chunks nobody has ever stood in, so its copy can be newer than the
 * client's. Two writers handing out revision numbers independently produce numbers that cannot be
 * compared -- "17" on one machine says nothing about "17" on the other. A hash of the content is
 * comparable whoever produced it, and an absent hash means "I have nothing", which is exactly what a
 * wiped database should say without anyone having to remember that it was wiped.
 *
 * <h2>Light is deliberately not hashed</h2>
 *
 * <p>A voxel packs a block id, a biome id and a light value. Light is the one that churns: placing a
 * torch underground rewrites the stored light for everything around it, and at LOD distance none of
 * that is visible. Hashing it would make a torch cost a full column resend -- about 13 KB for
 * something the player cannot see from where the change matters -- and light churn is frequent
 * enough that it would have made the whole delta useless. So the hash covers block and biome only,
 * and a light-only change is not re-sent.
 *
 * <h2>What "content" means</h2>
 *
 * <p>The canonical form is what {@link SectionCodec} would put on the wire: the global block-state
 * id, and the biome's registry name. Not Voxy's local ids, which are handed out per engine in
 * whatever order that engine happened to meet each block and mean nothing on the other machine. So
 * the hash is over exactly the content that would be sent, minus light -- which is the property that
 * makes "the hashes match" and "sending it again would change nothing" the same statement.
 *
 * <p>Consequently the hash moves if the block-state registry is renumbered, which happens when the
 * mod set changes. That costs one full resend after a mod update and is the correct answer: the
 * meaning of the numbers being sent has changed.
 *
 * <p>The hash definition is part of {@link LodProtocol#PROTOCOL}. Changing anything here means
 * bumping that, because a client's stored hashes are only meaningful against the definition that
 * produced them.
 */
public final class LodHash {
   private static final long FNV_OFFSET = 0xCBF29CE484222325L;
   private static final long FNV_PRIME = 0x100000001B3L;

   private LodHash() {
   }

   /**
    * Zero is reserved for "nothing here" at every scale, so a real hash never collides with it.
    *
    * <p>Worth the one branch: without it a column whose content happened to hash to zero would be
    * indistinguishable from a column that does not exist, and the two cases are treated as
    * opposites -- one is skipped, the other is sent.
    */
   public static long nonZero(long h) {
      return h == 0L ? 1L : h;
   }

   /**
    * One 32^3 section, block and biome only.
    *
    * @param data the raw voxel array, as {@code WorldSection._unsafeGetRawDataArray()} returns it
    * @param blockIdToGlobalState local block id to global block-state id, -1 where unresolvable
    * @param biomeIdToNameHash local biome id to {@link #name} of its registry name
    */
   public static long section(long[] data, int[] blockIdToGlobalState, int[] biomeIdToNameHash) {
      long h = FNV_OFFSET;

      for (long voxel : data) {
         int block = Mapper.getBlockId(voxel);
         int biome = Mapper.getBiomeId(voxel);

         int state = block >= 0 && block < blockIdToGlobalState.length ? blockIdToGlobalState[block] : -1;
         int name = biome >= 0 && biome < biomeIdToNameHash.length ? biomeIdToNameHash[biome] : 0;

         h = (h ^ ((long) state << 32 ^ (name & 0xFFFFFFFFL))) * FNV_PRIME;
      }

      return h;
   }

   /**
    * Folds one section into its column's hash.
    *
    * <p>XOR, so the order sections are visited in does not matter and a section can be folded in or
    * out one at a time. The height is mixed in first, or two identical sections at different heights
    * would cancel each other out and a column could lose one of each without noticing.
    */
   public static long column(long acc, int sy, long sectionHash) {
      return acc ^ mix(sectionHash ^ (long) sy * 0x9E3779B97F4A7C15L);
   }

   /**
    * Folds one column into its region's roll-up.
    *
    * <p>Same reasoning as {@link #column}: XOR is order-independent, which is what lets both ends
    * build the same number from a hash map they iterate in different orders, and what lets the
    * server fold a column out and back in when its content changes.
    */
   public static long region(long acc, long columnKey, long columnHash) {
      return acc ^ mix(columnKey * 0xFF51AFD7ED558CCDL ^ mix(columnHash));
   }

   /** Stable 32-bit hash of a registry name. Not {@code String.hashCode}: too many short collisions. */
   public static int name(String s) {
      if (s == null || s.isEmpty()) {
         return 0;
      }

      int h = 0x811C9DC5;

      for (int i = 0; i < s.length(); i++) {
         h = (h ^ s.charAt(i)) * 0x01000193;
      }

      return h == 0 ? 1 : h;
   }

   /** Stafford variant 13, the same avalanche {@code SectionIndex} uses for its own fingerprints. */
   public static long mix(long v) {
      v ^= v >>> 33;
      v *= 0xFF51AFD7ED558CCDL;
      v ^= v >>> 33;
      v *= 0xC4CEB9FE1A85EC53L;
      return v ^ v >>> 33;
   }
}
