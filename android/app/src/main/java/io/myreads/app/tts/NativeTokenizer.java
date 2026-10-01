package io.myreads.app.tts;

import java.io.Closeable;
import java.io.File;

public final class NativeTokenizer implements Closeable {
    static { System.loadLibrary("zijian_tokenizer"); }
    private long handle;
    public NativeTokenizer(File model) { handle = open(model.getAbsolutePath()); }
    public int[] tokenize(String input) {
        if (handle == 0) throw new IllegalStateException("分词器已关闭");
        return encode(handle, input);
    }
    @Override public void close() {
        if (handle != 0) { release(handle); handle = 0; }
    }
    private static native long open(String path);
    private static native int[] encode(long handle, String input);
    private static native void release(long handle);
}
