package dev.local.voxymap;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.local.voxymap.sweep.DebugReport;
import dev.local.voxymap.sweep.SweepController;
import dev.local.voxymap.util.Log;
import dev.local.voxymap.xaero.Diagnostics;
import dev.local.voxymap.xaero.XaeroBridge;
import java.util.List;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import xaero.map.MapProcessor;

/** {@code /voxymap ...} -- client-side only, so it works on servers too. */
public final class Cmd {
   private Cmd() {
   }

   public static void register(SweepController sweep, VoxyMapConfig config) {
      ClientCommandRegistrationCallback.EVENT
         .register(
            (dispatcher, access) -> dispatcher.register(
               root(sweep, config)
            )
         );
   }

   private static LiteralArgumentBuilder<FabricClientCommandSource> root(SweepController sweep, VoxyMapConfig config) {
      // The command tree is built once, at startup, so this reads the flag as it was on load.
      // Flipping it needs a restart -- which is said out loud in the help text and the config.
      boolean debug = config.debug;

      LiteralArgumentBuilder<FabricClientCommandSource> root = ClientCommands.literal("voxymap")
         .executes(ctx -> {
            reply(
               ctx.getSource(),
               "/voxymap start [--reauthor] [--force] | stop | status | auto <on|off> | reload | pregen"
                  + (debug ? " | debug [x] [z] | probe [x] [z] | here [offsetChunks] | region <x> <z> | wipe" : "")
            );
            return 1;
         })
         // Server state, relayed rather than executed here -- see PregenBridge for why it cannot
         // simply live on the server's own /voxymap.
         .then(PregenBridge.node())
         .then(
            ClientCommands.literal("start")
               .executes(ctx -> run(ctx.getSource(), sweep.start(false, false)))
               .then(
                  ClientCommands.literal("--reauthor")
                     .executes(ctx -> run(ctx.getSource(), sweep.start(true, false)))
                     .then(ClientCommands.literal("--force").executes(ctx -> run(ctx.getSource(), sweep.start(true, true))))
               )
               .then(
                  ClientCommands.literal("--force")
                     .executes(ctx -> run(ctx.getSource(), sweep.start(false, true)))
                     .then(ClientCommands.literal("--reauthor").executes(ctx -> run(ctx.getSource(), sweep.start(true, true))))
               )
         )
         .then(ClientCommands.literal("stop").executes(ctx -> run(ctx.getSource(), sweep.stop())))
         .then(
            ClientCommands.literal("status")
               .executes(ctx -> {
                  for (String line : sweep.status()) {
                     reply(ctx.getSource(), line);
                  }

                  return 1;
               })
         )
         .then(
            ClientCommands.literal("auto")
               .then(ClientCommands.literal("on").executes(ctx -> run(ctx.getSource(), sweep.setAuto(true))))
               .then(ClientCommands.literal("off").executes(ctx -> run(ctx.getSource(), sweep.setAuto(false))))
         )
         .then(
            ClientCommands.literal("reload")
               .executes(ctx -> {
                  VoxyMapConfig fresh = VoxyMapConfig.load();
                  VoxyMapClient.replaceConfig(fresh);
                  reply(ctx.getSource(), "Reloaded config/voxymap.json."
                     + (fresh.debug == debug ? "" : " Note: the debug commands only appear or disappear on restart."));
                  return 1;
               })
         );

      if (!debug) {
         return root;
      }

      return root
         .then(
            ClientCommands.literal("wipe")
               .executes(ctx -> {
                  replyAll(ctx.getSource(), MapWipe.describe());
                  return 1;
               })
               .then(ClientCommands.literal("confirm").executes(ctx -> run(ctx.getSource(), MapWipe.arm())))
         )
         .then(
            ClientCommands.literal("debug")
               .executes(ctx -> debugReport(ctx.getSource(), sweep, config, null, null))
               .then(
                  ClientCommands.argument("x", IntegerArgumentType.integer())
                     .then(
                        ClientCommands.argument("z", IntegerArgumentType.integer())
                           .executes(
                              ctx -> debugReport(
                                 ctx.getSource(),
                                 sweep,
                                 config,
                                 IntegerArgumentType.getInteger(ctx, "x"),
                                 IntegerArgumentType.getInteger(ctx, "z")
                              )
                           )
                     )
               )
         )
         .then(
            ClientCommands.literal("here")
               .executes(ctx -> here(ctx.getSource(), sweep, 0))
               .then(
                  ClientCommands.argument("offsetChunks", IntegerArgumentType.integer(0, 4096))
                     .executes(ctx -> here(ctx.getSource(), sweep, IntegerArgumentType.getInteger(ctx, "offsetChunks")))
               )
         )
         .then(
            ClientCommands.literal("region")
               .then(
                  ClientCommands.argument("x", IntegerArgumentType.integer())
                     .then(
                        ClientCommands.argument("z", IntegerArgumentType.integer())
                           .executes(
                              ctx -> run(
                                 ctx.getSource(),
                                 sweep.startRegion(IntegerArgumentType.getInteger(ctx, "x"), IntegerArgumentType.getInteger(ctx, "z"), false, false)
                              )
                           )
                           .then(
                              ClientCommands.literal("--force")
                                 .executes(
                                    ctx -> run(
                                       ctx.getSource(),
                                       sweep.startRegion(
                                          IntegerArgumentType.getInteger(ctx, "x"), IntegerArgumentType.getInteger(ctx, "z"), false, true
                                       )
                                    )
                                 )
                           )
                     )
               )
         )
         .then(
            ClientCommands.literal("probe")
               .executes(ctx -> probe(ctx.getSource(), null, null))
               .then(
                  ClientCommands.argument("x", IntegerArgumentType.integer())
                     .then(
                        ClientCommands.argument("z", IntegerArgumentType.integer())
                           .executes(
                              ctx -> probe(
                                 ctx.getSource(), IntegerArgumentType.getInteger(ctx, "x"), IntegerArgumentType.getInteger(ctx, "z")
                              )
                           )
                     )
               )
         );
   }

   /**
    * The one command to run when a bit of the map looks wrong. Answers "whose fault is this" by
    * putting Xaero's per-chunk tile ownership next to what Voxy actually stored underneath it.
    */
   private static int debugReport(FabricClientCommandSource source, SweepController sweep, VoxyMapConfig config, Integer x, Integer z) {
      int rx;
      int rz;

      if (x != null && z != null) {
         rx = x;
         rz = z;
      } else {
         LocalPlayer player = Minecraft.getInstance().player;
         if (player == null) {
            reply(source, "Not in a world.");
            return 0;
         }

         rx = player.blockPosition().getX() >> 9;
         rz = player.blockPosition().getZ() >> 9;
      }

      for (String line : DebugReport.build(sweep, config, rx, rz)) {
         reply(source, line);
         Log.info("debug: " + line);
      }

      return 1;
   }

   /**
    * Authors a single tile chunk. With an offset it targets ground that far away in the direction
    * the player is facing, which is how you aim at somewhere genuinely unexplored for a first test.
    */
   private static int here(FabricClientCommandSource source, SweepController sweep, int offsetChunks) {
      LocalPlayer player = Minecraft.getInstance().player;
      if (player == null) {
         reply(source, "Not in a world.");
         return 0;
      }

      double yaw = Math.toRadians(player.getYRot());
      double dirX = -Math.sin(yaw);
      double dirZ = Math.cos(yaw);

      int chunkX = (int)Math.floor(player.getX() + dirX * offsetChunks * 16.0) >> 4;
      int chunkZ = (int)Math.floor(player.getZ() + dirZ * offsetChunks * 16.0) >> 4;

      String msg = sweep.startTileChunk(chunkX >> 2, chunkZ >> 2, false, false);
      reply(source, msg + " (chunk " + chunkX + "," + chunkZ + " -> tile chunk " + (chunkX >> 2) + "," + (chunkZ >> 2) + ")");
      return 1;
   }

   /**
    * Dumps Xaero's live in-memory state for a region. This is the direct read on whether a region
    * is still renderable after a sweep, as opposed to merely saved -- which is exactly the
    * distinction the singleplayer and multiplayer symptoms turn on.
    */
   private static int probe(FabricClientCommandSource source, Integer x, Integer z) {
      MapProcessor mp = XaeroBridge.processor();
      if (mp == null) {
         reply(source, "Xaero has no active session.");
         return 0;
      }

      int rx;
      int rz;

      if (x != null && z != null) {
         rx = x;
         rz = z;
      } else {
         LocalPlayer player = Minecraft.getInstance().player;
         if (player == null) {
            reply(source, "Not in a world.");
            return 0;
         }

         rx = player.blockPosition().getX() >> 9;
         rz = player.blockPosition().getZ() >> 9;
      }

      for (String line : Diagnostics.probe(mp, rx, rz)) {
         reply(source, line);
         Log.info("probe: " + line);
      }

      return 1;
   }

   private static int run(FabricClientCommandSource source, String message) {
      reply(source, message);
      return 1;
   }

   private static void reply(FabricClientCommandSource source, String message) {
      source.sendFeedback(Component.literal(message));
   }

   public static void replyAll(FabricClientCommandSource source, List<String> lines) {
      for (String line : lines) {
         reply(source, line);
      }
   }
}
