package io.myreads.app.tts;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class ModelSourcesTest {
    private static final String OFFICIAL = "https://huggingface.co/owner/repo/resolve/abc123/weights.data";
    private static final String MIRROR = "https://hf-mirror.com/owner/repo/resolve/abc123/weights.data";
    @Test public void prefersMirrorAndPreservesThePinnedRevision() {
        assertEquals(Arrays.asList(MIRROR, OFFICIAL), ModelSources.urls(OFFICIAL, "auto", false));
        assertEquals(Arrays.asList(OFFICIAL, MIRROR), ModelSources.urls(OFFICIAL, "auto", true));
    }
    @Test public void manualSelectionsStayOnTheSelectedSource() {
        assertEquals(Collections.singletonList(MIRROR), ModelSources.urls(OFFICIAL, "mirror", false));
        assertEquals(Collections.singletonList(OFFICIAL), ModelSources.urls(OFFICIAL, "official", false));
    }
    @Test public void doesNotRewriteNonHuggingFaceFiles() {
        String url = "https://raw.githubusercontent.com/snakers4/silero-vad/revision/file.onnx";
        assertEquals(Collections.singletonList(url), ModelSources.urls(url, "auto", false));
        assertThrows(IllegalArgumentException.class, () -> ModelSources.urls(OFFICIAL, "other", false));
    }
}
