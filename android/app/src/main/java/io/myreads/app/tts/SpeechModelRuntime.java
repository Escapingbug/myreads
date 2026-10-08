package io.myreads.app.tts;

import android.content.Context;
import android.os.PowerManager;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/** One model for narration and UI speech. All tokenizer/ONNX access is serialized. */
final class SpeechModelRuntime {
    private static final ReentrantLock lock = new ReentrantLock(true);
    private static final AtomicInteger guidance = new AtomicInteger();
    private static volatile MossOnnxDemoEngine engine;
    private static NativeTokenizer tokenizer;
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
    static SynthesisResult synthesize(Context context, String text, File output, String voice,
                                      SynthesisCheckpoint checkpoint, NarrationCache.Clip previous) throws Exception {
        lock.lockInterruptibly();
        try {
            checkpoint.awaitReady(); load(context, true); checkpoint.awaitReady();
            engine.setCancelled(false);
            return engine.synthesize(tokenizer.tokenize(text), output, voice, 375, 1234L, checkpoint,
                previous == null ? null : previous.unit.ending,
                previous == null ? 0 : previous.rawTrailing, previous == null ? 0 : previous.retainedTail);
        } finally { lock.unlock(); }
    }
}
