package io.myreads.app.tts;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.List;

public class SpeechTextTest {
    @Test public void datesAmountsAndIdentifiers() {
        assertEquals("二零二六年九月三十日，共一万二千五百八十点六零元，百分之十二点五。电话一八六零一二零零九零九。", SpeechText.normalize("2026-09-30，共12,580.60元，12.5%。电话18601200909。"));
        assertEquals("二零二六年，编号零零七，负零点五。", SpeechText.normalize("２０２６年，编号007，-0.5。"));
    }
    @Test public void segmentsRetainTextAndSupplementaryCharacters() {
        String input = "夜色渐深，旅人仍在山路上行走".repeat(16) + "𠮷🙂。他停下脚步，听见远处有人呼唤。";
        List<String> segments = SpeechText.segments(input);
        assertEquals(input, String.join("", segments));
        for (String part : segments) {
            assertTrue(part.codePointCount(0, part.length()) <= 48);
            assertFalse(Character.isHighSurrogate(part.charAt(part.length() - 1)));
            assertFalse(Character.isLowSurrogate(part.charAt(0)));
        }
    }
    @Test public void whitespaceOnlyProducesNoAudioTask() { assertTrue(SpeechText.segments("\n\t　").isEmpty()); }
    @Test public void dialogueDelimitersDoNotBecomeSpokenSymbols() {
        String input = "他问：“你什么时候回来？”她回答：『明天……我会回来——一定！』风吹过窗台。";
        assertEquals("他问，你什么时候回来？她回答，明天。我会回来，一定！风吹过窗台。", SpeechText.normalize(input));
        assertEquals(1, SpeechText.segments(input).size());
    }
    @Test public void typographicVariantsAndRepeatedMarksPreserveIntent() {
        assertEquals("真的吗？！是的！等一下。然后走。", SpeechText.normalize("真的吗？？！！是的！！！等一下......然后走..。"));
        assertEquals("今天读完山间来信。这很重要，请记住。", SpeechText.normalize("今天读完《山间来信》。这很（重要），请记住。"));
    }
    @Test public void punctuationOnlyAndClosingQuotesProduceNoAudioTask() {
        for (String input : List.of("……", "——", "？！", "“”", "』", "***", "🙂")) assertTrue(input, SpeechText.segments(input).isEmpty());
        for (String part : SpeechText.segments("“回来吧。”".repeat(30))) {
            assertTrue(part.codePoints().anyMatch(Character::isLetterOrDigit));
            assertFalse(part.contains("“") || part.contains("”"));
        }
    }
    @Test public void englishContractionsAndDomainsKeepTheirMeaning() {
        assertEquals("I can't believe it. We won't stop!", SpeechText.normalize("“I can’t believe it.” ‘We won’t stop!’"));
        assertEquals("详见 example.com，明天九点零五分开始。", SpeechText.normalize("详见 example.com，明天09:05开始。"));
    }
    @Test public void longDialogueSegmentsPreserveWordsWithinLimit() {
        String input = "她说：“我还记得那天的清晨……你沿着山路走来——风很轻，我们都没有说话。”".repeat(12);
        String normalized = SpeechText.normalize(input);
        List<String> segments = SpeechText.segments(input);
        assertEquals(normalized, String.join("", segments));
        for (String part : segments) assertTrue(part.codePointCount(0, part.length()) <= 48);
        assertEquals(normalized, SpeechText.normalize(normalized));
    }
    @Test public void combinedQuestionAndExclamationAreNotSplitAtBudget() {
        String input = "问".repeat(47) + "？！随后他转身。";
        List<String> segments = SpeechText.segments(input);
        assertEquals(input, String.join("", segments));
        assertTrue(segments.get(0).endsWith("？！"));
        assertFalse(segments.get(1).startsWith("！"));
        assertEquals(49, segments.get(0).codePointCount(0, segments.get(0).length()));
    }
}
