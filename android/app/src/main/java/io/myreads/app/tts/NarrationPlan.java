package io.myreads.app.tts;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/** Persist normalized chunk boundaries so a restart resumes the same request. */
final class NarrationPlan {
    final String id, model, voice, source;
    final int origin;
    final List<OfficialNarration.Chunk> chunks;
    private final File file;
    private NarrationPlan(File directory, String model, String voice, String source, int origin,
                          List<OfficialNarration.Chunk> chunks) throws Exception {
        this.model = model; this.voice = voice; this.source = source; this.origin = origin;
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        byte[] digest = hash.digest((NarrationCache.REVISION + "\0" + model + "\0" + voice + "\0" + source + "\0" + origin).getBytes(StandardCharsets.UTF_8));
        StringBuilder key = new StringBuilder(); for (byte value : digest) key.append(String.format(Locale.ROOT, "%02x", value & 255));
        id = key.toString(); file = new File(directory, id + ".json"); this.chunks = new ArrayList<>(chunks);
    }
    static NarrationPlan create(File directory, String model, String voice, String source, int origin,
                                List<OfficialNarration.Chunk> chunks) throws Exception {
        NarrationPlan plan = new NarrationPlan(directory, model, voice, source, origin, chunks); plan.save(); return plan;
    }
    static NarrationPlan load(File directory, String id, String model, String voice, String source) {
        if (id == null || !id.matches("[0-9a-f]{64}")) return null;
        File file = new File(directory, id + ".json"); if (!file.isFile() || file.length() > 8 * 1024 * 1024) return null;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream in = new FileInputStream(file)) { byte[] buffer = new byte[8192]; int count; while ((count = in.read(buffer)) >= 0) bytes.write(buffer, 0, count); }
            JSONObject data = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            if (!data.getString("pipeline").equals(NarrationCache.REVISION) || !model.equals(data.getString("model"))
                || !voice.equals(data.getString("voice")) || !source.equals(data.getString("source"))) return null;
            int origin = data.getInt("origin"); if (origin < 0) return null;
            JSONArray units = data.getJSONArray("chunks"); List<OfficialNarration.Chunk> chunks = new ArrayList<>();
            if (units.length() == 0 || units.length() > 100000) return null;
            int previous = origin;
            for (int i = 0; i < units.length(); i++) {
                JSONObject unit = units.getJSONObject(i); String text = unit.getString("text");
                int paragraph = unit.getInt("paragraph"), pause = unit.getInt("pause");
                if (text.isEmpty() || text.length() > 20000 || paragraph < previous || pause != 0 && pause != 11520 && pause != 19200) return null;
                chunks.add(new OfficialNarration.Chunk(text, paragraph, pause)); previous = paragraph;
            }
            NarrationPlan plan = new NarrationPlan(directory, model, voice, source, origin, chunks);
            return plan.id.equals(id) ? plan : null;
        } catch (Exception ignored) { return null; }
    }
    void replace(int index, List<OfficialNarration.Chunk> replacement) throws Exception {
        chunks.remove(index); chunks.addAll(index, replacement); save();
    }
    private void save() throws Exception {
        if (!file.getParentFile().isDirectory() && !file.getParentFile().mkdirs()) throw new IOException("无法保存朗读计划");
        JSONObject data = new JSONObject().put("pipeline", NarrationCache.REVISION).put("model", model).put("voice", voice)
            .put("source", source).put("origin", origin); JSONArray units = new JSONArray();
        for (OfficialNarration.Chunk chunk : chunks) units.put(new JSONObject().put("text", chunk.text).put("paragraph", chunk.paragraph).put("pause", chunk.pauseSamples));
        data.put("chunks", units); File partial = new File(file.getPath() + ".part");
        try {
            try (FileOutputStream out = new FileOutputStream(partial)) { out.write(data.toString().getBytes(StandardCharsets.UTF_8)); out.getFD().sync(); }
            if (!partial.renameTo(file)) throw new IOException("无法保存朗读计划");
        } finally { partial.delete(); }
    }
    static final class Resume {
        final String plan;
        final int unit;
        final OfficialRandom random;
        private Resume(String plan, int unit, OfficialRandom random) { this.plan = plan; this.unit = unit; this.random = random; }
        static Resume read(JSONObject saved, String book, int chapter, int paragraph, String voice) {
            try {
                if (!NarrationCache.REVISION.equals(saved.optString("pipeline")) || !book.equals(saved.optString("bookId"))
                    || chapter != saved.getInt("chapter") || paragraph != saved.getInt("paragraph") || !voice.equals(saved.optString("voice"))) return null;
                String plan = saved.getString("plan"); int unit = saved.getInt("unit");
                if (!plan.matches("[0-9a-f]{64}") || unit < 0) return null;
                return new Resume(plan, unit, OfficialRandom.resume(saved.getString("rng"), saved.getLong("rngDraws")));
            } catch (Exception ignored) { return null; }
        }
    }
}
