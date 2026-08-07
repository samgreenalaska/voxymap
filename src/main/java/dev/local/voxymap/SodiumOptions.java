package dev.local.voxymap;

import dev.local.voxymap.util.Log;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionGroupBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionPageBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Adds a VoxyMap section to the video settings screen, next to Voxy's own.
 *
 * <p>Sodium replaces the vanilla video settings screen and exposes this API for mods to add their
 * own pages; Voxy registers through the same entrypoint, which is why its settings appear there.
 *
 * <p>Values are written straight through to {@link VoxyMapConfig} and persisted on apply, so the
 * screen and {@code config/voxymap.json} never disagree.
 *
 * <p><b>Registration order matters.</b> Sodium validates each option as it is added to a group,
 * but validates the mod entry itself later, during its own build pass. So the page is assembled
 * first and {@code registerOwnModOptions()} is called last: if anything here throws, no mod entry
 * exists and Sodium has nothing half-built to choke on. Doing it the other way round crashed the
 * game with "At least one page, option override, or option overlay must be added" — the catch
 * below swallowed the real error and left the empty entry behind.
 */
public final class SodiumOptions implements ConfigEntryPoint {
   private static final String MOD_ID = "voxymap";

   @Override
   public void registerConfigLate(ConfigBuilder builder) {
      VoxyMapConfig config = VoxyMapClient.config();
      if (config == null) {
         return;
      }

      OptionPageBuilder page;

      try {
         OptionGroupBuilder refresh = builder.createOptionGroup()
            .setName(Component.literal("Automatic map filling"))
            .addOption(
               builder.createBooleanOption(Identifier.parse(MOD_ID + ":auto_start"))
                  .setName(Component.literal("Fill map from Voxy"))
                  .setTooltip(
                     Component.literal(
                        "Automatically project Voxy's stored terrain onto Xaero's world map after "
                           + "joining a world, and continuously thereafter as Voxy discovers new ground. "
                           + "Turning this off leaves the mod idle until you run /voxymap start."
                     )
                  )
                  .setImpact(OptionImpact.LOW)
                  .setDefaultValue(true)
                  .setBinding(v -> config.autoStart = v, () -> config.autoStart)
                  .setStorageHandler(config::save)
            );

         refresh.addOption(
            builder.createIntegerOption(Identifier.parse(MOD_ID + ":chunkgen_distance"))
               .setName(Component.literal("Chunkgen distance"))
               .setTooltip(
                  Component.literal(
                     "How far around you to load and generate world chunks, so there is terrain to "
                        + "put on the map instead of only what you have walked through. Off does no "
                        + "worldgen at all; the map then fills only from ground you visit.\n\n"
                        + "Cost grows with the square of this: 64 chunks is a disc of about 12,900, "
                        + "256 is about 205,000, and the maximum is 13 million. Generation backs off "
                        + "on its own when the framerate, the tick rate or the garbage collector says "
                        + "the game needs the room, but it cannot make a very large distance cheap.\n\n"
                        + "On a multiplayer server this is the server's own setting -- use "
                        + "/voxymapserver pregen there."
                  )
               )
               .setImpact(OptionImpact.HIGH)
               // The slider carries a notch index, not a chunk count. See DISTANCE_NOTCHES.
               .setRange(0, DISTANCE_NOTCHES.length - 1, 1)
               .setDefaultValue(notchOf(64))
               .setValueFormatter(v -> {
                  int chunks = chunksAt(v);
                  return Component.literal(chunks == 0 ? "Off" : chunks + " chunks");
               })
               .setBinding(
                  v -> config.setChunkgenDistance(chunksAt(v)),
                  () -> notchOf(config.chunkgenDistance())
               )
               .setStorageHandler(config::save)
         );

         OptionGroupBuilder budget = builder.createOptionGroup()
            .setName(Component.literal("Performance"))
            .addOption(
               // Replaces a raw "Main-thread budget, N ms/tick" slider. That asked the player to
               // pick a number whose meaning depends on what the apply stage does and how Xaero's
               // loader is paced, and whose failure mode -- picking too low -- is invisible: the
               // map just fills slower. The raw values are still in voxymap.json for tuning.
               builder.createEnumOption(Identifier.parse(MOD_ID + ":fill_speed"), VoxyMapConfig.FillSpeed.class)
                  .setName(Component.literal("Map filling speed"))
                  .setElementNameProvider(
                     v -> Component.literal(
                        switch (v) {
                           case BACKGROUND -> "Background";
                           case BALANCED -> "Balanced";
                           case FAST -> "Fast";
                        }
                     )
                  )
                  .setTooltip(
                     v -> Component.literal(
                        switch (v) {
                           case BACKGROUND -> "Fills the map slowly and stays out of the way. Pick this if you "
                              + "notice the game stuttering while the map fills in.";
                           case BALANCED -> "Fills the map at a steady rate for a small share of the frame time. "
                              + "The default, and what the mod is tuned against.";
                           case FAST -> "Fills the map as quickly as the map mod will accept it. Costs noticeably "
                              + "more frame time while it is working -- useful for catching up on a lot of "
                              + "newly explored ground at once.";
                        }
                     )
                  )
                  .setImpact(OptionImpact.MEDIUM)
                  .setDefaultValue(VoxyMapConfig.FillSpeed.BALANCED)
                  .setBinding(config::setFillSpeed, config::fillSpeed)
                  .setStorageHandler(config::save)
            )
            .addOption(
               builder.createIntegerOption(Identifier.parse(MOD_ID + ":pause_fps"))
                  .setName(Component.literal("Pause below FPS"))
                  .setTooltip(
                     Component.literal("Stop filling the map while the framerate is under this. Set to 0 to never pause.")
                  )
                  .setImpact(OptionImpact.LOW)
                  .setRange(0, 240, 5)
                  .setDefaultValue(45)
                  // A threshold at or above the framerate cap can never be satisfied, so it quietly
                  // means "never fill" -- which is exactly how someone who set this to the maximum
                  // while chasing a stutter ends up with a map that stopped filling and nothing on
                  // screen saying why. Put it on the row rather than in a log line.
                  .setValueFormatter(v -> {
                     if (v == 0) {
                        return Component.literal("Never");
                     }

                     int cap = framerateCap();

                     return Component.literal(cap > 0 && v >= cap ? v + " fps (never fills)" : v + " fps");
                  })
                  .setBinding(v -> config.pauseWhenFpsBelow = v, () -> config.pauseWhenFpsBelow)
                  .setStorageHandler(config::save)
            );

         page = builder.createOptionPage().setName(Component.literal("VoxyMap")).addOptionGroup(refresh).addOptionGroup(budget);
      } catch (Throwable t) {
         // Nothing has been registered with Sodium at this point, so bailing out is clean.
         Log.warn("could not build the VoxyMap video settings page; it will not appear", t);
         return;
      }

      // Only now does a mod entry come into existence, and it has a page from the outset.
      builder.registerOwnModOptions().setName("VoxyMap").setVersion(version()).addPage(page);
   }

   /**
    * The Chunkgen distances the slider can stop at, in chunks.
    *
    * <p>Sodium's integer slider is linear, and a linear 0-2048 puts every value anyone actually
    * wants in the first six pixels: at this window size 32 and 64 were two pixels apart, so the
    * useful part of the control was the part you could not aim at. The slider therefore carries an
    * index into this table rather than a chunk count, which makes the scale roughly geometric --
    * each notch is about half as many again as the one before, so the low end gets most of the
    * travel and the top end stays reachable.
    *
    * <p>Round numbers rather than a computed logarithm, because a true log curve lands on 19, 23,
    * 27 and reads like a rounding bug. The cost is real either way: the last notch is a disc of
    * 13.2 million chunks.
    */
   private static final int[] DISTANCE_NOTCHES = {
      0, 16, 24, 32, 48, 64, 96, 128, 192, 256, 384, 512, 768, 1024, 1536, 2048
   };

   private static int chunksAt(int notch) {
      return DISTANCE_NOTCHES[Math.max(0, Math.min(DISTANCE_NOTCHES.length - 1, notch))];
   }

   /**
    * The notch nearest a chunk count, so a hand-edited config still shows as something sensible
    * rather than snapping the file to a default the moment the screen is opened.
    */
   private static int notchOf(int chunks) {
      int best = 0;
      int bestDistance = Integer.MAX_VALUE;

      for (int i = 0; i < DISTANCE_NOTCHES.length; i++) {
         int distance = Math.abs(DISTANCE_NOTCHES[i] - chunks);

         if (distance < bestDistance) {
            bestDistance = distance;
            best = i;
         }
      }

      return best;
   }

   /**
    * The framerate the game is capped to, or 0 if it is uncapped or unknown.
    *
    * <p>Vanilla's slider treats anything at or past {@code UNLIMITED_FRAMERATE_CUTOFF} as no limit,
    * so that reads back as 0 here -- no cap means no threshold is unreachable.
    */
   private static int framerateCap() {
      try {
         net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();

         if (mc == null || mc.options == null) {
            return 0;
         }

         int limit = mc.options.framerateLimit().get();

         return limit >= net.minecraft.client.Options.UNLIMITED_FRAMERATE_CUTOFF ? 0 : limit;
      } catch (Throwable t) {
         return 0;
      }
   }

   /** Read from the jar rather than written here, where it went stale twice. */
   private static String version() {
      try {
         return net.fabricmc.loader.api.FabricLoader.getInstance()
            .getModContainer(MOD_ID)
            .map(c -> c.getMetadata().getVersion().getFriendlyString())
            .orElse("unknown");
      } catch (Throwable t) {
         return "unknown";
      }
   }
}
