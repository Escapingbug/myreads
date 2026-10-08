package io.myreads.app.tts;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.*;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class PlaybackWarmupTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    @Test public void coversTheSameWallTimeAcrossPlaybackSpeeds() {
        for (float speed : new float[]{0.5f, 1, 1.25f, 1.5f, 2})
            assertEquals(500, PlaybackWarmup.durationMs(speed) / speed, 1);
        assertEquals(500, PlaybackWarmup.durationMs(Float.NaN));
    }
    @Test public void silenceMatchesNarrationPcmAndCanBeReusedWithoutChangingSpeech() throws Exception {
        File file = PlaybackWarmup.audio(folder.getRoot(), 1.5f);
        byte[] bytes = Files.readAllBytes(file.toPath());
        ByteBuffer wav = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(1, wav.getShort(22)); assertEquals(48000, wav.getInt(24));
        assertEquals(16, wav.getShort(34)); assertEquals(750 * 48 * 2, wav.getInt(40));
        for (int i = 44; i < bytes.length; i++) assertEquals(0, bytes[i]);
        long modified = file.lastModified();
        assertEquals(file, PlaybackWarmup.audio(folder.getRoot(), 1.5f));
        assertEquals(modified, file.lastModified());
    }
}
