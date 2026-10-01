package io.myreads.app.update;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.*;
import android.os.Build;
import com.getcapacitor.JSObject;
import org.json.JSONObject;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

final class UpdateStore {
    final Context context;
    final File root, partial, apk;
    final SharedPreferences prefs;
    UpdateStore(Context context) {
        this.context = context.getApplicationContext();
        root = new File(context.getFilesDir(), "updates");
        partial = new File(root, "update.apk.part"); apk = new File(root, "update.apk");
        prefs = context.getSharedPreferences("app-update", Context.MODE_PRIVATE);
    }
    PackageInfo installed() throws Exception { return context.getPackageManager().getPackageInfo(context.getPackageName(), signatureFlags()); }
    static long code(PackageInfo info) { return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode; }
    static int signatureFlags() { return Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES; }
    JSONObject metadata() throws Exception { return new JSONObject(prefs.getString("metadata", "{}")); }
    void validate(JSONObject data) throws Exception {
        UpdatePolicy.validate(data.getString("url"), data.getString("versionName"), data.getLong("versionCode"), code(installed()), data.getLong("size"), data.getString("sha256"));
    }
    synchronized void prepare(JSONObject data) throws Exception {
        validate(data);
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("无法创建更新下载目录");
        if (!data.getString("sha256").equals(metadata().optString("sha256"))) clearFiles();
        if (partial.length() > data.getLong("size")) remove(partial);
        if (root.getUsableSpace() < data.getLong("size") - partial.length() + 96L * 1024 * 1024) throw new IOException("存储空间不足，请清理后重试");
        prefs.edit().putString("metadata", data.toString()).putString("phase", "downloading").putString("error", "").commit();
    }
    JSObject status() throws Exception {
        JSONObject data = metadata();
        String phase = prefs.getString("phase", "idle");
        if (data.optLong("versionCode") <= code(installed()) && !phase.equals("idle") && !UpdateDownloadService.running) {
            clearFiles(); prefs.edit().clear().commit(); phase = "idle"; data = new JSONObject();
        } else if ((phase.equals("downloading") || phase.equals("verifying")) && !UpdateDownloadService.running) {
            phase = "paused"; prefs.edit().putString("phase", phase).commit();
        } else if (phase.equals("ready") && !apk.isFile()) {
            phase = "error"; prefs.edit().putString("phase", phase).putString("error", "安装包已被清理，请重新下载").commit();
        }
        return new JSObject().put("phase", phase).put("downloaded", apk.isFile() ? apk.length() : partial.length())
            .put("total", data.optLong("size")).put("versionName", data.optString("versionName"))
            .put("error", prefs.getString("error", ""));
    }
    void phase(String value, String error) { prefs.edit().putString("phase", value).putString("error", error).commit(); UpdatePlugin.emit(this); }
    void verify(File file) throws Exception {
        JSONObject data = metadata(); validate(data);
        if (file.length() != data.getLong("size") || !hash(file).equals(data.getString("sha256"))) throw new IOException("安装包校验失败，请重新下载");
        PackageManager pm = context.getPackageManager();
        PackageInfo candidate = pm.getPackageArchiveInfo(file.getAbsolutePath(), signatureFlags());
        PackageInfo current = installed();
        if (candidate == null || !context.getPackageName().equals(candidate.packageName)
            || code(candidate) != data.getLong("versionCode") || !data.getString("versionName").equals(candidate.versionName))
            throw new IOException("安装包的应用或版本与发布清单不符");
        Set<String> before = signatures(current), after = signatures(candidate);
        if (before.isEmpty() || !before.equals(after)) throw new IOException("安装包签名与已安装应用不同，已停止更新");
    }
    private static Set<String> signatures(PackageInfo info) {
        Signature[] values = Build.VERSION.SDK_INT >= 28
            ? (info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners()) : info.signatures;
        Set<String> result = new HashSet<>();
        if (values != null) for (Signature value : values) result.add(value.toCharsString());
        return result;
    }
    static String hash(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[256 * 1024]; int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
    void clearFiles() throws IOException { remove(partial); remove(apk); }
    static void remove(File file) throws IOException { if (file.exists() && !file.delete()) throw new IOException("无法清理旧安装包"); }
}
