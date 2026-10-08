package io.myreads.app.tts;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Cache WAV with its structural timing and generated-code provenance. */
final class NarrationCache {
    static final String REVISION = "narration-context-window-v3";
    static final class Clip {
        final File file;
        final NarrationPlanner.Unit unit;
        final List<int[]> codes;
        final long durationMs, activeMs;
        final int rawLeading, rawTrailing, retainedTail;
        Clip(File file, NarrationPlanner.Unit unit, List<int[]> codes, long durationMs, long activeMs,
             int rawLeading, int rawTrailing, int retainedTail) {
            this.file = file; this.unit = unit; this.codes = codes; this.durationMs = durationMs;
            this.activeMs = activeMs; this.rawLeading = rawLeading; this.rawTrailing = rawTrailing; this.retainedTail = retainedTail;
        }
    }
    static String key(String model, String voice, NarrationPlanner.Unit unit, Clip previous) throws Exception {
        return key(model, voice, unit, previous, null);
    }
    static String key(String model, String voice, NarrationPlanner.Unit unit, Clip previous,
                      NarrationContext.Window context) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update((model + ":" + REVISION + ":" + voice + ":" + unit.ending + ":" + unit.text + "\0").getBytes(StandardCharsets.UTF_8));
        if (previous != null) {
            digest.update((previous.unit.ending + ":" + previous.rawTrailing + ":" + previous.retainedTail + "\0").getBytes(StandardCharsets.UTF_8));
        }
        if (context != null) {
            digest.update(("continuation\0" + context.text + "\0").getBytes(StandardCharsets.UTF_8));
            for (int[] row : context.codes) for (int code : row) {
                digest.update((byte) (code >>> 8)); digest.update((byte) code);
            }
        }
        StringBuilder name = new StringBuilder(); for (byte b : digest.digest()) name.append(String.format(Locale.ROOT, "%02x", b & 255));
        return name.toString();
    }
    static void write(File file, Clip clip) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
            out.writeInt(0x5a4e4331); out.writeLong(clip.file.length()); out.writeLong(clip.durationMs);
            out.writeInt(clip.rawLeading); out.writeInt(clip.rawTrailing); out.writeInt(clip.retainedTail);
            out.writeInt(clip.codes.size());
            for (int[] row : clip.codes) { if (row.length != 16) throw new IOException("无效的语音 token"); for (int code : row) out.writeShort(code); }
        }
    }
    static Clip read(File wav, File sidecar, NarrationPlanner.Unit unit) {
        if (!wav.isFile() || !sidecar.isFile() || sidecar.length() > 16000) return null;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(sidecar)))) {
            if (in.readInt() != 0x5a4e4331 || in.readLong() != wav.length() || wav.length() <= 44) return null;
            long duration = in.readLong(); int lead = in.readInt(), tail = in.readInt(), retained = in.readInt(), count = in.readInt();
            if (count < 1 || count >= 375 || duration <= 0 || duration > 35000 || lead < 0 || tail < 0 || retained < 0
                || lead > 1440000 || tail > 1440000 || retained > tail) return null;
            List<int[]> codes = new ArrayList<>();
            for (int i = 0; i < count; i++) { int[] row = new int[16]; for (int j = 0; j < 16; j++) { row[j] = in.readUnsignedShort(); if (row[j] >= 1024) return null; } codes.add(row); }
            if (in.read() != -1) return null;
            return new Clip(wav, unit, codes, duration, 0, lead, tail, retained);
        } catch (IOException ignored) { return null; }
    }
}
