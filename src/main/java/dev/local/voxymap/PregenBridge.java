package dev.local.voxymap;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;

/**
 * {@code /voxymap pregen ...} on the client, forwarded to the server that can actually answer it.
 *
 * <h2>Why this indirection exists</h2>
 *
 * <p>Chunk generation is server state, so the command has to run there. But the client registers
 * {@code /voxymap} through Fabric's client command API, and a client-side root **shadows the
 * server's completely**: the client dispatcher parses the whole line, fails on the first literal it
 * does not recognise, and reports "Incorrect argument for command" without ever sending it on. So a
 * server subcommand hanging off {@code /voxymap} is unreachable from any client that has this mod
 * installed -- which is every client that would want it.
 *
 * <p>Rather than leave the user with two roots to remember, the client owns the whole grammar and
 * relays it. The server still registers {@code /voxymapserver}, which is what actually executes and
 * remains the way to drive it from the server console.
 *
 * <p>Arguments are parsed and re-serialised rather than passed through as a raw string, so a
 * malformed value is rejected by the client's own dispatcher with a proper error and tab completion
 * works on the numbers. The ranges here must stay in step with
 * {@code dev.local.voxymap.server.ServerCmd}; they are the same limits {@code VoxyMapConfig.clamp}
 * enforces, so a mismatch is a worse error message rather than a wrong value.
 */
public final class PregenBridge {
   private PregenBridge() {
   }

   public static LiteralArgumentBuilder<FabricClientCommandSource> node() {
      return ClientCommands.literal("pregen")
         .executes(ctx -> send(ctx, "pregen"))
         .then(
            ClientCommands.literal("enabled")
               .then(ClientCommands.argument("value", BoolArgumentType.bool())
                  .executes(ctx -> send(ctx, "pregen enabled " + BoolArgumentType.getBool(ctx, "value"))))
         )
         .then(
            ClientCommands.literal("radius")
               .then(ClientCommands.argument("chunks", IntegerArgumentType.integer(0, 256))
                  .executes(ctx -> send(ctx, "pregen radius " + IntegerArgumentType.getInteger(ctx, "chunks"))))
         )
         .then(
            ClientCommands.literal("interval")
               .then(ClientCommands.argument("ticks", IntegerArgumentType.integer(1, 200))
                  .executes(ctx -> send(ctx, "pregen interval " + IntegerArgumentType.getInteger(ctx, "ticks"))))
         )
         .then(
            ClientCommands.literal("tasks")
               .then(ClientCommands.argument("count", IntegerArgumentType.integer(1, 64))
                  .executes(ctx -> send(ctx, "pregen tasks " + IntegerArgumentType.getInteger(ctx, "count"))))
         )
         .then(
            ClientCommands.literal("mintps")
               .then(ClientCommands.argument("tps", DoubleArgumentType.doubleArg(1.0, 20.0))
                  .executes(ctx -> send(ctx, "pregen mintps " + DoubleArgumentType.getDouble(ctx, "tps"))))
         );
   }

   private static int send(CommandContext<FabricClientCommandSource> ctx, String tail) {
      ClientPacketListener connection = Minecraft.getInstance().getConnection();

      if (connection == null) {
         ctx.getSource().sendFeedback(Component.literal("[voxymap] not connected to a server"));
         return 0;
      }

      // The reply arrives as ordinary chat from the server, so nothing is echoed here -- saying
      // "sent" and then printing the real answer a tick later reads like it ran twice.
      connection.sendCommand("voxymapserver " + tail);
      return 1;
   }
}
