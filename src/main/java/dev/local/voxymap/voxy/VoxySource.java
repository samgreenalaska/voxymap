package dev.local.voxymap.voxy;

import dev.local.voxymap.util.Log;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Owns the reference to Voxy's {@link WorldEngine} for the duration of a sweep.
 *
 * <p>Lookup goes through {@code WorldIdentifier.ofEngineNullable(level)}, which routes via the
 * {@code IWorldGetIdentifier} mixin on {@code Level}. That mixin lives in Voxy's <em>common</em>
 * mixin config, so it applies in both singleplayer and multiplayer -- which is the whole reason
 * this bridge can work on servers.
 *
 * <p>{@code getOrCreateEngine()} is deliberately not used: it would fabricate an empty database
 * for a world Voxy is not tracking.
 *
 * <h2>Why the reference has to be dropped off the main thread</h2>
 *
 * <p>Disconnecting runs {@code VoxyInstance.shutdown()} on the render thread, at the TAIL of
 * {@code Minecraft.disconnect}. It ends in
 *
 * <pre>{@code while (world.isWorldUsed()) Thread.sleep(10); }</pre>
 *
 * <p>with no timeout, and {@code isWorldUsed()} is exactly {@code refCount != 0 || cachedSections
 * != 0}. Our reference makes that condition permanently true. The render thread is the same thread
 * that would run the client tick that releases it, so the game deadlocks and the shutdown watchdog
 * kills the process a minute later.
 *
 * <p>There is no client-side event that reliably fires before that TAIL injection --
 * {@code AFTER_CLIENT_LEVEL_CHANGE} returns early on a null level, and the networking disconnect
 * event can land a tick late. So {@link #releaseIfLevelGone()} is called from the sweep worker
 * thread instead, which is both the thread that reads Voxy's storage and a thread Voxy's spin
 * cannot block. It is ordering-independent by construction.
 */
public final class VoxySource {
   private final Object lock = new Object();

   private volatile WorldEngine engine;
   private volatile ClientLevel boundLevel;

   /** @return true if an engine was acquired. */
   public boolean open(ClientLevel level) {
      this.close();
      if (level == null) {
         return false;
      }

      WorldEngine e;
      try {
         e = WorldIdentifier.ofEngineNullable(level);
      } catch (Throwable t) {
         Log.warn("Voxy engine lookup failed", t);
         return false;
      }

      if (e == null) {
         return false;
      }

      synchronized (this.lock) {
         try {
            e.acquireRef();
         } catch (Throwable t) {
            Log.warn("could not acquire a Voxy engine reference", t);
            return false;
         }

         this.engine = e;
         this.boundLevel = level;
         return true;
      }
   }

   /** Idempotent and safe from either thread; only the first caller releases. */
   public void close() {
      synchronized (this.lock) {
         WorldEngine e = this.engine;
         this.engine = null;
         this.boundLevel = null;

         if (e != null) {
            try {
               e.releaseRef();
            } catch (Throwable t) {
               // Throws IllegalStateException if Voxy already freed the world, which is harmless
               // here -- the reference is gone either way.
               Log.warn("releasing the Voxy engine reference failed", t);
            }
         }
      }
   }

   /**
    * Drops the reference the moment the client is no longer in the level it was taken for.
    *
    * <p>Called from the sweep worker between units of work, so it never runs while a
    * {@code WorldSection} is held. See the class comment for why this cannot be a main-thread hook.
    *
    * @return true if the reference had to be released, meaning the caller must stop reading Voxy
    */
   /** Whether the client has left the level this source was opened for. */
   public boolean levelGone() {
      ClientLevel now = Minecraft.getInstance().level;
      return now == null || now != this.boundLevel;
   }

   public boolean releaseIfLevelGone() {
      if (this.engine == null || !this.levelGone()) {
         return false;
      }

      Log.diag("the client left the world; releasing Voxy's engine reference so its shutdown can finish");
      this.close();
      return true;
   }

   public WorldEngine engine() {
      return this.engine;
   }

   public boolean isOpen() {
      return this.engine != null;
   }

   /** The sweep must abort the moment the client swaps levels underneath us. */
   public boolean stillValid() {
      return this.engine != null && this.boundLevel != null && Minecraft.getInstance().level == this.boundLevel;
   }

   /** Read-only probe used by {@code /voxymap status}; does not take a reference. */
   public static WorldEngine peek(ClientLevel level) {
      if (level == null) {
         return null;
      }

      try {
         return WorldIdentifier.ofEngineNullable(level);
      } catch (Throwable t) {
         return null;
      }
   }
}
