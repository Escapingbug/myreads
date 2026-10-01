package io.myreads.app.tts;

/** VAD suggests a boundary; only confirmed near-zero samples may actually be removed. */
final class NarrationTiming {
    static final int RATE = 48000, GUARD = RATE * 160 / 1000;
    static final class Rendered {
        final float[] samples;
        final int leading, trailing, retainedTail;
        Rendered(float[] samples, int leading, int trailing, int retainedTail) {
            this.samples = samples; this.leading = leading; this.trailing = trailing; this.retainedTail = retainedTail;
        }
    }
    static Rendered render(float[] raw, int firstSpeech, int lastSpeech, NarrationPlanner.Boundary previousBoundary,
                           int previousRawTail, int previousRetainedTail) {
        if (firstSpeech < 0 || lastSpeech <= firstSpeech || lastSpeech > raw.length) {
            // An uncertain detector is not permission to discard a quiet utterance.
            int gap = previousBoundary == null ? RATE * 160 / 1000 : pause(previousBoundary, previousRawTail) - previousRetainedTail;
            float[] preserved = new float[raw.length + Math.max(0, gap)];
            System.arraycopy(raw, 0, preserved, Math.max(0, gap), raw.length);
            return new Rendered(preserved, 0, 0, 0);
        }
        int start = Math.max(0, firstSpeech - GUARD), end = Math.min(raw.length, lastSpeech + GUARD);
        // Quiet consonants and breathy onsets can precede VAD by hundreds of ms.
        // Protect every sample that could survive 16-bit WAV quantization, even
        // when the detector confidently labels that area as silence.
        for (int i = 0; i < start; i++) if (Math.abs(raw[i]) >= 1f / 32768) {
            firstSpeech = i; start = Math.max(0, i - GUARD); break;
        }
        for (int i = raw.length - 1; i >= end; i--) if (Math.abs(raw[i]) >= 1f / 32768) {
            lastSpeech = i + 1; end = Math.min(raw.length, lastSpeech + GUARD); break;
        }
        int rawTail = raw.length - lastSpeech;
        int keptLead = firstSpeech - start, keptTail = end - lastSpeech;
        int target = previousBoundary == null ? RATE * 160 / 1000
            : pause(previousBoundary, previousRawTail + firstSpeech);
        int pad = Math.max(0, target - previousRetainedTail - keptLead);
        float[] rendered = new float[pad + end - start];
        System.arraycopy(raw, start, rendered, pad, end - start);
        return new Rendered(rendered, firstSpeech, rawTail, keptTail);
    }
    static int pause(NarrationPlanner.Boundary boundary, int naturalSamples) {
        int min, max;
        switch (boundary) {
            case CONTINUATION: min = 0; max = 120; break;
            case CLAUSE: min = 100; max = 260; break;
            case QUESTION: min = 280; max = 580; break;
            case EXCLAMATION: min = 220; max = 480; break;
            case ELLIPSIS: min = 450; max = 900; break;
            case PARAGRAPH: min = 650; max = 1000; break;
            case TITLE: case CHAPTER: min = 850; max = 1250; break;
            default: min = 250; max = 520;
        }
        return Math.max(min * RATE / 1000, Math.min(max * RATE / 1000, naturalSamples));
    }
}
