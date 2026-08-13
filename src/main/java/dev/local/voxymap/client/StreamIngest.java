package dev.local.voxymap.client;

import dev.local.voxymap.net.SectionCodec;
import java.nio.file.Path;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Where sections streamed from the server end up, behind one interface so {@link LodClient} names
 * neither database.
 *
 * <p>That is the whole point of the interface, and it is not stylistic. On Windows-on-ARM, Voxy is
 * not installed at all, so a class whose method descriptors mention {@code WorldEngine} may fail
 * verification the moment it is touched — and {@code LodClient} is touched on every packet. Keeping
 * both implementations behind this line means the client loads exactly one of them, chosen at
 * startup from what is actually installed.
 */
public interface StreamIngest {
   /** What the client thread needs once a background declaration walk is done. */
   interface Declaration {
      boolean done();

      LodDeclareWorker.Result result();

      Throwable error();
   }

   /** Called when a column is genuinely held, which for some backends is well after it arrived. */
   interface Receipt {
      void bank(long columnKey, long hash, long ySet);
   }

   /** Whether the database is up and can be written to for this level. */
   boolean ready(ClientLevel level);

   /**
    * Where {@code voxymap-have.bin} lives for this world: inside the database it describes.
    *
    * @return null if it cannot be worked out, which declares nothing and costs a resend
    */
   Path storeFolder(ClientLevel level);

   /** The database's own id for this world, written into the store header and checked on load. */
   String worldId(ClientLevel level);

   /** Starts the background walk that decides what this client can honestly claim. */
   Declaration declare(ClientLevel level, Path folder, String worldId);

   /**
    * Takes one decoded section.
    *
    * @return false if it could not be used, which poisons the column so it is not recorded
    */
   boolean write(ClientLevel level, SectionCodec.Decoded decoded);

   /**
    * The server says this column is complete.
    *
    * @return true if the receipt should be banked now; false if the backend will bank it itself
    *     once the column is really in the database, or never if it could not be
    */
   boolean columnComplete(ClientLevel level, long columnKey, long hash, long ySet);

   /** Forget anything part-built: a different world's sections must not join this one's. */
   void reset();

   /** Backend-specific counters for {@code /voxymap status}, or an empty string. */
   String status();
}
