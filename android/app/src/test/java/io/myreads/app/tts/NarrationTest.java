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
        List<NarrationPlanner.Unit> units = NarrationPlanner.paragraph("他抬起头。“你是谁？”门外没有回答……风又吹了起来。", counter);
        assertEquals(4, units.size());
        assertEquals(SENTENCE, units.get(0).ending); assertEquals(QUESTION, units.get(1).ending);
        assertTrue(units.get(1).dialogue); assertEquals(ELLIPSIS, units.get(2).ending);
        assertEquals(PARAGRAPH, units.get(3).ending);
        assertEquals("他抬起头。", units.get(0).text);
        assertEquals("你是谁？", units.get(1).text);
    }
    @Test public void doesNotGreedilyCombineSeparateShortSentences() {
        List<NarrationPlanner.Unit> units = NarrationPlanner.paragraph("好。走吧。天亮了。", counter);
        assertEquals(3, units.size()); assertEquals("好。", units.get(0).text);
    }
    @Test public void keepsShortDialogueAttributionWithTheQuotedUtterance() {
        List<NarrationPlanner.Unit> units = NarrationPlanner.paragraph("“你来了？”她轻声问。屋里又安静下来。", counter);
        assertEquals(2, units.size()); assertEquals("你来了？她轻声问。", units.get(0).text);
        assertTrue(units.get(0).dialogue); assertEquals(SENTENCE, units.get(0).ending);
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
        List<NarrationPlanner.Unit> units = NarrationPlanner.paragraph("温度是3.5度。Wait... Then go.", counter);
        assertEquals(3, units.size()); assertTrue(units.get(0).text.contains("三点五"));
        assertEquals(ELLIPSIS, units.get(1).ending);
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
        assertArrayEquals(quiet, NarrationTiming.render(quiet, -1, -1, null, 0, 0).samples, 0);
    }
    @Test public void bufferingUsesPlayableDurationAndGenerationSpeedRatherThanNumberOfClips() {
        NarrationBuffer buffer = new NarrationBuffer("auto", 0);
        assertFalse(buffer.prepareChapter(1));
        buffer.generated(18000, 6000); assertTrue(buffer.prepareChapter(1));
        double ratio = buffer.ratio(); buffer.generated(0, 6000); assertEquals(ratio, buffer.ratio(), 0);
        assertFalse(buffer.start(19000, 2, false)); assertTrue(buffer.start(20000, 2, false));
        assertTrue(buffer.start(2000, 2, true)); assertTrue(buffer.aheadMs(2) > buffer.aheadMs(1));
        assertFalse(new NarrationBuffer("stream", 10).prepareChapter(1));
        assertTrue(new NarrationBuffer("chapter", 0.1).prepareChapter(1));
        assertTrue(new NarrationBuffer("auto", 0.6).prepareChapter(2));
    }
    private NarrationCache.Clip clip() throws IOException {
        File wav = folder.newFile(); Files.write(wav.toPath(), new byte[100]);
        int[] codes = new int[16]; Arrays.fill(codes, 123);
        return new NarrationCache.Clip(wav, new NarrationPlanner.Unit("来吧。", SENTENCE, false), List.of(codes), 1000, 3000, 100, 200, 100);
    }
    @Test public void audioContextSurvivesCacheHitsAndMalformedSidecarsAreRejected() throws Exception {
        NarrationCache.Clip clip = clip(); File data = folder.newFile(); NarrationCache.write(data, clip);
        NarrationCache.Clip restored = NarrationCache.read(clip.file, data, clip.unit);
        assertNotNull(restored); assertArrayEquals(clip.codes.get(0), restored.codes.get(0));
        assertEquals(0, restored.activeMs); assertEquals(100, restored.retainedTail);
        try (FileOutputStream out = new FileOutputStream(data, true)) { out.write(1); }
        assertNull(NarrationCache.read(clip.file, data, clip.unit));
        NarrationCache.write(data, clip); Files.write(clip.file.toPath(), new byte[101]);
        assertNull(NarrationCache.read(clip.file, data, clip.unit));
    }
    @Test public void cacheKeyChangesWhenPriorAudioOrStructuralContextChanges() throws Exception {
        NarrationCache.Clip previous = clip(); NarrationPlanner.Unit next = new NarrationPlanner.Unit("进来。", PARAGRAPH, false);
        String key = NarrationCache.key("model", "voice", next, previous, true);
        assertEquals(key, NarrationCache.key("model", "voice", next, previous, true));
        assertNotEquals(key, NarrationCache.key("model", "voice", next, previous, false));
        assertNotEquals(key, NarrationCache.key("model", "voice", next.ending(SENTENCE), previous, true));
        previous.codes.get(0)[0]++;
        assertNotEquals(key, NarrationCache.key("model", "voice", next, previous, true));
    }
    @Test public void continuationPlacesPreviousTranscriptInUserAndSpeechAfterAssistantStart() {
        List<String> encoded = new ArrayList<>();
        int[] result = NarrationPrompt.continuation(value -> { encoded.add(value); return new int[]{100 + encoded.size()}; }, "前句。", "后句。", 4, 5, 7);
        assertEquals("前句。后句。", encoded.get(4));
        assertEquals("None", encoded.get(2)); assertEquals("assistant\n", encoded.get(7));
        assertEquals(4, result[0]); assertEquals(5, result[7]); assertEquals(4, result[9]); assertEquals(7, result[result.length - 1]);
    }
}
