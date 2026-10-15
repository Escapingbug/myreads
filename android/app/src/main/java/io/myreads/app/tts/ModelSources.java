package io.myreads.app.tts;

import java.util.*;

/** Mirrors retain the exact pinned revision and filename from the bundled catalog. */
public final class ModelSources {
    private ModelSources() {}
    public static List<String> urls(String official, String mode, boolean preferOfficial) {
        if (!Arrays.asList("auto", "mirror", "official").contains(mode)) throw new IllegalArgumentException("模型下载来源无效");
        if (!official.startsWith("https://huggingface.co/")) return Collections.singletonList(official);
        String mirror = "https://hf-mirror.com/" + official.substring("https://huggingface.co/".length());
        if ("mirror".equals(mode)) return Collections.singletonList(mirror);
        if ("official".equals(mode)) return Collections.singletonList(official);
        return preferOfficial ? Arrays.asList(official, mirror) : Arrays.asList(mirror, official);
    }
}
