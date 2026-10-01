package io.myreads.app.tts;

import android.Manifest;
import android.content.Intent;
import android.os.Build;
import androidx.core.content.ContextCompat;
import com.getcapacitor.*;
import com.getcapacitor.annotation.*;
import org.json.*;
import java.io.File;
import java.util.Set;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.*;

@CapacitorPlugin(name = "ZijianTts", permissions = {
    @Permission(alias = "notifications", strings = {Manifest.permission.POST_NOTIFICATIONS})
})
public final class ZijianTtsPlugin extends Plugin {
    private final ExecutorService files = Executors.newSingleThreadExecutor();
    private static final Set<String> VOICES = new HashSet<>(Arrays.asList("Junhao", "Zhiming", "Weiguo", "Xiaoyu", "Yuewen", "Lingyu"));
    @Override public void load() { TtsEvents.attach(this); }
    void publish(String event, JSObject status) { notifyListeners(event, status); }
    @PluginMethod public void getStatus(PluginCall call) {
        files.execute(() -> {
            try { call.resolve(new JSObject().put("model", new ModelRepository(getContext()).status()).put("playback", BookPlaybackService.status(getContext()))); }
            catch (Exception error) { call.reject(error.getMessage(), error); }
        });
    }
    private boolean requestNotification(PluginCall call, String callback) {
        if (Build.VERSION.SDK_INT >= 33 && getPermissionState("notifications") == PermissionState.PROMPT) {
            requestPermissionForAlias("notifications", call, callback); return true;
        }
        return false;
    }
    @PluginMethod public void downloadModel(PluginCall call) {
        if (!requestNotification(call, "downloadAfterPermission")) downloadAfterPermission(call);
    }
    @PermissionCallback private void downloadAfterPermission(PluginCall call) {
        files.execute(() -> {
            try {
                ModelRepository repository = new ModelRepository(getContext());
                if (!repository.ready() && !ModelDownloadService.running) {
                    Intent intent = new Intent(getContext(), ModelDownloadService.class);
                    intent.putExtra("mirror", Boolean.TRUE.equals(call.getBoolean("mirror", false)));
                    ContextCompat.startForegroundService(getContext(), intent);
                }
                call.resolve();
            } catch (Exception error) { call.reject("无法开始下载：" + error.getMessage(), error); }
        });
    }
    @PluginMethod public void pauseDownload(PluginCall call) { ModelDownloadService.pause(); call.resolve(); }
    @PluginMethod public void removeModel(PluginCall call) {
        if (ModelDownloadService.running) { call.reject("请先暂停模型下载"); return; }
        getActivity().runOnUiThread(() -> {
            if (BookPlaybackService.current != null) BookPlaybackService.current.stopPlayback();
            files.execute(() -> {
                try {
                    new ModelRepository(getContext()).remove();
                    ModelRepository.delete(new File(getContext().getCacheDir(), "tts"));
                    call.resolve();
                } catch (Exception error) { call.reject(error.getMessage(), error); }
            });
        });
    }
    @PluginMethod public void play(PluginCall call) {
        if (!requestNotification(call, "playAfterPermission")) playAfterPermission(call);
    }
    @PermissionCallback private void playAfterPermission(PluginCall call) {
        files.execute(() -> {
            try {
                if (!new ModelRepository(getContext()).ready()) throw new IllegalStateException("请先下载听书模型");
                String id = call.getString("bookId", "");
                String voice = call.getString("voice", "Junhao");
                if (!id.matches("[a-zA-Z0-9-]{1,100}") || !VOICES.contains(voice)) throw new IllegalArgumentException("听书参数无效");
                JSONArray chapters = call.getArray("chapters");
                int chapter = call.getInt("chapter", 0), paragraph = call.getInt("paragraph", 0);
                if (chapters == null || chapters.length() == 0 || chapters.length() > 20000 || chapter < 0 || chapter >= chapters.length() || paragraph < 0)
                    throw new IllegalArgumentException("听书章节无效");
                if (!chapters.getJSONObject(chapter).getBoolean("downloaded")) throw new IllegalArgumentException("请先下载这一章");
                JSONObject metadata = new JSONObject().put("chapters", chapters);
                LocalTtsFiles.write(new File(getContext().getFilesDir(), "tts-books/" + id + ".json"), metadata.toString());
                Intent intent = new Intent(getContext(), BookPlaybackService.class);
                intent.putExtra("access", BookPlaybackService.LAUNCH_TOKEN);
                intent.putExtra("bookId", id).putExtra("title", call.getString("title", "小说听书"))
                    .putExtra("chapter", chapter).putExtra("paragraph", paragraph).putExtra("voice", voice)
                    .putExtra("speed", clampSpeed(call.getFloat("speed", 1f)))
                    .putExtra("mode", call.getString("mode", "auto"));
                ContextCompat.startForegroundService(getContext(), intent); call.resolve();
            } catch (Exception error) { call.reject(error.getMessage(), error); }
        });
    }
    private float clampSpeed(float value) { return Float.isFinite(value) ? Math.max(0.5f, Math.min(2f, value)) : 1f; }
    @PluginMethod public void control(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            BookPlaybackService active = BookPlaybackService.current;
            if (active != null) {
                switch (call.getString("action", "")) {
                    case "pause": active.pausePlayback(); break;
                    case "resume": active.resumePlayback(); break;
                    case "stop": active.stopPlayback(); break;
                    case "speed": active.setSpeed(clampSpeed(call.getFloat("speed", 1f))); break;
                    default: call.reject("未知听书操作"); return;
                }
            }
            call.resolve();
        });
    }
    @Override protected void handleOnDestroy() { files.shutdown(); }
}
