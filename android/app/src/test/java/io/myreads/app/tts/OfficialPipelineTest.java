package io.myreads.app.tts;

import org.junit.Test;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

public class OfficialPipelineTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private List<String> resource(String name) throws Exception {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(getClass().getResourceAsStream("/tts/" + name), StandardCharsets.UTF_8))) {
            List<String> lines = new ArrayList<>(); for (String line; (line = in.readLine()) != null;) lines.add(line); return lines;
        }
    }
    private String decode(String s) { return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8); }
    @Test public void pcg64MatchesTenThousandOfficialNumpyDrawsExactly() throws Exception {
        OfficialRandom random = new OfficialRandom();
        for (String expected : resource("official-pcg64.txt"))
            assertEquals(Long.parseUnsignedLong(expected, 16), Double.doubleToRawLongBits(random.nextDouble()));
        assertEquals(10000, random.draws());
    }
    @Test public void interruptedRequestRollsBackAndGuidanceCannotAdvanceTheBook() throws Exception {
        List<String> golden = resource("official-pcg64.txt"); OfficialRandom book = new OfficialRandom(); book.advance(4556);
        String key = book.key();
        try (OfficialRandom.Transaction attempt = book.transaction()) { book.advance(51); }
        assertEquals(key, book.key());
        new OfficialRandom().advance(2000);
        assertEquals(Long.parseUnsignedLong(golden.get(4556), 16), Double.doubleToRawLongBits(book.nextDouble()));
        try (OfficialRandom.Transaction attempt = book.transaction()) { book.advance(17); attempt.commit(); }
        assertEquals(Long.parseUnsignedLong(golden.get(4574), 16), Double.doubleToRawLongBits(book.nextDouble()));
    }
    @Test public void robustRulesMatchPinnedUpstreamFixtures() throws Exception {
        for (String row : resource("official-robust.tsv")) {
            String[] fields = row.split("\t", -1);
            assertEquals(decode(fields[0]), decode(fields[1]), OfficialText.robust(decode(fields[0])));
        }
    }
    @Test public void cacheHitRestoresTheExactNextChunkSamplingPosition() throws Exception {
        File wav = folder.newFile(), sidecar = folder.newFile(); Files.write(wav.toPath(), new byte[100]);
        List<int[]> codes = new ArrayList<>(); for (int i = 0; i < 267; i++) codes.add(new int[16]);
        NarrationPlanner.Unit unit = new NarrationPlanner.Unit("原始语音。", NarrationPlanner.Boundary.SENTENCE, false);
        NarrationCache.Clip clip = new NarrationCache.Clip(wav, unit, codes, 21760, 0, 0, 0, 0, 4556);
        NarrationCache.write(sidecar, clip); NarrationCache.Clip hit = NarrationCache.read(wav, sidecar, unit); assertNotNull(hit);
        OfficialRandom fromCache = new OfficialRandom(); fromCache.advance(hit.randomDraws);
        assertEquals(Long.parseUnsignedLong(resource("official-pcg64.txt").get(4556), 16), Double.doubleToRawLongBits(fromCache.nextDouble()));
        NarrationCache.write(sidecar, new NarrationCache.Clip(wav, unit, codes, 21760, 0, 0, 0, 0, 4555));
        assertNull(NarrationCache.read(wav, sidecar, unit));
    }
    @Test public void nativeGraphsMatchTheEntireOfficialTextPipeline() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("tts.native"));
        Map<String, NativeWeText> normalizers = new HashMap<>();
        try {
            for (String row : resource("official-normalization.tsv")) {
                String[] fields = row.split("\t", -1); String text = decode(fields[0]);
                String language = OfficialText.language(OfficialText.robust(text), "Yuewen");
                NativeWeText normalizer = normalizers.get(language);
                if (normalizer == null) { normalizer = new NativeWeText(new File("src/main/assets/tts/normalizer"), language); normalizers.put(language, normalizer); }
                final NativeWeText selected = normalizer;
                assertEquals(text, decode(fields[1]), OfficialText.prepare(text, "Yuewen", selected::normalize));
            }
        } finally { for (NativeWeText normalizer : normalizers.values()) normalizer.close(); }
    }
    @Test public void independentBlocksPackAcrossPrintedParagraphsAndKeepResumeLocations() {
        List<OfficialNarration.Paragraph> paragraphs = Arrays.asList(new OfficialNarration.Paragraph("第一段。", 3),
            new OfficialNarration.Paragraph("“你来了？”她轻声问。", 4));
        List<OfficialNarration.Chunk> chunks = OfficialNarration.chapter(paragraphs, OfficialText::robust, s -> s.codePointCount(0, s.length()));
        assertEquals(1, chunks.size()); assertEquals("第一段。“你来了？”她轻声问。", chunks.get(0).text);
        assertEquals(3, chunks.get(0).paragraph); assertEquals(0, chunks.get(0).pauseSamples);
        List<String> longText = OfficialNarration.split("他说：“等等！先别把灯关了。”她没有回答。".repeat(20), s -> s.codePointCount(0, s.length()), 75);
        assertTrue(longText.size() > 1);
        assertTrue(longText.get(0).contains("“"));
        assertEquals(19200, OfficialNarration.pauseSamples("她轻声问。"));
        assertEquals(11520, OfficialNarration.pauseSamples("There are five words here."));
    }
}
