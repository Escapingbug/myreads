package io.myreads.app.update;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Intent;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import com.getcapacitor.*;
import com.getcapacitor.annotation.*;
import java.lang.ref.WeakReference;
import java.util.concurrent.*;

@CapacitorPlugin(name = "AppUpdate", permissions = {
    @Permission(alias = "notifications", strings = {Manifest.permission.POST_NOTIFICATIONS})
})
public final class UpdatePlugin extends Plugin {
    private static WeakReference<UpdatePlugin> active = new WeakReference<>(null);
    private static final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService files = Executors.newSingleThreadExecutor();
    @Override public void load() { active = new WeakReference<>(this); }
    static void emit(UpdateStore store) {
        try {
            JSObject value = store.status();
            main.post(() -> { UpdatePlugin plugin = active.get(); if (plugin != null) plugin.notifyListeners("updateState", value); });
        } catch (Exception ignored) { /* The next getStatus reports any storage error. */ }
    }
    private boolean canInstall() { return Build.VERSION.SDK_INT < 26 || getContext().getPackageManager().canRequestPackageInstalls(); }
    @PluginMethod public void getStatus(PluginCall call) {
        files.execute(() -> {
            try {
                UpdateStore store = new UpdateStore(getContext());
                call.resolve(new JSObject().put("versionName", store.installed().versionName).put("versionCode", UpdateStore.code(store.installed()))
                    .put("canInstall", canInstall()).put("download", store.status()));
            } catch (Exception error) { call.reject(error.getMessage(), error); }
        });
    }
    @PluginMethod public void download(PluginCall call) {
        if (Build.VERSION.SDK_INT >= 33 && getPermissionState("notifications") == PermissionState.PROMPT) requestPermissionForAlias("notifications", call, "startDownload");
        else startDownload(call);
    }
    @PermissionCallback private void startDownload(PluginCall call) {
        files.execute(() -> {
            try {
                if (!UpdateDownloadService.running) {
                    UpdateStore store = new UpdateStore(getContext()); store.prepare(call.getData().has("url") ? call.getData() : store.metadata());
                    UpdateDownloadService.running = true;
                    try { ContextCompat.startForegroundService(getContext(), new Intent(getContext(), UpdateDownloadService.class)); }
                    catch (Exception error) { UpdateDownloadService.running = false; store.phase("error", error.getMessage()); throw error; }
                }
                call.resolve();
            } catch (Exception error) { call.reject("无法下载更新：" + error.getMessage(), error); }
        });
    }
    @PluginMethod public void pause(PluginCall call) { UpdateDownloadService.pause(); call.resolve(); }
    @PluginMethod public void install(PluginCall call) {
        files.execute(() -> {
            try {
                UpdateStore store = new UpdateStore(getContext());
                if (!"ready".equals(store.status().getString("phase"))) throw new IllegalStateException("请先下载更新");
                store.verify(store.apk);
                getActivity().runOnUiThread(() -> {
                    try {
                        if (!canInstall()) {
                            getActivity().startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getContext().getPackageName())));
                            call.resolve(new JSObject().put("permissionRequired", true));
                            return;
                        }
                        Uri uri = FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", store.apk);
                        Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        getActivity().startActivity(intent);
                        getContext().getSystemService(NotificationManager.class).cancel(4002);
                        call.resolve(new JSObject().put("permissionRequired", false));
                    } catch (Exception error) { call.reject("无法打开安装页面：" + error.getMessage(), error); }
                });
            } catch (Exception error) { call.reject(error.getMessage(), error); }
        });
    }
    @Override protected void handleOnDestroy() { if (active.get() == this) active.clear(); files.shutdown(); }
}
