package io.myreads.app.tts;

/** The complete transcript paired with the assistant's spoken prefix. */
public final class NarrationPrompt {
    public interface Encoder { int[] encode(String value); }
    static int[] transcript(Encoder encoder, String previous, String target) {
        return encoder.encode(previous + target);
    }
}
