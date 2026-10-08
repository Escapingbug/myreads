package io.myreads.app.tts;

/** Bound runaway generation for very short utterances before any audio is played. */
final class SpeechQuality {
    static final int DEFAULT_FRAMES = 375, SHORT_FRAMES = 125;
    static int frameLimit(String text) {
        long letters = text.codePoints().filter(Character::isLetterOrDigit).count();
        return letters <= 8 ? SHORT_FRAMES : DEFAULT_FRAMES;
    }
}
