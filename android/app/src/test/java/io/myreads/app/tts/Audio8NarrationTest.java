package io.myreads.app.tts;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
public class Audio8NarrationTest {
    @Test public void packsShortSentencesAndPreservesCommaContext() {
        String text = "雨停了。他推开窗，看见月亮升了起来。Good evening.";
        List<OfficialNarration.Chunk> chunks = Audio8Narration.chapter(Arrays.asList(new OfficialNarration.Paragraph(text, 7)));
        assertEquals(1, chunks.size()); assertEquals(text, chunks.get(0).text); assertEquals(7, chunks.get(0).paragraph);
    }
    @Test public void keepsParagraphPositionAndBoundsLongClauses() {
        String text = "窗外雨声渐渐停了，".repeat(20);
        List<OfficialNarration.Chunk> chunks = Audio8Narration.chapter(Arrays.asList(new OfficialNarration.Paragraph(text, 3),new OfficialNarration.Paragraph("下一段。", 9)));
        StringBuilder result = new StringBuilder();
        for (OfficialNarration.Chunk c : chunks) { assertTrue(c.text.codePointCount(0,c.text.length()) <= 49); if(c.paragraph==3)result.append(c.text); }
        assertEquals(text,result.toString()); assertEquals(9,chunks.get(chunks.size()-1).paragraph);
    }
}
