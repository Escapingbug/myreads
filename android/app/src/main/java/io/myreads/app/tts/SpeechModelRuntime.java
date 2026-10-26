package io.myreads.app.tts;

import android.content.Context;
import android.os.PowerManager;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/** One model for narration and UI speech. All tokenizer/ONNX access is serialized. */
final class SpeechModelRuntime {
    static final class RunawaySpeech extends IOException {
        RunawaySpeech() { super("这段声音未能完整生成，请重试或换一个声音"); }
    }
    private static final ReentrantLock lock = new ReentrantLock(true);
    private static final AtomicInteger guidance = new AtomicInteger();
    private static volatile MossOnnxDemoEngine engine;
    private static NativeTokenizer tokenizer;
    private static final java.util.Map<String, NativeWeText> normalizers = new java.util.HashMap<>();
    private static File normalizerDirectory;
    private static volatile boolean accessibility;
    private static int narrators;
    static final class YieldNarration extends RuntimeException {}

    static boolean loaded() { return engine != null; }
    static boolean accessibilityEnabled() { return accessibility; }
    static void beginGuidance() {
        guidance.incrementAndGet();
        BookPlaybackService active = BookPlaybackService.current;
        if (active != null) active.wakeForGuidance();
    }
    static void endGuidance() { guidance.decrementAndGet(); }
    static boolean guidancePending() { return guidance.get() > 0; }
    static void checkNarration(SynthesisGate gate) {
        if (guidancePending() || accessibility && gate.isPaused()) throw new YieldNarration();
    }
    static void retainNarration() { lock.lock(); try { narrators++; } finally { lock.unlock(); } }
    static void releaseNarration() { lock.lock(); try { narrators--; closeIfUnused(); } finally { lock.unlock(); } }
    static void enable(Context context) throws Exception {
        lock.lockInterruptibly();
        try { load(context, true); accessibility = true; }
        catch (Throwable error) { closeIfUnused(); throw error; }
        finally { lock.unlock(); }
    }
    static void disable() { lock.lock(); try { accessibility = false; closeIfUnused(); } finally { lock.unlock(); } }
    private static void closeIfUnused() {
        if (accessibility || narrators > 0) return;
        if (engine != null) { engine.close(); engine = null; }
        if (tokenizer != null) { tokenizer.close(); tokenizer = null; }
        for (NativeWeText normalizer : normalizers.values()) normalizer.close();
        normalizers.clear(); normalizerDirectory = null;
    }
    private static void load(Context context, boolean model) throws Exception {
        if (tokenizer != null && (!model || engine != null)) return;
        ModelRepository repository = new ModelRepository(context);
        if (!repository.ready()) throw new IOException("请先下载并校验听书模型");
        if (tokenizer == null) tokenizer = new NativeTokenizer(new File(repository.root, "MOSS-TTS-Nano-100M-ONNX/tokenizer.model"));
        if (model && engine == null) {
            PowerManager.WakeLock wake = context.getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zijian:accessibility-model");
            wake.acquire(10 * 60 * 1000L);
            try { engine = new MossOnnxDemoEngine(repository.root, new File(context.getCacheDir(), "tts"), 2); }
            finally { if (wake.isHeld()) wake.release(); }
        }
    }
    static int[] tokenize(Context context, String text) throws Exception {
        lock.lockInterruptibly();
        try { load(context, false); return tokenizer.tokenize(text); }
        finally { lock.unlock(); }
    }
    static java.util.List<OfficialNarration.Chunk> planChapter(Context context,
            java.util.List<OfficialNarration.Paragraph> paragraphs, String voice) throws Exception {
        lock.lockInterruptibly();
        try {
            load(context, false);
            java.util.List<String> raw = new java.util.ArrayList<>();
            for (OfficialNarration.Paragraph p : paragraphs) raw.add(p.text);
            String language = OfficialText.language(OfficialText.robust(OfficialText.join("\n", raw)), voice);
            if (normalizerDirectory == null) normalizerDirectory = OfficialTextAssets.install(context);
            NativeWeText normalizer = normalizers.get(language);
            if (normalizer == null) {
                normalizer = new NativeWeText(normalizerDirectory, language); normalizers.put(language, normalizer);
            }
            final NativeWeText selected = normalizer;
            return OfficialNarration.chapter(paragraphs,
                text -> OfficialText.robust(selected.normalize(language.equals("zh")
                    ? OfficialText.hyphens(OfficialText.robust(text)) : OfficialText.robust(text))),
                text -> tokenizer.tokenize(text).length);
        } finally { lock.unlock(); }
    }
    // UI guidance is its own synthesis request. It never consumes the book's RNG.
    static SynthesisResult synthesize(Context context, String text, File output, String voice,
                                      SynthesisCheckpoint checkpoint, NarrationCache.Clip previous) throws Exception {
        return synthesizeOfficial(context, text, output, voice, checkpoint, new OfficialRandom(), 0);
    }
    static SynthesisResult synthesizeOfficial(Context context, String text, File output, String voice,
            SynthesisCheckpoint checkpoint, OfficialRandom random, int pauseSamples) throws Exception {
        lock.lockInterruptibly();
        boolean committed = false;
        try (OfficialRandom.Transaction attempt = random.transaction()) {
            checkpoint.awaitReady(); load(context, true); checkpoint.awaitReady();
            engine.setCancelled(false);
            SynthesisResult result = engine.synthesize(tokenizer.tokenize(text), output, voice, 375,
                random, checkpoint, pauseSamples);
            committed = result.getGeneratedFrames() > 0 && result.getGeneratedFrames() < 375;
            if (committed) attempt.commit();
            if (result.getGeneratedFrames() == 0) throw new RunawaySpeech();
            return result;
        } finally {
            // Interrupted, yielded and capped attempts are unplayed transactions.
            // Re-running them must start from exactly the same official stream.
            if (!committed) output.delete();
            lock.unlock();
        }
    }
}
