package dev.local.voxymap.client;

import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import dev.local.voxymap.dh.DhBridge;
import dev.local.voxymap.dh.DhIngest;
import dev.local.voxymap.net.SectionCodec;
import java.nio.file.Path;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Writes streamed sections into Distant Horizons, by building chunks out of them.
 *
 * <p>The work is in {@link DhIngest}; this is the seam that lets {@link LodClient} drive it without
 * naming a DH class. Two things differ from the Voxy path and both are visible here:
 *
 * <ul>
 *   <li><b>A column is not held when it arrives.</b> Sections are accumulated and built on a worker,
 *       so {@link #columnComplete} answers "not yet" and DH's own acceptance banks the receipt
 *       later. Recording earlier would be recording what the client meant to have.
 *   <li><b>The store lives in DH's save folder.</b> Same reasoning as the Voxy path putting it in
 *       Voxy's: a claim about a database has to die with that database, or a deleted world leaves a
 *       client declaring ground it no longer holds and a server declining to re-send it (§7.16).
 * </ul>
 */
public final class DhStreamIngest implements StreamIngest {
   public DhStreamIngest(Receipt receipt) {
      DhIngest.start(receipt::bank);
   }

   @Override
   public boolean ready(ClientLevel level) {
      return DhBridge.levelFor(level) != null;
   }

   @Override
   public Path storeFolder(ClientLevel level) {
      return folderFor(level);
   }

   @Override
   public String worldId(ClientLevel level) {
      return worldIdFor(level);
   }

   /** Static so {@code MapWipe} can name the same file without starting an ingest thread. */
   public static Path folderFor(ClientLevel level) {
      IDhApiLevelWrapper wrapper = DhBridge.levelFor(level);

      if (wrapper == null) {
         return null;
      }

      try {
         java.io.File folder = wrapper.getDhSaveFolder();
         return folder == null ? null : folder.toPath();
      } catch (Throwable t) {
         return null;
      }
   }

   /**
    * DH's own identifier for the level.
    *
    * <p>Not the dimension name: a server can present several levels of the same dimension type, and
    * DH keys its folders off more than the name. Using the same string DH does means the header
    * check catches a store that has ended up beside the wrong database.
    */
   public static String worldIdFor(ClientLevel level) {
      IDhApiLevelWrapper wrapper = DhBridge.levelFor(level);
      return wrapper == null ? null : wrapper.getDhIdentifier();
   }

   @Override
   public Declaration declare(ClientLevel level, Path folder, String worldId) {
      DhDeclareWorker worker = new DhDeclareWorker(level, DhBridge.levelFor(level), folder, worldId);

      return new Declaration() {
         @Override
         public boolean done() {
            return worker.done();
         }

         @Override
         public LodDeclareWorker.Result result() {
            return worker.result();
         }

         @Override
         public Throwable error() {
            return worker.error();
         }
      };
   }

   @Override
   public boolean write(ClientLevel level, SectionCodec.Decoded decoded) {
      return DhIngest.accept(decoded, level);
   }

   @Override
   public boolean columnComplete(ClientLevel level, long columnKey, long hash, long ySet) {
      DhIngest.complete(columnKey, hash, ySet, level);
      // Never here: DhIngest banks the receipt once DH has taken the chunks, and does not bank it
      // at all if the build was dropped. A column the client cannot prove it holds is one the
      // server should send again.
      return false;
   }

   @Override
   public void reset() {
      DhIngest.reset();
   }

   @Override
   public String status() {
      return " " + DhIngest.status();
   }
}
