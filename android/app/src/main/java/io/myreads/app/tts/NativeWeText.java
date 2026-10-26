package io.myreads.app.tts;

import java.io.Closeable;
import java.io.File;
import java.nio.charset.StandardCharsets;

/** Execute the exact packaged WeText graphs; all calls are serialized by SpeechModelRuntime. */
public final class NativeWeText implements Closeable {
    static { System.loadLibrary("zijian_tokenizer"); }
    private long handle;
    public NativeWeText(File directory, String language) {
        handle = open(new File(directory, language + "_tn_tagger.fst").getAbsolutePath().getBytes(StandardCharsets.UTF_8),
            new File(directory, language + "_tn_verbalizer.fst").getAbsolutePath().getBytes(StandardCharsets.UTF_8), language.equals("en"));
    }
    public String normalize(String text) {
        if (handle == 0) throw new IllegalStateException("文本规范化器已关闭");
        return new String(normalize(handle, text.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
    }
    @Override public void close() { if (handle != 0) { release(handle); handle = 0; } }
    private static native long open(byte[] tagger, byte[] verbalizer, boolean english);
    private static native byte[] normalize(long handle, byte[] text);
    private static native void release(long handle);
}
