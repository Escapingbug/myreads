package io.myreads.app.tts;
import java.io.File;
import java.util.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class NarrationPlanTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    @Test public void restartPreservesChunkBoundariesAndSamplingState() throws Exception {
        File directory = folder.newFolder();
        List<OfficialNarration.Chunk> chunks = Arrays.asList(new OfficialNarration.Chunk("第一段。第二段。", 7, 19200),
            new OfficialNarration.Chunk("“你来了？”她轻声问。", 9, 0));
        NarrationPlan plan = NarrationPlan.create(directory, "model", "Yuewen", "chapter-hash", 7, chunks);
        OfficialRandom random = new OfficialRandom(); random.advance(4556);
        JSONObject position = new JSONObject().put("pipeline", NarrationCache.REVISION).put("bookId", "book")
            .put("chapter", 2).put("paragraph", 9).put("voice", "Yuewen")
            .put("plan", plan.id).put("unit", 1).put("rng", random.key()).put("rngDraws", random.draws());
        NarrationPlan.Resume resume = NarrationPlan.Resume.read(position, "book", 2, 9, "Yuewen"); assertNotNull(resume);
        NarrationPlan loaded = NarrationPlan.load(directory, resume.plan, "model", "Yuewen", "chapter-hash"); assertNotNull(loaded);
        assertEquals(chunks.get(1).text, loaded.chunks.get(resume.unit).text);
        assertEquals(random.nextDouble(), resume.random.nextDouble(), 0);
        assertNull(NarrationPlan.Resume.read(position, "book", 2, 9, "Junhao"));
        assertNull(NarrationPlan.Resume.read(position, "other-book", 2, 9, "Yuewen"));
        assertNull(NarrationPlan.load(directory, plan.id, "model", "Yuewen", "changed-chapter-hash"));
    }
    @Test public void cappedSuffixReplacementLeavesQueuedAndPlayingIndicesValid() throws Exception {
        File directory = folder.newFolder();
        NarrationPlan plan = NarrationPlan.create(directory, "model", "voice", "source", 0,
            Arrays.asList(new OfficialNarration.Chunk("已排入队列。", 0, 19200), new OfficialNarration.Chunk("未完成的长段。", 2, 0)));
        plan.replace(1, Arrays.asList(new OfficialNarration.Chunk("未完成的", 2, 19200), new OfficialNarration.Chunk("长段。", 2, 0)));
        NarrationPlan restored = NarrationPlan.load(directory, plan.id, "model", "voice", "source"); assertNotNull(restored);
        assertEquals("已排入队列。", restored.chunks.get(0).text);
        assertEquals("未完成的", restored.chunks.get(1).text);
        assertEquals(0, restored.chunks.get(2).pauseSamples);
    }
    @Test public void legacyAndMalformedPositionsFallBackToPrintedParagraph() throws Exception {
        assertNull(NarrationPlan.Resume.read(new JSONObject("{\"bookId\":\"book\",\"chapter\":0,\"paragraph\":4}"), "book", 0, 4, "Yuewen"));
        assertNull(NarrationPlan.load(folder.newFolder(), "../other", "model", "voice", "source"));
        assertThrows(IllegalArgumentException.class, () -> OfficialRandom.resume("not-a-state", 0));
    }
}
