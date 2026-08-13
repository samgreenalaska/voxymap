package dev.local.voxymap.dh;

import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import dev.local.voxymap.lod.SectionKey;
import dev.local.voxymap.lod.Voxel;
import dev.local.voxymap.net.SectionCodec;
import dev.local.voxymap.util.Log;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Puts streamed LOD into Distant Horizons by handing it chunks.
 *
 * <p>This is the DH-side counterpart of the Voxy insert in {@code LodClient}: same sections off the
 * same wire, a different database underneath. DH has no way to be handed voxels, but it has a
 * supported way to be handed a <em>chunk</em> — {@code overwriteChunkDataAsync} — and it treats one
 * exactly as if the client had loaded it: wrapper, LOD build, lighting, mip, store, render. So the
 * job here is to turn a 32<sup>3</sup> voxel section back into vanilla chunk objects.
 *
 * <h2>A column at a time, never a section</h2>
 *
 * <p>A chunk is submitted whole or not at all, because a submission <em>replaces</em> what DH holds
 * for that chunk. Feeding it sections as they arrive would leave the last one standing alone with
 * everything below it thrown away — the same shape of failure §7.19 hit on the Voxy side, arrived
 * at from the other direction.
 *
 * <p>So sections accumulate against their Voxy column key and are built when the server says the
 * column is complete, which it already tells us for the receipt. One Voxy column is 64x64 blocks:
 * four Minecraft chunks, each assembled from the matching quadrant of every section in the stack.
 *
 * <h2>Off the client thread, and dropped rather than queued forever</h2>
 *
 * <p>Building a column touches on the order of a third of a million block writes, so it happens on
 * a worker. The queue is bounded and a full queue drops the column instead of blocking the netty
 * thread — dropping is free here, because a column is only recorded as held once DH has accepted
 * it, so a dropped one is simply re-sent next session. That is the same under-declare bias the
 * whole protocol is built on.
 */
public final class DhIngest {
   /** Columns waiting to be built. Small: each one is several megabytes of voxels. */
   private static final int QUEUE_CAPACITY = 8;

   /** Sections held for columns the server has not finished sending. */
   private static final int MAX_PENDING_COLUMNS = 512;

   /** One resolved section, ready to be pasted into chunks. */
   private record Section(int sx, int sy, int sz, BlockState[] blocks, Holder<Biome>[] biomes, long[] voxels) {
   }

   /** A complete column and the receipt to bank if DH accepts it. */
   private record Job(long columnKey, long hash, long ySet, List<Section> sections, ClientLevel level, IDhApiLevelWrapper dhLevel) {
   }

   /** What to do once a column is in DH's database. */
   public interface Receipt {
      void accepted(long columnKey, long hash, long ySet);
   }

   private static final Long2ObjectOpenHashMap<List<Section>> pending = new Long2ObjectOpenHashMap<>();
   private static final BlockingQueue<Job> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

   private static Thread worker;
   private static volatile boolean running;
   private static volatile Receipt receipt;

   private static long columnsBuilt;
   private static long columnsDropped;
   private static long chunksSubmitted;
   private static long refused;
   private static long unresolved;
   private static String lastRefusal;
   private static boolean announcedFirst;

   private DhIngest() {
   }

   public static void start(Receipt onAccepted) {
      receipt = onAccepted;

      if (worker != null && worker.isAlive()) {
         return;
      }

      running = true;
      worker = new Thread(DhIngest::loop, "voxymap-dh-ingest");
      worker.setDaemon(true);
      // Below the client thread: this is background map filling, and DH's own build threads are
      // already competing for the same cores.
      worker.setPriority(Thread.NORM_PRIORITY - 2);
      worker.start();
   }

   public static void stop() {
      running = false;
      queue.clear();

      if (worker != null) {
         worker.interrupt();
         worker = null;
      }

      reset();
   }

   /** Forgets every part-built column: a different world's sections must not join this one's. */
   public static void reset() {
      pending.clear();
      queue.clear();
      announcedFirst = false;
   }

   /**
    * Stashes one decoded section against its column.
    *
    * <p>Client thread. The palette is resolved here rather than on the worker because it reads the
    * level's registries, and this is the thread that is guaranteed to have a level at all.
    *
    * @return false if the section could not be used, which poisons the column
    */
   public static boolean accept(SectionCodec.Decoded decoded, ClientLevel level) {
      if (level == null) {
         return false;
      }

      int sx = SectionKey.x(decoded.key());
      int sy = SectionKey.y(decoded.key());
      int sz = SectionKey.z(decoded.key());

      BlockState[] blocks = new BlockState[decoded.blockStateIds().length];

      for (int i = 0; i < blocks.length; i++) {
         BlockState state = Block.BLOCK_STATE_REGISTRY.byId(decoded.blockStateIds()[i]);

         if (state == null) {
            // A block from a mod this client does not have. Air is the honest answer: drawing
            // something else would put the wrong terrain on the map and in the distance.
            unresolved++;
            state = Blocks.AIR.defaultBlockState();
         }

         blocks[i] = state;
      }

      @SuppressWarnings("unchecked")
      Holder<Biome>[] biomes = new Holder[decoded.biomeNames().length];
      var registry = level.registryAccess().lookupOrThrow(Registries.BIOME);
      Holder<Biome> fallback = registry.getAny().orElse(null);

      for (int i = 0; i < biomes.length; i++) {
         String name = decoded.biomeNames()[i];
         Holder<Biome> holder = null;

         if (name != null && !name.isEmpty()) {
            Identifier id = Identifier.tryParse(name);

            if (id != null) {
               holder = registry.get(ResourceKey.create(Registries.BIOME, id)).orElse(null);
            }
         }

         if (holder == null) {
            unresolved++;
            holder = fallback;
         }

         biomes[i] = holder;
      }

      if (fallback == null) {
         return false;
      }

      long column = columnKey(sx, sz);
      List<Section> stack = pending.get(column);

      if (stack == null) {
         if (pending.size() >= MAX_PENDING_COLUMNS) {
            // The server sends a column whole, so this only builds up if completions stop arriving
            // -- a disconnect mid-column, or a batch dropped downstream. Forget the oldest claim
            // rather than growing without bound; it costs a resend.
            pending.clear();
            columnsDropped++;
         }

         stack = new ArrayList<>(16);
         pending.put(column, stack);
      }

      stack.add(new Section(sx, sy, sz, blocks, biomes, decoded.voxels()));
      return true;
   }

   /**
    * The server says this column is complete: build it.
    *
    * @return false if it could not be queued, meaning the receipt must not be banked
    */
   public static boolean complete(long columnKey, long hash, long ySet, ClientLevel level) {
      List<Section> stack = pending.remove(columnKey);

      if (stack == null || stack.isEmpty()) {
         return false;
      }

      IDhApiLevelWrapper dhLevel = DhBridge.levelFor(level);

      if (dhLevel == null) {
         columnsDropped++;
         return false;
      }

      if (!queue.offer(new Job(columnKey, hash, ySet, stack, level, dhLevel))) {
         columnsDropped++;
         return false;
      }

      return true;
   }

   private static long columnKey(int sx, int sz) {
      return dev.local.voxymap.voxy.SectionIndex.columnKey(sx, sz);
   }

   private static void loop() {
      while (running) {
         try {
            Job job = queue.poll(100L, TimeUnit.MILLISECONDS);

            if (job == null) {
               continue;
            }

            if (build(job)) {
               Receipt r = receipt;

               if (r != null) {
                  r.accepted(job.columnKey(), job.hash(), job.ySet());
               }
            }
         } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
         } catch (Throwable t) {
            Log.warn("building a column for Distant Horizons failed", t);
         }
      }
   }

   /** @return true if every chunk of the column reached DH. */
   private static boolean build(Job job) {
      // The client may have left the world between the column completing and this thread reaching
      // it. Building against a stale level would hand DH chunks belonging to another world.
      if (net.minecraft.client.Minecraft.getInstance().level != job.level()) {
         columnsDropped++;
         return false;
      }

      int sx = dev.local.voxymap.voxy.SectionIndex.columnX(job.columnKey());
      int sz = dev.local.voxymap.voxy.SectionIndex.columnZ(job.columnKey());
      PalettedContainerFactory factory = PalettedContainerFactory.create(job.level().registryAccess());
      boolean all = true;

      for (int qx = 0; qx < 2; qx++) {
         for (int qz = 0; qz < 2; qz++) {
            LevelChunk chunk = buildChunk(job, factory, (sx << 1) + qx, (sz << 1) + qz, qx, qz);

            if (chunk == null) {
               continue;
            }

            String error = DhBridge.submitChunk(job.dhLevel(), chunk, job.level());

            if (error == null) {
               chunksSubmitted++;

               if (!announcedFirst) {
                  announcedFirst = true;
                  Log.info("first streamed column handed to Distant Horizons at chunk "
                     + chunk.getPos().x() + "," + chunk.getPos().z());
               }
            } else {
               all = false;
               refused++;

               if (!error.equals(lastRefusal)) {
                  lastRefusal = error;
                  Log.warn("Distant Horizons refused a chunk: " + error);
               }
            }
         }
      }

      columnsBuilt++;
      return all;
   }

   /**
    * One Minecraft chunk out of one quadrant of every section in the column.
    *
    * @return the chunk, or null if this quadrant turned out to be nothing but air
    */
   private static LevelChunk buildChunk(Job job, PalettedContainerFactory factory, int cx, int cz, int qx, int qz) {
      ClientLevel level = job.level();
      LevelChunk chunk = new LevelChunk(level, new ChunkPos(cx, cz));
      LevelChunkSection[] sections = chunk.getSections();
      boolean any = false;

      for (Section section : job.sections()) {
         for (int half = 0; half < 2; half++) {
            int sectionY = (section.sy() << 1) + half;
            int index = level.getSectionIndexFromSectionY(sectionY);

            if (index < 0 || index >= sections.length) {
               // Above or below this world's build height. Voxy stores what it was given, which on
               // a modded world can be taller than the client's own level.
               continue;
            }

            LevelChunkSection built = buildSection(factory, section, qx, qz, half);

            if (built != null) {
               sections[index] = built;
               any = true;
            }
         }
      }

      if (!any) {
         return null;
      }

      // DH builds its own height maps, but it reads the vanilla ones for the solid-height shortcut
      // and finds them empty on a chunk nothing ever primed.
      Heightmap.primeHeightmaps(chunk, EnumSet.allOf(Heightmap.Types.class));
      return chunk;
   }

   /** @return the filled section, or null if all 4096 blocks were air. */
   private static LevelChunkSection buildSection(PalettedContainerFactory factory, Section section, int qx, int qz, int half) {
      long[] voxels = section.voxels();
      BlockState[] blocks = section.blocks();
      Holder<Biome>[] biomes = section.biomes();

      PalettedContainer<BlockState> states = null;
      PalettedContainer<Holder<Biome>> biomeContainer = null;

      int baseX = qx << 4;
      int baseZ = qz << 4;
      int baseY = half << 4;

      for (int y = 0; y < 16; y++) {
         for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
               long voxel = voxels[Voxel.index(baseX + x, baseY + y, baseZ + z)];
               int blockId = Voxel.blockId(voxel);

               // Index 0 is NOT air here, and treating it as air is what put holes in the world.
               //
               // These ids are palette indices the codec assigned, not Voxy's ids: SectionCodec
               // hands out index 0 to the first block it meets while walking the section's raw
               // array, and that walk is y-major from index 0 -- the BOTTOM of the section. In a
               // terrain section the bottom is stone, or seabed, or water. So index 0 is usually
               // the single most common block in the section, and skipping it deleted exactly that
               // block from every chunk: what was left rendered as thin floating slabs and
               // coloured speckles over an ocean that had lost its floor.
               //
               // Air is identified by asking the block state, below, which is the only thing that
               // actually knows.
               if (blockId < 0 || blockId >= blocks.length) {
                  continue;
               }

               BlockState state = blocks[blockId];

               if (state == null || state.isAir()) {
                  continue;
               }

               if (states == null) {
                  // Allocated on first use: most sections of most columns are empty sky, and a
                  // container per empty section was the difference between this being cheap and
                  // this being the reason the client stutters.
                  states = factory.createForBlockStates();
                  biomeContainer = factory.createForBiomes();
               }

               states.set(x, y, z, state);

               // Biomes are stored per 4x4x4 cell, so only one voxel in 64 has anything to say.
               if ((x & 3) == 0 && (y & 3) == 0 && (z & 3) == 0) {
                  int biomeId = Voxel.biomeId(voxel);

                  if (biomeId >= 0 && biomeId < biomes.length && biomes[biomeId] != null) {
                     biomeContainer.set(x >> 2, y >> 2, z >> 2, biomes[biomeId]);
                  }
               }
            }
         }
      }

      return states == null ? null : new LevelChunkSection(states, biomeContainer);
   }

   public static String status() {
      return "dh ingest: " + (worker != null && worker.isAlive() ? "running" : "stopped")
         + " pendingColumns=" + pending.size()
         + " queued=" + queue.size()
         + " built=" + columnsBuilt
         + " chunksSubmitted=" + chunksSubmitted
         + " dropped=" + columnsDropped
         + " refused=" + refused
         + " unresolvedPaletteEntries=" + unresolved
         + (lastRefusal == null ? "" : " lastRefusal=" + lastRefusal);
   }
}
