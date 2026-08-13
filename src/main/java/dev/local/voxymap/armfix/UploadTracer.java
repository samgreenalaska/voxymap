package dev.local.voxymap.armfix;

import net.fabricmc.loader.api.FabricLoader;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Records every emulated {@code glBufferSubData} upload.
 *
 * <p>The failure being chased is a hard access violation inside the driver, so anything sitting in
 * a buffered stream at the moment of death is lost. Two sinks, for two different jobs:
 *
 * <ul>
 *   <li><b>last-calls.txt</b> — a memory-mapped ring of the most recent {@value #RING_SLOTS} calls.
 *       Writes go straight into a mapped page, so the kernel still flushes them after the process
 *       is killed. This is what identifies the exact call that faulted.</li>
 *   <li><b>upload-trace.log</b> — a human-readable summary, fsync'd per line. Only notable events
 *       are written (a size/usage/target combination seen for the first time, or a new largest
 *       upload), each with the calling mod resolved from the stack. This is what shows which mod
 *       uploads what, without costing a stack walk on every call.</li>
 * </ul>
 */
public final class UploadTracer {
    private static final int RING_SLOTS = 4096;
    private static final int RECORD_BYTES = 96;
    private static final int HEADER_BYTES = 512;

    /** target(2 bits) | usage(10 bits) | size bucket(5 bits) */
    private static final int SEEN_BITS = 17;

    private static volatile boolean ready;
    private static MappedByteBuffer ring;
    private static Writer log;
    private static FileOutputStream logStream;

    private static final AtomicLong SEQ = new AtomicLong();
    private static long startNanos;

    /** Signatures already reported. Plain array: read on every upload, so it must not allocate. */
    private static boolean[] seen;
    /** Largest upload seen so far per bind target, so the trace shows the growth curve. */
    private static final int[] MAX_BY_TARGET = new int[4];

    private static final ThreadLocal<byte[]> SCRATCH =
            ThreadLocal.withInitial(() -> new byte[RECORD_BYTES]);

    private UploadTracer() {
    }

    public static synchronized void init(String renderer) {
        if (ready) {
            return;
        }
        try {
            Path dir = FabricLoader.getInstance().getGameDir().resolve(ArmFix.TRACE_DIR).resolve(ArmFix.TRACE_SUBDIR);
            Files.createDirectories(dir);

            Path ringFile = dir.resolve("last-calls.txt");
            long size = (long) HEADER_BYTES + (long) RING_SLOTS * RECORD_BYTES;
            try (RandomAccessFile raf = new RandomAccessFile(ringFile.toFile(), "rw")) {
                raf.setLength(size);
                ring = raf.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, size);
            }
            byte[] blank = new byte[RECORD_BYTES];
            java.util.Arrays.fill(blank, (byte) ' ');
            blank[RECORD_BYTES - 1] = '\n';
            for (int i = 0; i < RING_SLOTS; i++) {
                ring.put(HEADER_BYTES + i * RECORD_BYTES, blank, 0, RECORD_BYTES);
            }
            writeHeader(renderer);

            logStream = new FileOutputStream(dir.resolve("upload-trace.log").toFile(), false);
            log = new OutputStreamWriter(logStream, StandardCharsets.UTF_8);

            seen = new boolean[1 << SEEN_BITS];
            startNanos = System.nanoTime();
            ready = true;

            line("voxymap armfix upload trace");
            line("renderer: " + renderer);
            line("chunk=" + ArmFix.chunkEnabled() + " chunkBytes=" + ArmFix.chunkBytes()
                    + " chunkMinBytes=" + ArmFix.chunkMinBytes());
            line("targets: 34962=ARRAY 34963=ELEMENT_ARRAY 35345=UNIFORM 36663=COPY_WRITE");
            line("columns: seq, ms, target, usage, bufferId, offset, length, caller");
            line("---");
        } catch (Throwable t) {
            System.err.println("[voxymap armfix] tracer init failed, continuing without it: " + t);
            ready = false;
        }
    }

    private static void writeHeader(String renderer) {
        StringBuilder sb = new StringBuilder();
        sb.append("voxymap armfix ring of the last ").append(RING_SLOTS)
                .append(" glBufferSubData calls; highest S= is the most recent.\n");
        sb.append("renderer: ").append(renderer).append('\n');
        sb.append("S=seq t=ms tgt=bindTarget usg=usageBits buf=glBufferId off=byteOffset len=byteCount\n");
        byte[] head = new byte[HEADER_BYTES];
        java.util.Arrays.fill(head, (byte) ' ');
        byte[] src = sb.toString().getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(src, 0, head, 0, Math.min(src.length, HEADER_BYTES - 1));
        head[HEADER_BYTES - 1] = '\n';
        ring.put(0, head, 0, HEADER_BYTES);
    }

    /** Hot path: called for every emulated buffer upload. Must not allocate or throw. */
    public static void record(int buffer, long offset, int length, int usage, int target) {
        if (!ready) {
            return;
        }
        try {
            long seq = SEQ.incrementAndGet();
            writeRing(seq, buffer, offset, length, usage, target);
            if (isNotable(length, usage, target)) {
                report(seq, buffer, offset, length, usage, target);
            }
        } catch (Throwable ignored) {
            // Tracing must never be the reason the game dies.
        }
    }

    private static void writeRing(long seq, int buffer, long offset, int length, int usage, int target) {
        byte[] b = SCRATCH.get();
        java.util.Arrays.fill(b, (byte) ' ');
        int p = 0;
        p = put(b, p, "S=");
        p = putNum(b, p, seq, 10);
        p = put(b, p, " t=");
        p = putNum(b, p, (System.nanoTime() - startNanos) / 1_000_000L, 9);
        p = put(b, p, " tgt=");
        p = putNum(b, p, target, 5);
        p = put(b, p, " usg=");
        p = putNum(b, p, usage, 10);
        p = put(b, p, " buf=");
        p = putNum(b, p, buffer, 8);
        p = put(b, p, " off=");
        p = putNum(b, p, offset, 12);
        p = put(b, p, " len=");
        putNum(b, p, length, 10);
        b[RECORD_BYTES - 1] = '\n';

        int slot = (int) Math.floorMod(seq, (long) RING_SLOTS);
        ring.put(HEADER_BYTES + slot * RECORD_BYTES, b, 0, RECORD_BYTES);
    }

    private static int put(byte[] b, int pos, String s) {
        for (int i = 0; i < s.length(); i++) {
            b[pos + i] = (byte) s.charAt(i);
        }
        return pos + s.length();
    }

    /** Right-aligned zero-padded, so records stay fixed width and sort naturally. */
    private static int putNum(byte[] b, int pos, long v, int width) {
        if (v < 0) {
            v = 0;
        }
        for (int i = width - 1; i >= 0; i--) {
            b[pos + i] = (byte) ('0' + (int) (v % 10));
            v /= 10;
        }
        return pos + width;
    }

    /**
     * True the first time a (target, usage, size-bucket) combination shows up, and whenever a
     * target sees its largest upload yet. Both are array operations — no allocation, no lock.
     */
    private static boolean isNotable(int length, int usage, int target) {
        int t = targetIndex(target);
        int bucket = 32 - Integer.numberOfLeadingZeros(Math.max(length, 1));
        int key = (t << 15) | ((usage & 0x3FF) << 5) | (bucket & 0x1F);
        boolean notable = false;
        if (!seen[key]) {
            seen[key] = true;
            notable = true;
        }
        if (length > MAX_BY_TARGET[t]) {
            MAX_BY_TARGET[t] = length;
            notable = true;
        }
        return notable;
    }

    private static int targetIndex(int target) {
        return switch (target) {
            case 34962 -> 0; // GL_ARRAY_BUFFER
            case 34963 -> 1; // GL_ELEMENT_ARRAY_BUFFER
            case 35345 -> 2; // GL_UNIFORM_BUFFER
            default -> 3;    // GL_COPY_WRITE_BUFFER
        };
    }

    private static void report(long seq, int buffer, long offset, int length, int usage, int target) {
        line(seq + ", " + (System.nanoTime() - startNanos) / 1_000_000L + "ms, tgt=" + target
                + ", usage=" + usage + ", buf=" + buffer + ", off=" + offset
                + ", len=" + length + " (" + human(length) + "), " + caller());
    }

    private static String human(int bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        }
        if (bytes < 1024 * 1024) {
            return (bytes / 1024) + "KiB";
        }
        return String.format("%.2fMiB", bytes / (1024.0 * 1024.0));
    }

    /**
     * Everything below blaze3d's own upload plumbing, so the mod responsible is visible rather than
     * just {@code CommandEncoder.writeToBuffer}. Only runs for notable calls.
     */
    private static String caller() {
        try {
            return StackWalker.getInstance().walk(s -> s
                    .map(f -> f.getClassName() + "." + f.getMethodName())
                    // Drops both this class and the mixin handler frame injected into the target.
                    .filter(n -> !n.contains("voxymap.armfix") && !n.contains("voxymap$"))
                    .limit(6)
                    .collect(Collectors.joining(" <- ")));
        } catch (Throwable t) {
            return "<stack unavailable>";
        }
    }

    /** Flushed and fsync'd, because the process can die at any instant. */
    private static synchronized void line(String s) {
        if (log == null) {
            return;
        }
        try {
            log.write(s);
            log.write('\n');
            log.flush();
            logStream.getFD().sync();
        } catch (IOException ignored) {
        }
    }

    /** Called by the chunker so the trace shows what the workaround actually did. */
    public static void noteChunking(int length, int chunkBytes, int pieces) {
        line("  -> split " + human(length) + " into " + pieces + " x " + human(chunkBytes));
    }

    /** Retargeting happens on every map tile upload, so report each usage class only once. */
    private static final boolean[] RETARGET_REPORTED = new boolean[1024];

    public static void noteRetarget(int usage, int from, int to) {
        int key = usage & 0x3FF;
        if (RETARGET_REPORTED[key]) {
            return;
        }
        RETARGET_REPORTED[key] = true;
        line("RETARGET usage=" + usage + ": bind target " + from + " -> " + to
                + " (" + describeUsage(usage) + ")");
    }

    private static String describeUsage(int usage) {
        StringBuilder sb = new StringBuilder();
        appendBit(sb, usage, 1, "MAP_READ");
        appendBit(sb, usage, 2, "MAP_WRITE");
        appendBit(sb, usage, 4, "HINT_CLIENT_STORAGE");
        appendBit(sb, usage, 8, "COPY_DST");
        appendBit(sb, usage, 16, "COPY_SRC");
        appendBit(sb, usage, 32, "VERTEX");
        appendBit(sb, usage, 64, "INDEX");
        appendBit(sb, usage, 128, "UNIFORM");
        appendBit(sb, usage, 256, "UNIFORM_TEXEL_BUFFER");
        appendBit(sb, usage, 512, "INDIRECT_PARAMETERS");
        return sb.isEmpty() ? "none" : sb.toString();
    }

    private static void appendBit(StringBuilder sb, int usage, int bit, String name) {
        if ((usage & bit) != 0) {
            if (!sb.isEmpty()) {
                sb.append('|');
            }
            sb.append(name);
        }
    }
}
