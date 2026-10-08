package io.myreads.app.tts;

import org.junit.Test;
import static org.junit.Assert.*;

public class SpeechQualityTest {
    @Test public void shortSpeechCannotGenerateEighteenSecondsOfUnrequestedWords() {
        assertEquals(125, SpeechQuality.frameLimit("我看着她？！"));
        assertEquals(125, SpeechQuality.frameLimit("嗯。"));
        assertEquals(125, SpeechQuality.frameLimit("No!"));
    }
    @Test public void punctuationDoesNotExtendTheShortSpeechBudget() {
        assertEquals(SpeechQuality.frameLimit("𠮷回来吧"), SpeechQuality.frameLimit("“𠮷……回来吧？！”"));
    }
    @Test public void normalProseRetainsTheExistingThirtySecondBudget() {
        assertEquals(375, SpeechQuality.frameLimit("菲儿停止了假哭，朝艾登瞪大眼睛。"));
        assertEquals(375, SpeechQuality.frameLimit("The traveler waited by the window."));
    }
}
