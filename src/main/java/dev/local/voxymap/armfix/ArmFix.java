package dev.local.voxymap.armfix;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Compatibility shim for the Windows-on-ARM OpenGL translation layer (OpenGLOn12).
 *
 * <p>On Snapdragon X / Adreno the driver faults inside {@code glBufferSubData} when certain mods
 * upload large buffers. That call lands in {@code DirectStateAccess$Emulated.bufferSubData} — the
 * emulated path blaze3d picks because OpenGLOn12 does not expose {@code GL_ARB_direct_state_access}.
 *
 * <p>Two behaviours, both inert unless the affected driver is detected:
 * <ul>
 *   <li>{@code trace} — record every buffer upload so the failing one can be identified</li>
 *   <li>{@code chunk} — split large uploads into smaller {@code glBufferSubData} calls</li>
 * </ul>
 */
public final class ArmFix {
    /**
     * Its own properties file rather than a section of {@code voxymap.json}.
     *
     * <p>Not tidiness: blaze3d builds its GlDevice before mod initialisers run, so this config has
     * to be readable from a mixin during startup, before anything of voxymap's exists. A flat
     * properties file read on demand has no initialisation order to get wrong.
     */
    public static final String CONFIG_NAME = "voxymap-armfix.properties";

    /** Where the trace goes, under voxymap's own folder. */
    public static final String TRACE_DIR = "voxymap";
    public static final String TRACE_SUBDIR = "armfix";

    private static final int GL_VENDOR = 0x1F00;
    private static final int GL_RENDERER = 0x1F01;
    private static final int GL_VERSION = 0x1F02;

    public static final int GL_ARRAY_BUFFER = 34962;
    public static final int GL_COPY_WRITE_BUFFER = 36663;

    /** enable=auto|always|never */
    private static String enableMode = "auto";
    private static boolean traceEnabled = true;
    private static boolean chunkEnabled = false;
    private static int chunkBytes = 1024 * 1024;
    private static int chunkMinBytes = 0;
    private static boolean retargetEnabled = false;
    private static int retargetTo = GL_ARRAY_BUFFER;
    private static boolean mutableBufferStorage = true;

    /** Resolved on the first upload, when a GL context definitely exists. 0=unknown 1=active 2=inert */
    private static volatile int state = 0;
    private static String rendererString = "<not yet queried>";

    private ArmFix() {
    }

    private static volatile boolean configLoaded;

    /**
     * blaze3d builds its GlDevice — and therefore picks its buffer storage — early enough that it
     * can beat the mod's init entrypoint, so every entry point into this class loads the config
     * itself rather than trusting initialisation order.
     */
    public static synchronized void ensureConfig() {
        if (!configLoaded) {
            configLoaded = true;
            loadConfig();
        }
    }

    public static void loadConfig() {
        configLoaded = true;
        Path file = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_NAME);
        Properties p = new Properties();
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                p.load(in);
            } catch (IOException e) {
                System.err.println("[voxymap armfix] could not read " + file + ": " + e);
            }
        } else {
            writeDefaultConfig(file);
        }
        enableMode = p.getProperty("enable", "auto").trim().toLowerCase();
        traceEnabled = Boolean.parseBoolean(p.getProperty("trace", "true").trim());
        chunkEnabled = Boolean.parseBoolean(p.getProperty("chunk", "false").trim());
        chunkBytes = Math.max(4096, parseInt(p.getProperty("chunkBytes"), 1024 * 1024));
        chunkMinBytes = parseInt(p.getProperty("chunkMinBytes"), 0);
        retargetEnabled = Boolean.parseBoolean(p.getProperty("retarget", "false").trim());
        retargetTo = parseInt(p.getProperty("retargetTo"), GL_ARRAY_BUFFER);
        mutableBufferStorage = Boolean.parseBoolean(p.getProperty("mutableBufferStorage", "true").trim());
    }

    private static int parseInt(String s, int fallback) {
        if (s == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static void writeDefaultConfig(Path file) {
        String text = """
                # voxymap armfix - Windows-on-ARM OpenGL buffer upload workaround
                #
                # enable: auto   - only act when an OpenGLOn12 / Adreno / D3D12 renderer is detected
                #         always - act regardless of driver (testing only)
                #         never  - do nothing at all
                enable=auto

                # mutableBufferStorage: THE FIX.
                #
                # Hides GL_ARB_buffer_storage from blaze3d so it takes its own mutable-storage
                # fallback (glBufferData) instead of immutable glBufferStorage. What kills this
                # driver is a buffer created with GL_MAP_PERSISTENT_BIT | GL_CLIENT_STORAGE_BIT
                # being written by glBufferSubData -- not the size, and not the bind target.
                # See RESULTS.md for the measurements.
                mutableBufferStorage=true

                # retarget: rejected hypothesis, kept for the record.
                # Rebinds uploads that blaze3d routes to GL_COPY_WRITE_BUFFER (36663) onto
                # GL_ARRAY_BUFFER instead. Measured: does NOT help. The crash reproduces on the
                # retargeted slot, which is what ruled the bind target out as the cause.
                retarget=false

                # Slot to use instead of GL_COPY_WRITE_BUFFER (36663) when retarget=true.
                # 34962 = GL_ARRAY_BUFFER, 35052 = GL_PIXEL_UNPACK_BUFFER
                retargetTo=34962

                # trace: record every DirectStateAccess$Emulated.bufferSubData call.
                # Writes <gamedir>/voxymap/armfix/upload-trace.log (summary, flushed per line) and
                # <gamedir>/voxymap/armfix/last-calls.txt (memory-mapped ring of the most recent calls,
                # which survives a hard access violation).
                trace=true

                # chunk: split large uploads into chunkBytes-sized glBufferSubData calls.
                # Measurement says size is NOT what trips this driver, so this stays off. Kept
                # because it is the obvious next thing to try if a different ARM box behaves
                # differently -- see RESULTS.md before turning it on.
                chunk=false
                chunkBytes=1048576

                # Only chunk uploads at least this large. 0 means chunk anything over chunkBytes.
                chunkMinBytes=0
                """;
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            System.err.println("[voxymap armfix] could not write default config: " + e);
        }
    }

    /**
     * Decide once, on the render thread with a live context, whether this machine needs us.
     * Called from the upload hook, so it must never throw.
     */
    public static boolean active() {
        int s = state;
        if (s != 0) {
            return s == 1;
        }
        ensureConfig();
        return resolve();
    }

    private static synchronized boolean resolve() {
        if (state != 0) {
            return state == 1;
        }
        boolean act;
        if ("never".equals(enableMode)) {
            rendererString = "<not queried: enable=never>";
            act = false;
        } else if ("always".equals(enableMode)) {
            rendererString = queryRenderer();
            act = true;
        } else {
            rendererString = queryRenderer();
            act = looksAffected(rendererString);
        }
        state = act ? 1 : 2;
        System.out.println("[voxymap armfix] renderer=" + rendererString + " enable=" + enableMode
                + " -> " + (act ? "ACTIVE" : "inert")
                + " (retarget=" + retargetEnabled + " retargetTo=" + retargetTo
                + " trace=" + traceEnabled + " chunk=" + chunkEnabled + " chunkBytes=" + chunkBytes + ")");
        if (act && traceEnabled) {
            UploadTracer.init(rendererString);
        }
        return act;
    }

    private static String queryRenderer() {
        try {
            return com.mojang.renderpearl.backend.opengl.GlStateManager._getString(GL_VENDOR)
                    + " | " + com.mojang.renderpearl.backend.opengl.GlStateManager._getString(GL_RENDERER)
                    + " | " + com.mojang.renderpearl.backend.opengl.GlStateManager._getString(GL_VERSION);
        } catch (Throwable t) {
            return "<query failed: " + t + ">";
        }
    }

    /**
     * The x64 machines in this pack run NVIDIA/AMD/Intel GL drivers, none of which match. Matching
     * "microsoft" covers the stock OpenGLOn12 renderer string; "d3d12"/"zink" cover the Mesa builds.
     */
    private static boolean looksAffected(String s) {
        if (s == null) {
            return false;
        }
        String l = s.toLowerCase();
        return l.contains("openglon12") || l.contains("adreno") || l.contains("d3d12")
                || l.contains("qualcomm") || l.contains("zink")
                || (l.contains("microsoft") && !l.contains("nvidia"));
    }

    public static boolean traceEnabled() {
        return traceEnabled;
    }

    public static boolean chunkEnabled() {
        return chunkEnabled;
    }

    public static int chunkBytes() {
        return chunkBytes;
    }

    public static int chunkMinBytes() {
        return chunkMinBytes;
    }

    public static boolean retargetEnabled() {
        return retargetEnabled;
    }

    public static boolean mutableBufferStorage() {
        ensureConfig();
        return mutableBufferStorage;
    }

    public static int retargetTo() {
        return retargetTo;
    }

    public static String renderer() {
        return rendererString;
    }
}
