package dev.local.voxymap.xaero;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import xaero.map.MapProcessor;
import xaero.map.WorldMap;
import xaero.map.WorldMapSession;
import xaero.map.region.MapLayer;
import xaero.map.world.MapDimension;

/**
 * Everything this bridge needs to know about Xaero's current state, in one place.
 *
 * <p>No mixins and no reflection: every member used here is already public in
 * xaeroworldmap 1.44.2. The dependency is pinned tightly in fabric.mod.json because that is an
 * accident of the current release, not a supported API.
 */
public final class XaeroBridge {
   /** The surface layer. Cave layers use other values; we only ever write the surface. */
   public static final int SURFACE_LAYER = Integer.MAX_VALUE;

   /** Our marker in MapTile.worldInterpretationVersion. Xaero writes 1 and only ever tests > 0. */
   public static final int VOXY_MARK = 0x42;

   private XaeroBridge() {
   }

   public static WorldMapSession session() {
      try {
         WorldMapSession s = WorldMapSession.getCurrentSession();
         return s != null && s.isUsable() ? s : null;
      } catch (Throwable t) {
         return null;
      }
   }

   public static MapProcessor processor() {
      WorldMapSession s = session();
      return s == null ? null : s.getMapProcessor();
   }

   /**
    * The exact set of conditions {@code MapWriter.onRender} checks before it will touch a region.
    * If Xaero would refuse to write right now, so do we.
    */
   public static String whyNotWritable(MapProcessor mp) {
      if (WorldMap.crashHandler != null && WorldMap.crashHandler.getCrashedBy() != null) {
         return "Xaero's map has crashed (" + WorldMap.crashHandler.getCrashedBy() + ")";
      }

      if (mp == null) {
         return "no map processor";
      }

      if (mp.isWritingPaused()) {
         return "map writing is paused";
      }

      if (mp.isWaitingForWorldUpdate()) {
         return "waiting for a world update";
      }

      if (!mp.getMapSaveLoad().isRegionDetectionComplete()) {
         return "region detection is still running";
      }

      if (!mp.isCurrentMultiworldWritable()) {
         return "the current multiworld is not writable";
      }

      if (mp.getWorld() == null) {
         return "no world";
      }

      if (mp.isCurrentMapLocked()) {
         return "the map is locked";
      }

      if (mp.getMapWorld() == null || mp.getMapWorld().getCurrentDimension() == null) {
         return "no current dimension";
      }

      if (mp.getMapWorld().isCacheOnlyMode()) {
         return "cache-only mode";
      }

      if (mp.getCurrentWorldId() == null) {
         return "no world id yet";
      }

      if (mp.ignoreWorld(mp.getWorld())) {
         return "Xaero is ignoring this world";
      }

      if (mp.getWorld().dimension() != mp.getMapWorld().getCurrentDimensionId()) {
         return "dimension mismatch between the client and the map";
      }

      return null;
   }

   public static boolean writable(MapProcessor mp) {
      return whyNotWritable(mp) == null;
   }

   /**
    * Xaero stores the surface layer's cave start on the layer itself. Read it rather than
    * assuming Integer.MAX_VALUE, so a tile we write matches what Xaero would have written.
    */
   public static int surfaceCaveStart(MapProcessor mp) {
      try {
         MapLayer layer = mp.getMapWorld().getCurrentDimension().getLayeredMapRegions().getLayer(SURFACE_LAYER);
         if (layer != null) {
            return layer.getCaveStart();
         }
      } catch (Throwable ignored) {
      }

      return Integer.MAX_VALUE;
   }

   public static MapDimension dimension(MapProcessor mp) {
      try {
         return mp.getMapWorld().getCurrentDimension();
      } catch (Throwable t) {
         return null;
      }
   }

   /**
    * Xaero reads the region files straight out of the world save in singleplayer, and rebuilds
    * regions from the MCA data on refresh -- which would wipe anything we wrote.
    */
   public static boolean isUsingWorldSave(MapProcessor mp) {
      MapDimension dim = dimension(mp);
      try {
         return dim != null && dim.isUsingWorldSave();
      } catch (Throwable t) {
         return false;
      }
   }

   /**
    * Whether this <em>dimension</em> needs a cave column model, which the surface one would render
    * wrong. The Nether does; the Overworld and End do not.
    *
    * <h2>Why this is not the surface layer's cave start any more</h2>
    *
    * <p>It used to be {@code surfaceCaveStart(mp) != Integer.MAX_VALUE}, which conflated two
    * different things. Xaero sets a cave start on the layer when the <em>player turns cave mode
    * on</em> — a view setting, in whatever dimension they happen to be in. So switching the world
    * map to a cave cross-section in the Overworld made this answer "cave dimension", and the sweep
    * refused to fill anything at all until it was given {@code --force}. Nothing about the terrain
    * had changed; the map was merely being looked at differently.
    *
    * <p>That refusal was also pointless. Tiles are written to {@link #SURFACE_LAYER} explicitly,
    * never to whichever layer is on screen, so filling the surface while the player looks at caves
    * is both correct and invisible until they switch back — which is exactly when they want it
    * already done.
    *
    * <p>What actually decides the column model is the dimension's own shape, so that is what is
    * asked. A ceiling is the vanilla marker for it and is what makes the Nether different.
    *
    * <p>Fails open: if the dimension cannot be identified the answer is "not cave-like", because
    * the surface model is right for every dimension but one, and refusing to draw is the worse
    * mistake of the two.
    */
   public static boolean isCaveLike(MapProcessor mp) {
      ClientLevel level = Minecraft.getInstance().level;

      if (level == null) {
         return false;
      }

      try {
         return level.dimensionType().hasCeiling();
      } catch (Throwable t) {
         return false;
      }
   }

   /** Pool key only -- it is literally the string "placeholder". Pass it through, do not parse it. */
   public static String tilePoolKey(MapProcessor mp) {
      return mp.getCurrentDimension();
   }

   public static boolean onMainThread() {
      return Minecraft.getInstance().isSameThread();
   }
}
