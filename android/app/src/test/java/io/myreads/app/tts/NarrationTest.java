package io.myreads.app.tts;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;
import static io.myreads.app.tts.NarrationPlanner.Boundary.*;

public class NarrationTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private final NarrationPlanner.TokenCounter counter = value -> value.codePointCount(0, value.length());
    private String joined(List<NarrationPlanner.Unit> units) {
        StringBuilder text = new StringBuilder(); for (NarrationPlanner.Unit unit : units) text.append(unit.text); return text.toString();
    }
    @Test public void keepsCompleteSentencesDialogueAndParagraphStructure() {
        List<NarrationPlanner.Unit> units = NarrationPlanner.paragraph(
            "他抬起头，窗外的雨声越来越近，可是他仍然一动不动地望着那扇紧闭的门。"
            + "“你是谁？你为什么会来到这里？难道你没有看到门上的告示吗？”"
            + "门外一直没有人回答，他只是看着远处那一盏明灭不定的灯火，久久没有开口……风又吹了起来。", counter);
        assertEquals(4, units.size());
        assertEquals(SENTENCE, units.get(0).ending); assertEquals(QUESTION, units.get(1).ending);
        assertTrue(units.get(1).dialogue); assertEquals(ELLIPSIS, units.get(2).ending);
        assertEquals(PARAGRAPH, units.get(3).ending);
        assertTrue(units.get(0).text.startsWith("他抬起头，"));
        assertEquals("你是谁？你为什么会来到这里？难道你没有看到门上的告示吗？", units.get(1).text);
    }
    @Test public void groupsTinySentencesWithoutRemovingTheirInternalPunctuation() {
        List<NarrationPlanner.Unit> units = NarrationPlanner.paragraph("好。走吧。天亮了。", counter);
        assertEquals(1, units.size()); assertEquals("好。走吧。天亮了。", units.get(0).text);
        assertEquals(PARAGRAPH, units.get(0).ending);
    }
    @Test public void keepsShortDialogueAttributionWithTheQuotedUtterance() {
        List<NarrationPlanner.Unit> units = NarrationPlanner.paragraph("“你来了？”她轻声问。屋里又安静下来。", counter);
        assertEquals(2, units.size()); assertEquals("你来了？她轻声问。", units.get(0).text);
        assertTrue(units.get(0).dialogue); assertFalse(units.get(1).dialogue);
        assertEquals(PARAGRAPH, units.get(1).ending);
    }
    @Test public void interruptedDialogueKeepsItsNamedActionAndResumedSpeechTogether() {
        String text = "“我看着她！？”菲儿停止了假哭，朝艾登瞪大眼睛，“大人别开玩笑了，她不把我宰了就谢天谢地了，求您了还是给我换房间吧！”";
        List<NarrationPlanner.Unit> units = NarrationPlanner.paragraph(text, counter);
        assertEquals(1, units.size()); assertEquals(SpeechText.normalize(text), units.get(0).text);
        assertTrue(units.get(0).dialogue); assertEquals(PARAGRAPH, units.get(0).ending);
    }
    @Test public void overBudgetInterruptedTurnKeepsItsOpeningWithTheActionClause() {
        String text = "“等等！”菲儿抬起头，望向窗外，“" + "这一路的风雨我都还记得。".repeat(25) + "”";
        List<NarrationPlanner.Unit> units = NarrationPlanner.paragraph(text, counter);
        assertEquals("等等！菲儿抬起头，望向窗外，", units.get(0).text);
        assertEquals(CLAUSE, units.get(0).ending); assertEquals(SpeechText.normalize(text), joined(units));
        for (NarrationPlanner.Unit unit : units) assertTrue(counter.count(unit.text) <= 75);
    }
    @Test public void commaConnectedAttributionSupportsNestedAndAsciiQuotations() {
        for (String text : List.of("“等等！”菲儿笑了笑，说道，“他刚才说‘别急。’我们再等一下。”",
            "\"Wait!\" she said, \"I can't leave yet.\"")) {
            List<NarrationPlanner.Unit> units = NarrationPlanner.paragraph(text, counter);
            assertEquals(1, units.size()); assertEquals(SpeechText.normalize(text), joined(units));
        }
    }
    @Test public void longSentencePrefersClauseBoundariesAndPreservesTextOrder() {
        String text = "窗外的风声".repeat(9) + "，" + "旅人收起手中的信件".repeat(10) + "，他终于站了起来。";
        List<NarrationPlanner.Unit> units = NarrationPlanner.paragraph(text, counter);
        assertEquals(SpeechText.normalize(text), joined(units));
        assertEquals(CLAUSE, units.get(0).ending); assertTrue(units.get(0).text.endsWith("，"));
        for (NarrationPlanner.Unit unit : units) assertTrue(counter.count(unit.text) <= 75);
        assertEquals(PARAGRAPH, units.get(units.size() - 1).ending);
    }
    @Test public void unpunctuatedTextAndSupplementaryCharactersAreNotLostOrDuplicated() {
        String text = "山林𠮷日".repeat(60);
        List<NarrationPlanner.Unit> units = NarrationPlanner.paragraph(text, counter);
        assertEquals(text, joined(units)); assertTrue(units.size() > 1);
        for (NarrationPlanner.Unit unit : units) assertTrue(counter.count(unit.text) <= 75);
        assertEquals(CONTINUATION, units.get(0).ending);
    }
    @Test public void capRetryKeepsTheOriginalEndingAndAllWords() {
        NarrationPlanner.Unit original = new NarrationPlanner.Unit("前面的脚步声渐渐远去，屋里的人久久没有开口说话。", PARAGRAPH, false);
        List<NarrationPlanner.Unit> split = NarrationPlanner.retry(original, counter);
        assertTrue(split.size() >= 2); assertEquals(original.text, joined(split));
        assertEquals(PARAGRAPH, split.get(split.size() - 1).ending);
    }
    @Test public void decimalPointIsNotASentenceBreakAndAsciiEllipsisIsRecognized() {
        String input = "温度是3.5度。Wait... Then go.";
        List<NarrationPlanner.Unit> units = NarrationPlanner.paragraph(input, counter);
        assertTrue(units.get(0).text.contains("三点五"));
        assertTrue(joined(units).replace(" ", "").contains("Wait.Thengo."));
        List<NarrationPlanner.Unit> ellipsis = NarrationPlanner.paragraph("这一路上的风雨".repeat(5) + "...后来他终于走到了门口。", counter);
        assertEquals(ELLIPSIS, ellipsis.get(0).ending);
    }
    @Test public void trimsOnlyOuterSilenceAndKeepsInternalSpeechSamplesExactly() {
        float[] audio = new float[48000];
        for (int i = 9600; i < 33600; i++) audio[i] = i % 97 == 0 ? 0 : i / 48000f;
        NarrationTiming.Rendered rendered = NarrationTiming.render(audio, 9600, 33600, SENTENCE, 4800, 2400);
        int lead = Math.max(0, NarrationTiming.pause(SENTENCE, 14400) - 2400 - NarrationTiming.GUARD);
        int start = 9600 - NarrationTiming.GUARD;
        for (int i = 9600; i < 33600; i++) assertEquals(audio[i], rendered.samples[lead + i - start], 0);
        assertEquals(NarrationTiming.GUARD, rendered.retainedTail);
        assertEquals(14400, rendered.trailing);
    }
    @Test public void calibratedGapIncludesThePreviousRetainedTail() {
        float[] audio = new float[24000]; Arrays.fill(audio, 4800, 19200, 0.5f);
        NarrationTiming.Rendered rendered = NarrationTiming.render(audio, 4800, 19200, PARAGRAPH, 4800, 2400);
        int first = 0; while (rendered.samples[first] == 0) first++;
        assertEquals(650 * 48, first + 2400);
        assertEquals(520 * 48, NarrationTiming.pause(SENTENCE, 96000));
        assertEquals(120 * 48, NarrationTiming.pause(CONTINUATION, 48000));
    }
    @Test public void unknownSpeechEdgesPreserveEvenQuietAudio() {
        float[] quiet = new float[12000]; Arrays.fill(quiet, 0.0001f);
        float[] output = NarrationTiming.render(quiet, -1, -1, null, 0, 0).samples;
        assertEquals(quiet.length + 160 * 48, output.length);
        assertArrayEquals(quiet, Arrays.copyOfRange(output, 160 * 48, output.length), 0);
    }
    @Test public void lateVadDoesNotEraseQuietInitialOrFinalPhonemes() {
        float[] audio = new float[48000];
        Arrays.fill(audio, 4800, 12000, 0.00005f);
        Arrays.fill(audio, 24000, 32000, 0.5f);
        Arrays.fill(audio, 43200, 46080, 0.00005f);
        NarrationTiming.Rendered rendered = NarrationTiming.render(audio, 24000, 32000, PARAGRAPH, 4800, 2400);
        int first = 0; while (rendered.samples[first] == 0) first++;
        assertEquals(650 * 48, first + 2400);
        for (int i = 4800; i < 46080; i++) assertEquals(audio[i], rendered.samples[first + i - 4800], 0);
    }
    @Test public void initialUtteranceHasTimeForTheAudioOutputToStart() {
        float[] audio = new float[24000]; Arrays.fill(audio, 0, 19200, 0.5f);
        NarrationTiming.Rendered rendered = NarrationTiming.render(audio, 0, 19200, null, 0, 0);
        assertEquals(0.5f, rendered.samples[160 * 48], 0);
        for (int i = 0; i < 160 * 48; i++) assertEquals(0, rendered.samples[i], 0);
    }
    @Test public void nestedAndAsciiQuotesStayTogetherAndUnclosedQuotesStayBounded() {
        for (String text : List.of("“你来了？他说‘别急。’我们再等等。外面冷不冷？”", "\"你来了？别急。我们再等等。外面冷不冷？\"", "'你来了？别急。我们再等等。外面冷不冷？'")) {
            List<NarrationPlanner.Unit> units = NarrationPlanner.paragraph(text, counter);
            assertEquals(1, units.size()); assertEquals(SpeechText.normalize(text), joined(units));
            assertTrue(units.get(0).dialogue);
        }
        String text = "“" + "风渐渐停了下来。".repeat(40);
        List<NarrationPlanner.Unit> units = NarrationPlanner.paragraph(text, counter);
        assertEquals(SpeechText.normalize(text), joined(units));
        for (NarrationPlanner.Unit unit : units) assertTrue(counter.count(unit.text) <= 75);
    }
    @Test public void quotedLongTurnsSplitAtSentencesBeforeClauses() {
        String text = "“" + "这一路上的风雨".repeat(5) + "。" + "你到底想说什么，".repeat(8) + "”";
        List<NarrationPlanner.Unit> units = NarrationPlanner.paragraph(text, counter);
        assertEquals(SpeechText.normalize(text), joined(units));
        assertEquals(SENTENCE, units.get(0).ending); assertTrue(units.get(0).text.endsWith("。"));
    }
    @Test public void bufferingUsesPlayableDurationAndGenerationSpeedRatherThanNumberOfClips() {
        NarrationBuffer buffer = new NarrationBuffer("auto", 0);
        assertFalse(buffer.prepareChapter());
        buffer.generated(18000, 6000); assertFalse(buffer.prepareChapter());
        double ratio = buffer.ratio(); buffer.generated(0, 6000); assertEquals(ratio, buffer.ratio(), 0);
        assertTrue(buffer.aheadMs(2) > buffer.aheadMs(1));
        assertFalse(new NarrationBuffer("stream", 10).prepareChapter());
        assertTrue(new NarrationBuffer("chapter", 0.1).prepareChapter());
        assertFalse(new NarrationBuffer("auto", 10).prepareChapter());
    }
    @Test public void explicitChapterPreparationSurvivesChangingGenerationSpeed() {
        NarrationBuffer manual = new NarrationBuffer("chapter", 0);
        manual.generated(500, 10000); assertTrue(manual.prepareChapter());
        manual.generated(100000, 1000); assertTrue(manual.prepareChapter());
        NarrationBuffer legacy = new NarrationBuffer("auto", 20);
        legacy.generated(100000, 1000); assertFalse(legacy.prepareChapter());
    }
    private NarrationCache.Clip clip() throws IOException {
        File wav = folder.newFile(); Files.write(wav.toPath(), new byte[100]);
        int[] codes = new int[16]; Arrays.fill(codes, 123);
        return new NarrationCache.Clip(wav, new NarrationPlanner.Unit("来吧。", SENTENCE, false), List.of(codes), 1000, 3000, 100, 200, 100);
    }
    @Test public void generatedCodesAndTimingSurviveCacheHitsAndMalformedSidecarsAreRejected() throws Exception {
        NarrationCache.Clip clip = clip(); File data = folder.newFile(); NarrationCache.write(data, clip);
        NarrationCache.Clip restored = NarrationCache.read(clip.file, data, clip.unit);
        assertNotNull(restored); assertArrayEquals(clip.codes.get(0), restored.codes.get(0));
        assertEquals(0, restored.activeMs); assertEquals(100, restored.retainedTail);
        try (FileOutputStream out = new FileOutputStream(data, true)) { out.write(1); }
        assertNull(NarrationCache.read(clip.file, data, clip.unit));
        NarrationCache.write(data, clip); Files.write(clip.file.toPath(), new byte[101]);
        assertNull(NarrationCache.read(clip.file, data, clip.unit));
    }
    @Test public void cacheKeyIncludesVoiceAndTimingWithoutInheritingPreviousGeneratedSpeech() throws Exception {
        NarrationCache.Clip previous = clip(); NarrationPlanner.Unit next = new NarrationPlanner.Unit("进来。", PARAGRAPH, false);
        String key = NarrationCache.key("model", "voice", next, previous);
        assertEquals(key, NarrationCache.key("model", "voice", next, previous));
        assertNotEquals(key, NarrationCache.key("model", "voice", next, null));
        assertNotEquals(key, NarrationCache.key("model", "other-voice", next, previous));
        assertNotEquals(key, NarrationCache.key("model", "voice", next.ending(SENTENCE), previous));
        previous.codes.get(0)[0]++;
        assertEquals(key, NarrationCache.key("model", "voice", next, previous));
    }
}
