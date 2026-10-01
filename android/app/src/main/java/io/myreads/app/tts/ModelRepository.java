package io.myreads.app.tts;

import android.content.Context;
import android.content.SharedPreferences;
import com.getcapacitor.JSObject;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

final class ModelRepository {
    final File root;
    final String id;
    final List<Entry> files = new ArrayList<>();
    final long total;
    private final SharedPreferences preferences;
    ModelRepository(Context context) throws Exception {
        JSONObject catalog;
        try (InputStream stream = context.getAssets().open("tts/catalog.json")) {
            catalog = new JSONObject(LocalTtsFiles.text(stream));
        }
        id = catalog.getString("id");
        root = new File(context.getNoBackupFilesDir(), "tts/" + id);
        preferences = context.getSharedPreferences("tts-model", Context.MODE_PRIVATE);
        JSONArray array = catalog.getJSONArray("files");
        long size = 0;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.getJSONObject(i);
            Entry entry = new Entry(item.getString("path"), item.getString("url"), item.getLong("size"), item.getString("sha256"));
            files.add(entry); size += entry.size;
        }
        total = size;
    }
    boolean ready() {
        if (!new File(root, ".ready").isFile()) return false;
        for (Entry entry : files) if (file(entry).length() != entry.size) return false;
        return true;
    }
    File file(Entry entry) { return new File(root, entry.path); }
    File partial(Entry entry) { return new File(root, entry.path + ".part"); }
    long downloaded() {
        long bytes = 0;
        for (Entry entry : files) bytes += Math.min(entry.size, file(entry).isFile() ? file(entry).length() : partial(entry).length());
        return bytes;
    }
    JSObject status() {
        boolean installed = ready();
        String phase = installed ? "ready" : preferences.getString("phase", "missing");
        if (!installed && (phase.equals("ready") || phase.equals("missing"))) phase = downloaded() > 0 ? "paused" : "missing";
        if (!ModelDownloadService.running && (phase.equals("downloading") || phase.equals("verifying"))) phase = "paused";
        return new JSObject().put("id", id).put("phase", phase).put("downloaded", downloaded())
            .put("total", total).put("file", preferences.getString("file", ""))
            .put("error", preferences.getString("error", ""));
    }
    void update(String phase, String filename, String error) {
        preferences.edit().putString("phase", phase).putString("file", filename).putString("error", error).apply();
        TtsEvents.emit("modelState", status());
    }
    void installed() throws Exception {
        File marker = new File(root, ".ready.tmp");
        try (FileOutputStream stream = new FileOutputStream(marker)) {
            stream.write(id.getBytes(StandardCharsets.UTF_8)); stream.getFD().sync();
        }
        if (!marker.renameTo(new File(root, ".ready"))) throw new IOException("无法完成模型安装");
        update("ready", "", "");
    }
    void remove() throws Exception {
        delete(root);
        update("missing", "", "");
    }
    static void delete(File file) throws IOException {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        if (file.exists() && !file.delete()) throw new IOException("无法删除 " + file.getName());
    }
    static String hash(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream stream = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[1024 * 1024]; int count;
            while ((count = stream.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
    static final class Entry {
        final String path, url, sha256; final long size;
        Entry(String path, String url, long size, String sha256) {
            this.path = path; this.url = url; this.size = size; this.sha256 = sha256;
        }
    }
}
