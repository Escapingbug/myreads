package io.myreads.app.update;

import io.myreads.app.tts.ResumableTransfer;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

public class UpdateSourcesTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private static final List<String> URLS = Arrays.asList("gitee", "github");
    @Test public void triesOtherSourceBeforeRetrying() throws Exception {
        List<String> attempts = new ArrayList<>();
        UpdateSources.download(URLS, url -> { attempts.add(url); if (url.equals("gitee")) throw new IOException("离线"); }, () -> {});
        assertEquals(URLS, attempts);
    }
    @Test public void boundsRepeatedFailures() {
        List<String> attempts = new ArrayList<>();
        assertThrows(IOException.class, () -> UpdateSources.download(URLS, url -> { attempts.add(url); throw new IOException("离线"); }, () -> {}));
        assertEquals(Arrays.asList("gitee", "github", "gitee", "github"), attempts);
    }
    @Test public void pinnedSourceNeverTriesAnother() throws Exception {
        List<String> attempts = new ArrayList<>();
        UpdateSources.download(Collections.singletonList("github"), url -> { attempts.add(url); if (attempts.size() == 1) throw new IOException("retry"); }, () -> {});
        assertEquals(Arrays.asList("github", "github"), attempts);
    }
    @Test public void pauseDoesNotFallThroughToAnotherSource() {
        List<String> attempts = new ArrayList<>();
        assertThrows(InterruptedException.class, () -> UpdateSources.download(URLS,
            url -> { attempts.add(url); throw new InterruptedException(); }, () -> { if (!attempts.isEmpty()) throw new InterruptedException(); }));
        assertEquals(Collections.singletonList("gitee"), attempts);
    }
    private static class Response extends HttpURLConnection {
        final String text; final int offset;
        Response(String text, int offset) throws Exception { super(new URL("https://example.test/apk")); this.text = text; this.offset = offset; }
        @Override public int getResponseCode() { return offset == 0 ? 200 : 206; }
        @Override public String getHeaderField(String name) { return "bytes " + offset + "-5/6"; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)); }
        @Override public void connect() {} @Override public void disconnect() {} @Override public boolean usingProxy() { return false; }
    }
    @Test public void continuesPartialBytesOnTheSecondSource() throws Exception {
        File partial = folder.newFile(); List<Long> offsets = new ArrayList<>();
        UpdateSources.download(URLS, url -> {
            offsets.add(partial.length());
            ResumableTransfer.write(new Response(url.equals("gitee") ? "abc" : "def", (int) partial.length()), partial, 6, () -> {}, () -> {});
        }, () -> {});
        assertEquals(Arrays.asList(0L, 3L), offsets);
        assertEquals("abcdef", new String(Files.readAllBytes(partial.toPath()), StandardCharsets.UTF_8));
    }
}
