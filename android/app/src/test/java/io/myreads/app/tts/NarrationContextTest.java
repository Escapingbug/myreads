package io.myreads.app.tts;

import java.io.File;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static io.myreads.app.tts.NarrationPlanner.Boundary.*;

public class NarrationContextTest {
    private final NarrationPlanner.TokenCounter tokens = text -> text.codePointCount(0, text.length());
    private final NarrationPlanner.Unit target = new NarrationPlanner.Unit("你终于来了。", SENTENCE, false);
    private NarrationCache.Clip clip(String text, NarrationPlanner.Boundary end, int frames, int code) {
        List<int[]> codes = new ArrayList<>();
        for (int i = 0; i < frames; i++) { int[] row = new int[16]; Arrays.fill(row, code); codes.add(row); }
        return new NarrationCache.Clip(new File("unused.wav"), new NarrationPlanner.Unit(text, end, false), codes,
            frames * 80L, 0, 0, 0, 0);
    }
    @Test public void carriesMultipleCompleteUnitsAcrossOrdinaryParagraphs() {
        NarrationContext history = new NarrationContext();
        history.accept(clip("他在门外等了一夜。", PARAGRAPH, 50, 11), null, tokens);
        NarrationContext.Window first = history.before(target);
        history.accept(clip("天亮了，他听见熟悉的脚步声。", PARAGRAPH, 60, 22), first, tokens);
        NarrationContext.Window next = history.before(target);
        assertEquals("他在门外等了一夜。\n天亮了，他听见熟悉的脚步声。\n", next.text);
        assertEquals(2, next.units); assertEquals(110, next.codes.size());
        assertEquals(11, next.codes.get(49)[0]); assertEquals(22, next.codes.get(50)[0]);
    }
    @Test public void frameAndTextBudgetsEvictWholeUnitsRatherThanArbitraryAudioTails() {
        NarrationContext history = new NarrationContext();
        history.accept(clip("旧句。", SENTENCE, 200, 11), null, tokens);
        history.accept(clip("新句。", PARAGRAPH, 150, 22), history.before(target), tokens);
        NarrationContext.Window next = history.before(target);
        assertEquals("新句。\n", next.text); assertEquals(150, next.codes.size());
        history.clear();
        history.accept(clip("前".repeat(140) + "。", SENTENCE, 50, 11), null, tokens);
        history.accept(clip("后".repeat(20) + "。", PARAGRAPH, 50, 22), history.before(target), tokens);
        assertEquals("后".repeat(20) + "。\n", history.before(target).text);
        history.accept(clip("特别长的一整句。", PARAGRAPH, 350, 33), history.before(target), tokens);
        assertNull(history.before(target));
    }
    @Test public void tinyPromptsAccumulateBeforeTheyBecomeContinuationContext() {
        NarrationContext history = new NarrationContext();
        history.accept(clip("好。", PARAGRAPH, 12, 11), null, tokens);
        assertNull(history.before(target));
        history.accept(clip("走吧。", PARAGRAPH, 14, 22), null, tokens);
        assertEquals("好。\n走吧。\n", history.before(target).text);
    }
    @Test public void periodicAnchorAndTitleChapterOrExplicitResetDoNotReuseOldSpeech() {
        NarrationContext history = new NarrationContext();
        history.accept(clip("先前的叙述。", PARAGRAPH, 30, 11), null, tokens);
        for (int i = 0; i < NarrationContext.MAX_CONTINUATIONS; i++) {
            NarrationContext.Window context = history.before(target); assertNotNull(context);
            history.accept(clip("后续叙述。", PARAGRAPH, 30, 22), context, tokens);
        }
        assertNull(history.before(target));
        history.accept(clip("新锚点。", PARAGRAPH, 30, 33), null, tokens);
        assertNull(history.before(target.ending(TITLE)));
        history.accept(clip("新的叙述。", PARAGRAPH, 30, 11), null, tokens);
        assertNotNull(history.before(target.ending(CHAPTER)));
        history.accept(clip("章末。", CHAPTER, 30, 22), history.before(target), tokens);
        assertNull(history.before(target));
        history.accept(clip("新的叙述。", PARAGRAPH, 30, 11), null, tokens);
        history.clear(); assertNull(history.before(target));
    }
    @Test public void cacheKeysIncludeTheEntireAlignedTextAndAudioPrefix() throws Exception {
        NarrationContext history = new NarrationContext();
        NarrationCache.Clip a = clip("先前的叙述。", PARAGRAPH, 30, 11);
        history.accept(a, null, tokens);
        NarrationContext.Window stable = history.before(target);
        String key = NarrationCache.key("model", "voice", target, a, stable);
        assertNotEquals(key, NarrationCache.key("model", "voice", target, a, null));
        a.codes.get(0)[0]++;
        assertEquals(key, NarrationCache.key("model", "voice", target, a, stable));
        assertNotEquals(key, NarrationCache.key("model", "voice", target, a, history.before(target)));
        NarrationContext.Window differentText = new NarrationContext.Window("不同的前文。\n", stable.codes, stable.units);
        assertNotEquals(key, NarrationCache.key("model", "voice", target, a, differentText));
    }
    @Test public void promptPlacesFullTranscriptInUserAndLeavesAudioStartOpenForPrefixCodes() {
        int[] prompt = NarrationPrompt.continuation(value -> value.codePoints().toArray(),
            "上一句。\n再上一句。\n", "当前句。", -1, -2, -3);
        assertEquals(-1, prompt[0]); assertEquals(-3, prompt[prompt.length - 1]);
        StringBuilder printable = new StringBuilder();
        for (int id : prompt) if (id >= 0) printable.appendCodePoint(id);
        assertTrue(printable.toString().contains("- Reference(s):\nNone"));
        assertTrue(printable.toString().contains("- Text:\n上一句。\n再上一句。\n当前句。"));
        assertTrue(printable.toString().endsWith("assistant\n"));
    }
    @Test public void separateQuotedTurnsStaySeparateAndSceneMarkersAreRecognized() {
        List<NarrationPlanner.Unit> units = NarrationPlanner.paragraph("“你来了？”“我来了。”他放下行李。", tokens);
        assertEquals(3, units.size()); assertTrue(units.get(0).dialogue); assertTrue(units.get(1).dialogue);
        assertFalse(units.get(2).dialogue);
        assertTrue(NarrationPlanner.sceneBreak("　***　")); assertTrue(NarrationPlanner.sceneBreak("\n "));
        assertFalse(NarrationPlanner.sceneBreak("他停住了……"));
    }
}
