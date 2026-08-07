package dev.local.voxymap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.local.voxymap.util.Log;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/** <gameDir>/config/voxymap.json */
public final class VoxyMapConfig {
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

   private static VoxyMapConfig shared;

   /**
    * The one config object for this JVM.
    *
    * <p>Both entrypoints used to call {@link #load()} for themselves, which on a client meant two
    * objects parsed from the same file and then drifting apart forever. The settings screen binds
    * to the client's; {@link dev.local.voxymap.server.ChunkPregen} reads the server's. So every
    * control on that page silently did nothing to chunk generation, and because
    * {@code ModInitializer.onInitialize} runs once at launch, not even reopening the world would
    * resync them -- only restarting the game.
    *
    * <p>That is not a tuning inconvenience, it is the difference between a runaway generator being
    * fixable from the video settings and only being fixable by editing JSON. One instance, shared,
    * mutated in place.
    */
   public static synchronized VoxyMapConfig shared() {
      if (shared == null) {
         shared = load();
      }

      return shared;
   }

   /**
    * The two knobs that actually decide how fast the map fills, as one choice.
    *
    * <p>They are presented together because tuning one without the other is how the main-thread
    * budget and the pipelining depth ended up cancelling each other out. Neither is a decision a
    * player can make on its own: "8 ms per client tick" means nothing without knowing what the
    * apply stage does, and "3 open regions" means nothing without knowing that Xaero's loader
    * completes one region per 40-100 ms pass.
    *
    * <p>Deliberately not a stored field. The raw values in this file stay the single source of
    * truth and {@link VoxyMapConfig#fillSpeed()} reads back whichever preset they are nearest to,
    * so hand-tuning the file is still possible and the screen never disagrees with it.
    */
   public enum FillSpeed {
      BACKGROUND(2.0, 2),
      BALANCED(8.0, 3),
      FAST(16.0, 5);

      public final double applyBudgetMillis;
      public final int maxOpenRegions;

      FillSpeed(double applyBudgetMillis, int maxOpenRegions) {
         this.applyBudgetMillis = applyBudgetMillis;
         this.maxOpenRegions = maxOpenRegions;
      }
   }

   /** The preset the current raw values sit closest to. */
   public FillSpeed fillSpeed() {
      FillSpeed best = FillSpeed.BALANCED;
      double bestDistance = Double.MAX_VALUE;

      for (FillSpeed speed : FillSpeed.values()) {
         double distance = Math.abs(speed.applyBudgetMillis - this.applyBudgetMillis);
         if (distance < bestDistance) {
            bestDistance = distance;
            best = speed;
         }
      }

      return best;
   }

   public void setFillSpeed(FillSpeed speed) {
      this.applyBudgetMillis = speed.applyBudgetMillis;
      this.maxOpenRegions = speed.maxOpenRegions;
      this.clamp();
   }

   /**
    * The Chunkgen distance as one number, in chunks, where zero is off.
    *
    * <p>{@link #generateChunks} stays as the field the server command and the config file use, but
    * it is not a separate decision any more: a distance of zero and "generation off" are the same
    * statement, and offering both invited them to disagree.
    */
   public int chunkgenDistance() {
      return this.generateChunks ? this.generationRadiusChunks : 0;
   }

   public void setChunkgenDistance(int chunks) {
      this.generateChunks = chunks > 0;

      // Zero is carried by the flag, so the radius keeps its last real value and turning generation
      // back on returns to the distance that was chosen rather than to a default.
      if (chunks > 0) {
         this.generationRadiusChunks = chunks;
      }

      this.clamp();
   }

   /**
    * Main-thread milliseconds per client tick spent turning scanned columns into Xaero tiles.
    *
    * <p>Kept in step with {@link FillSpeed#BALANCED}, which is what the video settings screen
    * offers as the default. Measured at 8 ms this costs roughly an eighth of the main-thread time
    * the 3D world render uses; dropping it to 2 quadruples the apply stage on regions that
    * actually write anything, which is most of them on a rescan.
    */
   public double applyBudgetMillis = 8.0;

   /** Skip the apply stage entirely on ticks where the framerate is below this. 0 disables. */
   public int pauseWhenFpsBelow = 45;

   /**
    * Region sessions alive at once: one being written, the rest loading ahead of it.
    *
    * <p>This is the pipelining depth. A region spends 250-700 ms waiting for Xaero's loader before
    * a single tile can be written, and that wait is pure latency -- {@code MapSaveLoad} completes
    * one region load per pass and {@code MapProcessor} sleeps 40-100 ms between passes. Loading the
    * next region while the current one is still being applied hides all of it.
    *
    * <p>It is also the memory bound, which is why it is clamped: a region with {@code beingWritten}
    * set is immune to both {@code MapLimiter} eviction and the {@code postUpload} demote-and-clean
    * path, so every session alive pins a {@code MapRegion} plus up to 64 x 64 KB of texture. 1
    * disables pipelining entirely.
    */
   public int maxOpenRegions = 3;

   /** Max distinct overlay runs recorded per column. Xaero itself allows 10. */
   public int maxOverlays = 4;

   /**
    * Fraction of a chunk's 256 columns that may be INCOMPLETE before the whole chunk is skipped.
    * 0.0 means "skip the chunk if any column is incomplete".
    */
   public double incompleteChunkTolerance = 0.0;

   /** Null out our own tiles near the player so Xaero's writer re-scans them for real. */
   public boolean reclaimNearPlayer = true;

   /** Relax the write predicate to also overwrite tiles we previously authored. */
   public boolean reauthorOwnTiles = false;

   /** Dimensions where Xaero reads the world save directly already have better data than Voxy. */
   public boolean skipWorldSaveDimensions = true;

   /** The Nether needs the full-cave column model, which v1 does not implement. */
   public boolean skipCaveLikeDimensions = true;

   /** Seconds to wait for a region to reach loadState 2 before requeuing it. */
   public int loadTimeoutSeconds = 20;

   /** Seconds to wait for a flushed region to finish saving before giving up on the wait. */
   public int saveTimeoutSeconds = 30;

   /**
    * Rebuild each tile chunk's texture immediately after writing it, the way MapWriter does,
    * instead of only flagging it for Xaero's render-thread pass. Turning this off restores the
    * old behaviour, which left tiles invisible until the world was reloaded. See DIAGNOSIS.md.
    */
   public boolean rebuildBuffersInline = true;

   /**
    * Seconds to hold a region open waiting for its textures to finish uploading before forcing
    * the save. Flushing early clears beingWritten, which lets Xaero demote and clean the region
    * before the tiles have ever been drawn.
    */
   public int settleTimeoutSeconds = 10;

   /**
    * Master switch for everything that exists to debug this mod: the per-region and per-sweep log
    * lines, the extra state dumps, and the {@code /voxymap debug|probe|here|region} commands.
    *
    * <p>On by default while this is pre-alpha. With it off the mod logs only what a user would want
    * to see -- start, finish, warnings -- and the debugging subcommands are not registered at all,
    * so they do not show up in tab completion.
    *
    * <p>Changing it takes effect on {@code /voxymap reload} for logging, but the command tree is
    * built once at startup, so hiding or revealing the subcommands needs a restart.
    */
   public boolean debug = true;

   /** Extra per-region state dumps in the log. Ignored unless {@link #debug} is on. */
   public boolean diagnostics = true;

   /** Sweep automatically after joining a world, without needing /voxymap start. */
   public boolean autoStart = true;

   /** Grace period after joining before the first automatic sweep, in seconds. */
   public int autoStartDelaySeconds = 15;

   /**
    * On an automatic rescan, only re-sweep regions whose Voxy data actually changed. Re-walking a
    * region costs about a second even when there is nothing to write, so leaving this on is what
    * keeps the background rescan cheap.
    */
   public boolean autoRescanOnlyChangedRegions = true;

   /** Re-snapshot Voxy's block palette every N regions, in case new blocks were ingested. */
   public int paletteRefreshRegions = 64;

   /** Per-region summary lines in the log. */
   public boolean verbose = true;

   // ------------------------------------------------------- server-side chunk generation
   //
   // Read only by the server entrypoint. Harmless on a client, where nothing looks at them unless
   // it is hosting the integrated server behind a singleplayer world.

   /** Push the world outward past view distance, so there is terrain to stream in the first place. */
   public boolean generateChunks = true;

   /**
    * How far from each player to generate, in chunks. Cost grows with the square of this.
    *
    * <p>64 is a disc of 12,853 chunks. The old default of 128 is 51,529, and the ceiling of 2,048
    * is 13.2 million -- which is why this is a number someone chooses rather than one derived from
    * a rendering setting that was never about worldgen.
    */
   public int generationRadiusChunks = 64;

   /** Server ticks between generation passes. */
   public int generationIntervalTicks = 5;

   /** Chunk loads allowed in flight at once. */
   public int maxActiveGenerationTasks = 6;

   /** Stop generating while the server is running slower than this, in ticks per second. */
   public double generationMinTps = 18.0;

   // ------------------------------------------------------------- server-side LOD streaming

   /** How far LOD is streamed to each player, in 32-block sections. */
   public int lodStreamRadiusSections = 96;

   /** Server ticks between streaming passes. */
   public int lodStreamIntervalTicks = 2;

   /**
    * Worker threads Voxy gets for voxelizing chunks server-side.
    *
    * <p>Zero means nothing is ever processed, which is not a hypothetical: the instance starts with
    * an empty pool and the queue simply grows.
    */
   public int lodIngestThreads = 2;

   /** Section columns examined per player per pass. Batches also stop at MAX_BATCH_BYTES. */
   public int maxSectionsPerStreamPass = 24;

   // There is deliberately no "how many sections per column" setting.
   //
   // There was -- lodSurfaceSections, defaulting to 2, on the reasoning that the map only draws the
   // surface and the deep column is most of the volume and none of the value. The reasoning is
   // sound and the setting is not available: the client cannot tell a column that was truncated on
   // purpose from one that is still arriving, so it treats both as incomplete and refuses to draw
   // the chunk. See LodStreamer's scan loop, and SS7.19 for why that rule exists.
   //
   // Anything that wants to send less than a whole column has to give the client a way to know it
   // was deliberate, and that is a protocol change, not a number in this file.

   private static Path path() {
      return FabricLoader.getInstance().getConfigDir().resolve("voxymap.json");
   }

   public static VoxyMapConfig load() {
      Path p = path();
      if (Files.exists(p)) {
         try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            VoxyMapConfig cfg = GSON.fromJson(r, VoxyMapConfig.class);
            if (cfg != null) {
               cfg.clamp();
               // Rewrite so options added in a newer version show up in the file rather than
               // staying invisible defaults.
               cfg.save();
               return cfg;
            }
         } catch (Exception e) {
            Log.warn("could not read config, using defaults", e);
         }
      }

      VoxyMapConfig cfg = new VoxyMapConfig();
      cfg.save();
      return cfg;
   }

   public void save() {
      Path p = path();

      try {
         Files.createDirectories(p.getParent());

         try (Writer w = Files.newBufferedWriter(p, StandardCharsets.UTF_8)) {
            GSON.toJson(this, w);
         }
      } catch (IOException e) {
         Log.warn("could not write config", e);
      }
   }

   /** In-place update so anything already holding this instance sees the new values. */
   public void copyFrom(VoxyMapConfig o) {
      this.applyBudgetMillis = o.applyBudgetMillis;
      this.pauseWhenFpsBelow = o.pauseWhenFpsBelow;
      this.maxOpenRegions = o.maxOpenRegions;
      this.maxOverlays = o.maxOverlays;
      this.incompleteChunkTolerance = o.incompleteChunkTolerance;
      this.reclaimNearPlayer = o.reclaimNearPlayer;
      this.reauthorOwnTiles = o.reauthorOwnTiles;
      this.skipWorldSaveDimensions = o.skipWorldSaveDimensions;
      this.skipCaveLikeDimensions = o.skipCaveLikeDimensions;
      this.loadTimeoutSeconds = o.loadTimeoutSeconds;
      this.saveTimeoutSeconds = o.saveTimeoutSeconds;
      this.rebuildBuffersInline = o.rebuildBuffersInline;
      this.settleTimeoutSeconds = o.settleTimeoutSeconds;
      this.debug = o.debug;
      this.diagnostics = o.diagnostics;
      this.autoStart = o.autoStart;
      this.autoStartDelaySeconds = o.autoStartDelaySeconds;
      this.autoRescanOnlyChangedRegions = o.autoRescanOnlyChangedRegions;
      this.paletteRefreshRegions = o.paletteRefreshRegions;
      this.verbose = o.verbose;
      // Generation and streaming were missing here, so /voxymap reload quietly did nothing to the
      // half of the mod most worth reloading. Now that one instance is shared, this is the only
      // path by which an edited file reaches a running generator.
      this.generateChunks = o.generateChunks;
      this.generationRadiusChunks = o.generationRadiusChunks;
      this.generationIntervalTicks = o.generationIntervalTicks;
      this.maxActiveGenerationTasks = o.maxActiveGenerationTasks;
      this.generationMinTps = o.generationMinTps;
      this.lodStreamRadiusSections = o.lodStreamRadiusSections;
      this.lodStreamIntervalTicks = o.lodStreamIntervalTicks;
      this.lodIngestThreads = o.lodIngestThreads;
      this.maxSectionsPerStreamPass = o.maxSectionsPerStreamPass;
      this.clamp();
   }

   private void clamp() {
      this.applyBudgetMillis = Math.max(0.25, Math.min(20.0, this.applyBudgetMillis));
      this.maxOpenRegions = Math.max(1, Math.min(6, this.maxOpenRegions));
      this.maxOverlays = Math.max(1, Math.min(10, this.maxOverlays));
      this.incompleteChunkTolerance = Math.max(0.0, Math.min(1.0, this.incompleteChunkTolerance));
      this.loadTimeoutSeconds = Math.max(1, this.loadTimeoutSeconds);
      this.saveTimeoutSeconds = Math.max(1, this.saveTimeoutSeconds);
      this.settleTimeoutSeconds = Math.max(1, this.settleTimeoutSeconds);
      this.autoStartDelaySeconds = Math.max(0, this.autoStartDelaySeconds);
      this.paletteRefreshRegions = Math.max(1, this.paletteRefreshRegions);

      // 256 chunks is 4096 blocks and ~205k chunks per player. The offset table is quadratic in
      // memory as well as the work being quadratic, so this is a real bound, not a formality.
      this.generationRadiusChunks = Math.max(0, Math.min(dev.local.voxymap.util.RadialOffsets.MAX_RADIUS, this.generationRadiusChunks));
      this.generationIntervalTicks = Math.max(1, this.generationIntervalTicks);
      this.maxActiveGenerationTasks = Math.max(1, Math.min(64, this.maxActiveGenerationTasks));
      this.generationMinTps = Math.max(1.0, Math.min(20.0, this.generationMinTps));

      this.lodStreamRadiusSections = Math.max(0, Math.min(dev.local.voxymap.util.RadialOffsets.MAX_RADIUS, this.lodStreamRadiusSections));
      this.lodStreamIntervalTicks = Math.max(1, this.lodStreamIntervalTicks);
      this.lodIngestThreads = Math.max(1, Math.min(16, this.lodIngestThreads));
      this.maxSectionsPerStreamPass = Math.max(1, Math.min(256, this.maxSectionsPerStreamPass));
   }
}
