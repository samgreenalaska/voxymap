package dev.local.voxymap.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.local.voxymap.VoxyMapConfig;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * Server-side {@code /voxymap pregen}, for tuning chunk generation without a restart.
 *
 * <p>Exists because the settings that decide how fast the world grows lived only in
 * {@code config/voxymap.json} on the server, and a config file is a poor place for a number whose
 * right value depends on how much headroom the machine happens to have. Worse, the file wins over
 * the code's defaults, so raising a default in a new build changes nothing on a server that already
 * has a config -- which is exactly how the generator ended up running at a sixth of its intended
 * rate after the interval default was lowered.
 *
 * <p>Changes apply immediately: {@link ChunkPregen} reads the same {@link VoxyMapConfig} instance
 * every pass rather than caching it. They are also written back to disk, so they survive a restart.
 *
 * <p>Op-gated at permission level 2, the same bar vanilla uses for gamerules.
 */
public final class ServerCmd {
   private ServerCmd() {
   }

   public static void register(VoxyMapConfig config, ChunkPregen pregen, LodStreamer streamer) {
      CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> build(dispatcher, config, pregen, streamer));
   }

   private static void build(CommandDispatcher<CommandSourceStack> dispatcher, VoxyMapConfig config, ChunkPregen pregen, LodStreamer streamer) {
      // Deliberately NOT "voxymap". The client half registers that root through Fabric's client
      // command API, and a client-side root shadows the server's completely: the client dispatcher
      // parses the whole line, fails on the first literal it does not recognise, and reports
      // "Incorrect argument for command" without ever sending it on. So a server subcommand hung
      // off /voxymap is unreachable from a client that has this mod installed -- which is every
      // client that would want it.
      dispatcher.register(
         Commands.literal("voxymapserver")
            // 26.2 replaced integer permission levels with PermissionCheck. GAMEMASTERS is the
            // level-2 equivalent, which is the bar vanilla uses for gamerules.
            .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
            .then(
               Commands.literal("pregen")
                  .executes(ctx -> status(ctx, config, pregen))
                  .then(
                     Commands.literal("enabled")
                        .then(Commands.argument("value", BoolArgumentType.bool()).executes(ctx -> {
                           config.generateChunks = BoolArgumentType.getBool(ctx, "value");
                           return applied(ctx, config, pregen, "enabled");
                        }))
                  )
                  .then(
                     Commands.literal("radius")
                        .then(Commands.argument("chunks", IntegerArgumentType.integer(0, 256)).executes(ctx -> {
                           config.generationRadiusChunks = IntegerArgumentType.getInteger(ctx, "chunks");
                           pregen.onRadiusChanged();
                           return applied(ctx, config, pregen, "radius");
                        }))
                  )
                  .then(
                     Commands.literal("interval")
                        .then(Commands.argument("ticks", IntegerArgumentType.integer(1, 200)).executes(ctx -> {
                           config.generationIntervalTicks = IntegerArgumentType.getInteger(ctx, "ticks");
                           return applied(ctx, config, pregen, "interval");
                        }))
                  )
                  .then(
                     Commands.literal("tasks")
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 64)).executes(ctx -> {
                           config.maxActiveGenerationTasks = IntegerArgumentType.getInteger(ctx, "count");
                           return applied(ctx, config, pregen, "tasks");
                        }))
                  )
                  .then(
                     Commands.literal("mintps")
                        .then(Commands.argument("tps", DoubleArgumentType.doubleArg(1.0, 20.0)).executes(ctx -> {
                           config.generationMinTps = DoubleArgumentType.getDouble(ctx, "tps");
                           return applied(ctx, config, pregen, "mintps");
                        }))
                  )
            )
            .then(
               // The escape hatch for the delta. It forgets what every connected player was
               // believed to hold, so the next lap re-sends everything in range -- the protocol-1
               // behaviour, on demand. Safe by construction: nothing here can hide data from a
               // client, only cost bandwidth. If the map is ever wrong on a client and a reconnect
               // does not fix it, this is the first thing to try.
               Commands.literal("resend").executes(ctx -> {
                  int forgotten = streamer.resend();
                  ctx.getSource().sendSuccess(
                     () -> Component.literal("[voxymap] forgot " + forgotten + " column receipts; everything in range will be sent again"),
                     true
                  );
                  return 1;
               })
            )
      );
   }

   private static int applied(CommandContext<CommandSourceStack> ctx, VoxyMapConfig config, ChunkPregen pregen, String what) {
      config.save();
      ctx.getSource().sendSuccess(() -> Component.literal("[voxymap] " + what + " updated. " + pregen.status()), true);
      return 1;
   }

   private static int status(CommandContext<CommandSourceStack> ctx, VoxyMapConfig config, ChunkPregen pregen) {
      double mspt = ctx.getSource().getServer().getAverageTickTimeNanos() / 1_000_000.0;

      // The effective count, not the configured one. The fill-speed preset scales it -- Background
      // quarters it -- so printing the raw number made the line under it overstate the ceiling by
      // four, which is exactly the sort of thing this command exists to stop.
      int tasks = ChunkPregen.effectiveTasks(config);

      ctx.getSource().sendSuccess(() -> Component.literal(
         "[voxymap] " + pregen.status()
            + "\n  " + VoxyMapServer.ingestStatus()
            + "\n  " + VoxyMapServer.streamStatus()
            + "\n  enabled=" + config.generateChunks
            + " radius=" + config.generationRadiusChunks
            + " interval=" + config.generationIntervalTicks + "t"
            + " tasks=" + tasks + (tasks == config.maxActiveGenerationTasks ? "" : " (config " + config.maxActiveGenerationTasks + ", scaled by fill speed " + config.fillSpeed() + ")")
            + " minTps=" + config.generationMinTps
            + String.format("%n  server is at %.1f ms/tick; generation pauses above %.1f",
               mspt, 1000.0 / Math.max(1.0, config.generationMinTps))
            + "\n  peak rate is roughly " + (20 / Math.max(1, config.generationIntervalTicks)) * tasks
            + " chunks/s -- raise tasks first, then lower interval"
      ), false);

      return 1;
   }
}
