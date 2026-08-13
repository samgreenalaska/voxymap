package dev.local.voxymap.armfix.mixin;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlUtil;
import dev.local.voxymap.armfix.ArmFix;
import dev.local.voxymap.armfix.UploadTracer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;

/**
 * The single point every crashing upload passes through.
 *
 * <pre>
 * org.lwjgl.opengl.GL15C.nglBufferSubData
 * com.mojang.blaze3d.opengl.DirectStateAccess$Emulated.bufferSubData   &lt;- here
 * com.mojang.blaze3d.opengl.GlCommandEncoder.writeToBuffer
 * com.mojang.blaze3d.systems.CommandEncoder.writeToBuffer
 * </pre>
 *
 * <p>{@code Emulated} is package-private, hence {@code targets}. blaze3d is not obfuscated, so the
 * names here are the real ones in every namespace and no refmap is required.
 */
@Mixin(targets = "com.mojang.blaze3d.opengl.DirectStateAccess$Emulated", remap = false)
public abstract class EmulatedBufferUploadMixin {

    @Inject(
            method = "bufferSubData(IJLjava/nio/ByteBuffer;I)V",
            at = @At("HEAD"))
    private void voxymap$traceUpload(int buffer, long offset, ByteBuffer data, int usage, CallbackInfo ci) {
        if (!ArmFix.active() || !ArmFix.traceEnabled()) {
            return;
        }
        UploadTracer.record(buffer, offset, data.remaining(), usage, GlUtil.selectBufferBindTarget(usage));
    }

    /**
     * The fix.
     *
     * <p>{@code selectBufferBindTarget} is called once at the top of {@code bufferSubData} and its
     * result drives the bind, the write and the unbind — so redirecting it here changes all three
     * consistently, which is why this is the injection point rather than the write itself.
     *
     * <p>A buffer that carries no VERTEX/INDEX/UNIFORM usage bit gets routed to
     * {@code GL_COPY_WRITE_BUFFER}, and that is the slot OpenGLOn12 dies on. GL buffer objects are
     * typeless — the bind target only selects which slot the write goes through — so binding the
     * same buffer to {@code GL_ARRAY_BUFFER} writes exactly the same bytes to exactly the same
     * place, over a code path this driver handles 390 times a second without complaint.
     */
    @Redirect(
            method = "bufferSubData(IJLjava/nio/ByteBuffer;I)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/opengl/GlUtil;selectBufferBindTarget(I)I"))
    private int voxymap$chooseBindTarget(int usage) {
        int target = GlUtil.selectBufferBindTarget(usage);
        if (target != ArmFix.GL_COPY_WRITE_BUFFER || !ArmFix.retargetEnabled() || !ArmFix.active()) {
            return target;
        }
        int replacement = ArmFix.retargetTo();
        UploadTracer.noteRetarget(usage, target, replacement);
        return replacement;
    }

    /**
     * Kept for the size hypothesis, which the measurements on this machine did not support. Inert
     * unless {@code chunk=true}, in which case a large upload becomes several smaller ones at
     * increasing offsets.
     */
    @Redirect(
            method = "bufferSubData(IJLjava/nio/ByteBuffer;I)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/opengl/GlStateManager;_glBufferSubData(IJLjava/nio/ByteBuffer;)V"))
    private void voxymap$splitUpload(int target, long offset, ByteBuffer data) {
        int length = data.remaining();
        int chunk = ArmFix.chunkBytes();

        if (!ArmFix.active() || !ArmFix.chunkEnabled()
                || length <= chunk || length < ArmFix.chunkMinBytes()) {
            GlStateManager._glBufferSubData(target, offset, data);
            return;
        }

        int base = data.position();
        int pieces = (length + chunk - 1) / chunk;
        if (ArmFix.traceEnabled()) {
            UploadTracer.noteChunking(length, chunk, pieces);
        }
        // A duplicate shares the same memory but has its own position/limit. LWJGL reads
        // position..limit without consuming, so the caller's buffer is left exactly as vanilla
        // would have left it: untouched.
        ByteBuffer view = data.duplicate();
        for (int done = 0; done < length; done += chunk) {
            view.limit(base + Math.min(done + chunk, length)).position(base + done);
            GlStateManager._glBufferSubData(target, offset + done, view);
        }
    }
}
