package dev.local.voxymap.server;

import dev.local.voxymap.util.Log;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Turns server chunks into LOD sections, so there is something to stream.
 *
 * <p>Every chunk the server loads is handed to Voxy's own {@code VoxelIngestService}, which
 * voxelizes it off-thread and writes it into the engine for that dimension. That covers both halves
 * of what needs ingesting without distinguishing them: chunks players load by walking around, and
 * chunks {@link ChunkPregen} pulls in from beyond view distance -- the pregenerator loads to
 * {@code ChunkStatus.FULL}, so its chunks arrive through exactly the same event.
 *
 * <p>Nothing here voxelizes anything itself. {@code enqueueIngest} is Voxy's, the section format is
 * Voxy's, and the threading is Voxy's service manager. This class is the wiring.
 */
public final class ChunkIngest {
   /**
    * Worlds whose LOD storage could not be opened.
    *
    * <p>Without this, a storage failure is retried on every single chunk load, and each retry is a
    * RocksDB open attempt on the server thread. That is how a bad path stopped being a warning and
    * became a 60-second tick and a watchdog kill. A world that cannot be opened once will not open
    * on the next chunk either; give up on it and let the server run without LOD.
    */
   private final java.util.Set<String> broken = new java.util.HashSet<>();

   private long ingested;
   private long skipped;
   private long failed;

   public void register() {
      // The boolean is "newly generated"; both cases are worth ingesting, since a chunk read from
      // disk is exactly what ChunkPregen pulls in on a restart.
      ServerChunkEvents.CHUNK_LOAD.register((level, chunk, newlyGenerated) -> {
         try {
            this.onChunkLoad(level, chunk);
         } catch (Throwable t) {
            // Chunk loading must never fail because the LOD database is unhappy.
            if (this.failed++ < 3) {
               Log.warn("could not ingest chunk " + chunk.getPos() + " into the LOD store", t);
            }
         }
      });
   }

   private void onChunkLoad(ServerLevel level, LevelChunk chunk) {
      var instance = VoxyCommon.getInstance();

      if (instance == null) {
         this.skipped++;
         return;
      }

      WorldIdentifier id = WorldIdentifier.of(level);

      if (this.broken.contains(id.getWorldId())) {
         this.skipped++;
         return;
      }

      if (!instance.isIngestEnabled(id)) {
         this.skipped++;
         return;
      }

      WorldEngine engine;

      try {
         engine = instance.getOrCreate(id);
      } catch (Throwable t) {
         this.broken.add(id.getWorldId());
         Log.error("LOD storage for " + id.getWorldId() + " could not be opened; LOD is off for this world", t);
         return;
      }

      if (engine == null) {
         this.skipped++;
         return;
      }

      if (instance.getIngestService().enqueueIngest(engine, chunk)) {
         this.ingested++;
      } else {
         this.skipped++;
      }
   }

   public String status() {
      // "queued" only ever meant "handed to Voxy". The outstanding task count is what says whether
      // anything is consuming that queue -- it sat at zero workers for a whole round of debugging
      // while queued climbed into the thousands and nothing was stored.
      String backlog = "unknown";

      try {
         var instance = VoxyCommon.getInstance();

         if (instance != null) {
            backlog = String.valueOf(instance.getIngestService().getTaskCount());
         }
      } catch (Throwable ignored) {
      }

      return "lod ingest: queued=" + this.ingested
         + " skipped=" + this.skipped
         + " failed=" + this.failed
         + " outstanding=" + backlog;
   }
}
