package io.myreads.app.tts;

import android.content.Context;
import android.os.PowerManager;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/** One offline Audio8 model for narration and UI speech; all access is serialized. */
final class SpeechModelRuntime {
    static final class RunawaySpeech extends IOException {
        RunawaySpeech() { super("这段声音未能完整生成，请重试或换一个声音"); }
    }
    private static final ReentrantLock lock = new ReentrantLock(true);
    private static final AtomicInteger guidance = new AtomicInteger();
    private static volatile Audio8AppEngine engine;
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
    }
    private static void load(Context context, boolean model) throws Exception {
        if (engine != null) return;
        ModelRepository repository = new ModelRepository(context);
        if (!repository.ready()) throw new IOException("请先下载并校验听书模型");
        if (model && engine == null) {
            PowerManager.WakeLock wake = context.getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zijian:accessibility-model");
            wake.acquire(10 * 60 * 1000L);
            try { engine = new Audio8AppEngine(context, repository.root); }
            finally { if (wake.isHeld()) wake.release(); }
        }
    }
    static int[] tokenize(Context context, String text) throws Exception {
        // Planning is cheap and never needs a model lock or native graph run.
        return text.codePoints().toArray();
    }
    static java.util.List<OfficialNarration.Chunk> planChapter(Context context,
            java.util.List<OfficialNarration.Paragraph> paragraphs, String voice) throws Exception {
        return Audio8Narration.chapter(paragraphs);
    }
    // UI guidance is its own synthesis request, using the same serialized engine.
    static SynthesisResult synthesize(Context context, String text, File output, String voice,
                                      SynthesisCheckpoint checkpoint, NarrationCache.Clip previous) throws Exception {
        return synthesizeOfficial(context, text, output, voice, checkpoint, new OfficialRandom(), 0);
    }
    static SynthesisResult synthesizeOfficial(Context context, String text, File output, String voice,
            SynthesisCheckpoint checkpoint, OfficialRandom random, int pauseSamples) throws Exception {
        lock.lockInterruptibly();
        boolean committed = false;
        try {
            checkpoint.awaitReady(); load(context, true); checkpoint.awaitReady();
            if (!Audio8AppEngine.VOICE.equals(voice)) throw new IOException("请选择新版听书声音");
            try {
                SynthesisResult result = engine.synthesize(text, output, checkpoint);
                committed = true;
                return result;
            } catch (Audio8AppEngine.TooLong tooLong) {
                // Keep the existing service's bounded retry at smaller sentence
                // budgets. Capped requests never write or cache partial speech.
                return new SynthesisResult(output, 375, Audio8AppEngine.SAMPLE_RATE, 0, 0);
            }
        } finally {
            // Interrupted, yielded and capped attempts cannot leave playable audio.
            if (!committed) output.delete();
            lock.unlock();
        }
    }
}
