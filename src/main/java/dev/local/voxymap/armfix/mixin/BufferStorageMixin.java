package dev.local.voxymap.armfix.mixin;

import com.mojang.renderpearl.backend.opengl.BufferStorage;
import dev.local.voxymap.armfix.ArmFix;
import org.lwjgl.opengl.GLCapabilities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Forces blaze3d onto its mutable buffer storage path.
 *
 * <p>Measured on OpenGLOn12: the bind target and the upload size are both innocent. What separates
 * the one upload that kills the process from the 1214 before it is how the buffer was created.
 * {@code GlConst.bufferUsageToGlFlag} turns blaze3d's usage bits into {@code glBufferStorage} flags:
 *
 * <pre>
 *   USAGE_MAP_WRITE (2)            -> GL_MAP_WRITE_BIT | GL_MAP_PERSISTENT_BIT   (0x42)
 *   USAGE_HINT_CLIENT_STORAGE (4)  -> GL_CLIENT_STORAGE_BIT                      (0x200)
 *   USAGE_COPY_DST (8)             -> GL_DYNAMIC_STORAGE_BIT                     (0x100)
 * </pre>
 *
 * Every buffer that survives here is plain {@code GL_DYNAMIC_STORAGE_BIT}. The one that faults —
 * Xaero's map tile PBO, usage 30 — is immutable storage that is also persistently mappable and
 * client-side hinted. {@code glBufferSubData} into that combination writes through a near-null
 * pointer inside the driver.
 *
 * <p>Rather than fight the flags, this takes the door vanilla already provides: when
 * {@code GL_ARB_buffer_storage} is absent, blaze3d uses {@code BufferStorage.Mutable}, which
 * allocates with {@code glBufferData} and tells {@code GlBuffer.Direct} that persistent mapping is
 * unavailable. Hiding the extension on the affected driver selects that path for every buffer, with
 * no flag rewriting and no behaviour blaze3d does not already support.
 */
@Mixin(value = BufferStorage.class, remap = false)
public class BufferStorageMixin {

    /** {@code Opcodes.GETFIELD}; spelled out because asm is not on this mod's compile classpath. */
    private static final int GETFIELD = 180;

    /** blaze3d rebuilds its device on resource reloads, so this runs more than once per session. */
    private static boolean reported;

    @Redirect(
            method = "create(Lorg/lwjgl/opengl/GLCapabilities;Ljava/util/Set;Z)Lcom/mojang/renderpearl/backend/opengl/BufferStorage;",
            at = @At(
                    value = "FIELD",
                    opcode = GETFIELD,
                    target = "Lorg/lwjgl/opengl/GLCapabilities;GL_ARB_buffer_storage:Z"))
    private static boolean voxymap$hideBufferStorage(GLCapabilities capabilities) {
        boolean supported = capabilities.GL_ARB_buffer_storage;
        if (supported && ArmFix.mutableBufferStorage() && ArmFix.active()) {
            if (!reported) {
                reported = true;
                System.out.println("[voxymap armfix] hiding GL_ARB_buffer_storage -> blaze3d will use mutable "
                        + "buffer storage (glBufferData) for every buffer");
            }
            return false;
        }
        return supported;
    }
}
