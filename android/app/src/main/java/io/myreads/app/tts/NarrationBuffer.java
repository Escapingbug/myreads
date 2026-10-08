package io.myreads.app.tts;

/** Audio-time admission and active synthesis speed, independent of clip count. */
final class NarrationBuffer {
    final String mode;
    private double ratio;
    NarrationBuffer(String mode, double previousRatio) {
        this.mode = mode.equals("chapter") ? "chapter" : "stream";
        ratio = Double.isFinite(previousRatio) && previousRatio > 0 ? previousRatio : 0;
    }
    void generated(long activeMs, long audioMs) {
        if (activeMs <= 0 || audioMs <= 0) return; // Cache hits and pause time are not performance samples.
        double sample = (double) activeMs / audioMs;
        ratio = ratio == 0 ? sample : ratio * 0.75 + sample * 0.25;
    }
    double ratio() { return ratio; }
    boolean prepareChapter() { return mode.equals("chapter"); }
    long aheadMs(float speed) { return (long) (Math.max(30, Math.min(90, 30 + ratio * 30)) * 1000 * speed); }
}
