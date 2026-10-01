package io.myreads.app.tts;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class ResumableTransferTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private static class Response extends HttpURLConnection {
        final int code; final String body, range;
        Response(int code, String body, String range) throws Exception { super(new URL("https://example.test/model")); this.code = code; this.body = body; this.range = range; }
        @Override public int getResponseCode() { return code; }
        @Override public String getHeaderField(String name) { return range; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)); }
        @Override public void connect() {}
        @Override public void disconnect() {}
        @Override public boolean usingProxy() { return false; }
    }
    private File partial(String value) throws Exception {
        File file = folder.newFile(); Files.write(file.toPath(), value.getBytes(StandardCharsets.UTF_8)); return file;
    }
    @Test public void resumeAppendsOnlyRequestedRange() throws Exception {
        File file = partial("abc");
        ResumableTransfer.write(new Response(206, "def", "bytes 3-5/6"), file, 6, () -> {}, () -> {});
        assertEquals("abcdef", new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }
    @Test public void serverIgnoringRangeReplacesPartial() throws Exception {
        File file = partial("abc");
        ResumableTransfer.write(new Response(200, "abcdef", null), file, 6, () -> {}, () -> {});
        assertEquals("abcdef", new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }
    @Test public void wrongRangeDoesNotTouchExistingData() throws Exception {
        File file = partial("abc");
        assertThrows(IOException.class, () -> ResumableTransfer.write(new Response(206, "abcdef", "bytes 0-5/6"), file, 6, () -> {}, () -> {}));
        assertEquals("abc", new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }
    @Test public void truncatedTransferRetainsDataForResume() throws Exception {
        File file = partial("");
        assertThrows(IOException.class, () -> ResumableTransfer.write(new Response(200, "abc", null), file, 6, () -> {}, () -> {}));
        assertEquals("abc", new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }
    @Test public void cancelledAndOversizedTransfersCannotComplete() throws Exception {
        File file = partial("abc");
        assertThrows(InterruptedException.class, () -> ResumableTransfer.write(new Response(206, "def", "bytes 3-5/6"), file, 6, () -> { throw new InterruptedException(); }, () -> {}));
        assertEquals("abc", new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> ResumableTransfer.write(new Response(206, "defghi", "bytes 3-8/9"), file, 6, () -> {}, () -> {}));
        assertEquals("abc", new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }
}
