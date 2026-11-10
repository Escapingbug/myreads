package io.myreads.app.tts;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

public class BertNarrationTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    @Test public void keepsCommaContextAndOriginalParagraphPositions() {
        List<OfficialNarration.Chunk> chunks = BertNarration.chapter(Arrays.asList(
            new OfficialNarration.Paragraph("林舟沿着石板路走，屋檐还在滴水。\n那声音很轻，却让他想起夏天。",3),
            new OfficialNarration.Paragraph("“你来了？快进来吧。”老人说。",5)));
        assertEquals("林舟沿着石板路走,屋檐还在滴水。",chunks.get(0).text);
        assertEquals(3,chunks.get(1).paragraph); assertEquals(5,chunks.get(2).paragraph);
        assertTrue(chunks.stream().allMatch(c -> c.pauseSamples==0));
        assertEquals("“你来了?",chunks.get(2).text);
    }
    @Test public void longSentencesRemainBoundedAndMixedWordsSurvive() {
        List<OfficialNarration.Chunk> chunks=BertNarration.chapter(Arrays.asList(
            new OfficialNarration.Paragraph("这本书叫 The Secret Garden，我想再读一遍。",0),
            new OfficialNarration.Paragraph("窗外的雨还在下，".repeat(40)+"天终于亮了。",1)));
        assertTrue(chunks.get(0).text.contains("The Secret Garden"));
        assertTrue(chunks.stream().allMatch(c -> c.text.codePointCount(0,c.text.length())<=75));
        assertEquals(1,chunks.get(chunks.size()-1).paragraph);
    }
    @Test public void cachesPcmWithoutMossAudioCodesOrRandomDraws() throws Exception {
        File wav=folder.newFile("new.wav"), sidecar=folder.newFile("new.codes");
        Files.write(wav.toPath(),new byte[100]);
        NarrationPlanner.Unit unit=new NarrationPlanner.Unit("雨停了。",NarrationPlanner.Boundary.SENTENCE,false);
        NarrationCache.Clip clip=new NarrationCache.Clip(wav,unit,Collections.emptyList(),1000,200,0,0,0,0);
        NarrationCache.write(sidecar,clip);
        NarrationCache.Clip hit=NarrationCache.read(wav,sidecar,unit);
        assertNotNull(hit);assertEquals(0,hit.randomDraws);assertTrue(hit.codes.isEmpty());
        assertEquals(1000,hit.durationMs);
        assertNotEquals("narration-official-onnx-v6",NarrationCache.REVISION);
    }
}
