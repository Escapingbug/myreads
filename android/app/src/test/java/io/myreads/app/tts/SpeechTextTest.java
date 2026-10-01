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
            assertTrue(part.codePointCount(0, part.length()) <= SpeechText.MAX_CHARACTERS + 1);
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
        for (String part : segments) assertTrue(part.codePointCount(0, part.length()) <= SpeechText.MAX_CHARACTERS + 1);
        assertEquals(normalized, SpeechText.normalize(normalized));
    }
    @Test public void combinedQuestionAndExclamationAreNotSplitAtBudget() {
        String input = "问".repeat(71) + "？！" + "随后他转身。".repeat(20);
        List<String> segments = SpeechText.segments(input);
        assertEquals(input, String.join("", segments));
        assertTrue(segments.get(0).endsWith("？！"));
        assertFalse(segments.get(1).startsWith("！"));
        assertEquals(73, segments.get(0).codePointCount(0, segments.get(0).length()));
    }
    @Test public void slightlyLongSentenceFinishesBeforeTheNextInvocation() {
        String sentence = "他终于明白自己一直寻找的答案并不藏在远方那些陌生的城市里而是藏在每天清晨推开家门时看见的那一束温暖的阳光之中。";
        assertTrue(sentence.codePointCount(0, sentence.length()) > 48);
        List<String> parts = SpeechText.segments(sentence + "第二天他带着这个答案重新走上了那条熟悉的山路。".repeat(5));
        assertEquals(sentence, parts.get(0));
        assertEquals(List.of(sentence), SpeechText.segments(sentence));
    }
    @Test public void longSentenceCanFinishItsClauseBeyondSoftTarget() {
        String clause = "他终于明白自己一直寻找的答案并不藏在远方那些陌生的城市里而是藏在每天清晨推开家门时看见的那一束温暖的阳光之中，";
        String input = clause + "所以这一次他决定先认真看看身边的一切再慢慢考虑以后应该怎样继续自己的旅程。";
        List<String> parts = SpeechText.segments(input);
        assertEquals(clause, parts.get(0));
        assertEquals(input, String.join("", parts));
    }
    @Test public void englishWordsAndSentencePeriodsAreRespected() {
        String input = "Reading together can make an ordinary evening extraordinary and memorable for everyone in the family. ".repeat(4);
        List<String> parts = SpeechText.segments(input);
        assertEquals(input.trim(), String.join("", parts));
        int offset = 0;
        for (String part : parts) {
            offset += part.length();
            if (offset < input.trim().length()) assertFalse(Character.isLetter(input.charAt(offset - 1)) && Character.isLetter(input.charAt(offset)));
        }
        String sentence = "We read quietly. ";
        List<String> sentences = SpeechText.segments(sentence.repeat(12));
        assertTrue(sentences.get(0).endsWith("."));
    }
    @Test public void cappedAudioRetryMakesSmallerPiecesWithoutLosingText() {
        String input = "我还记得那天清晨你沿着山路走来风很轻我们都没有说话后来才明白那一段安静的时光已经悄悄留在我们的记忆里了。";
        List<String> parts = SpeechText.shorterSegments(input);
        assertTrue(parts.size() >= 2);
        assertEquals(input, String.join("", parts));
        for (String part : parts) assertTrue(part.codePointCount(0, part.length()) <= input.codePointCount(0, input.length()) / 2 + 1);
    }
}
