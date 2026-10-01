package io.myreads.app.update;

import android.app.*;
import android.content.*;
import android.os.*;
import androidx.core.app.NotificationCompat;
import io.myreads.app.MainActivity;
import io.myreads.app.R;
import io.myreads.app.tts.ResumableTransfer;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class UpdateDownloadService extends Service {
    static volatile boolean running;
    private static volatile UpdateDownloadService current;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile HttpURLConnection connection;
    private UpdateStore store;
    private long lastProgress;
    static void pause() {
        UpdateDownloadService service = current;
        if (service != null) {
            service.cancelled.set(true);
            HttpURLConnection socket = service.connection;
            if (socket != null) socket.disconnect();
        }
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (current != null) return START_NOT_STICKY;
        current = this; running = true;
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager.class).createNotificationChannel(
            new NotificationChannel("app-update", "应用更新", NotificationManager.IMPORTANCE_LOW));
        startForeground(4001, notification("准备下载", 0, false));
        new Thread(this::download, "app-update-download").start();
        return START_NOT_STICKY;
    }
    private Notification notification(String text, int percent, boolean ready) {
        PendingIntent open = PendingIntent.getActivity(this, 4001, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "app-update").setSmallIcon(R.drawable.zijian_icon)
            .setContentTitle(ready ? "纸间更新已下载，打开应用安装" : "正在下载纸间更新")
            .setContentText(text).setContentIntent(open).setOnlyAlertOnce(true).setOngoing(!ready).setAutoCancel(ready);
        if (!ready) builder.setProgress(100, percent, false);
        return builder.build();
    }
    private void download() {
        boolean ready = false;
        try {
            store = new UpdateStore(this);
            JSONObject data = store.metadata(); store.validate(data);
            for (int attempt = 0; ; attempt++) {
                checkCancelled();
                try {
                    if (store.partial.length() < data.getLong("size")) transfer(data);
                    store.phase("verifying", ""); store.verify(store.partial); checkCancelled();
                    UpdateStore.remove(store.apk);
                    if (!store.partial.renameTo(store.apk)) throw new IOException("无法保存安装包");
                    store.phase("ready", ""); ready = true; break;
                } catch (Exception error) {
                    checkCancelled();
                    if (store.partial.length() >= data.getLong("size")) UpdateStore.remove(store.partial);
                    if (attempt >= 2) throw error;
                    store.phase("downloading", ""); Thread.sleep(700L * (attempt + 1));
                }
            }
        } catch (InterruptedException error) {
            if (store != null) store.phase("paused", "");
        } catch (Exception error) {
            if (store != null) store.phase("error", "下载未完成：" + error.getMessage());
        } finally {
            HttpURLConnection socket = connection; if (socket != null) socket.disconnect();
            running = false; current = null;
            if (store != null) UpdatePlugin.emit(store);
            final boolean complete = ready;
            new Handler(Looper.getMainLooper()).post(() -> {
                stopForeground(STOP_FOREGROUND_REMOVE);
                if (complete) getSystemService(NotificationManager.class).notify(4002, notification("点击后确认安装", 100, true));
                stopSelf();
            });
        }
    }
    private void transfer(JSONObject data) throws Exception {
        long offset = store.partial.length();
        connection = connect(data.getString("url"), offset);
        store.phase("downloading", "");
        try {
            ResumableTransfer.write(connection, store.partial, data.getLong("size"), this::checkCancelled, () -> {
                long now = SystemClock.elapsedRealtime();
                if (now - lastProgress >= 400) {
                    lastProgress = now;
                    int percent = (int)(store.partial.length() * 100 / data.optLong("size"));
                    getSystemService(NotificationManager.class).notify(4001, notification(percent + "%", percent, false));
                    UpdatePlugin.emit(store);
                }
            });
        } finally { connection.disconnect(); }
    }
    private HttpURLConnection connect(String source, long offset) throws Exception {
        URI uri = URI.create(source);
        for (int redirects = 0; redirects <= 6; redirects++) {
            checkCancelled();
            if (!UpdatePolicy.allowedRedirect(uri)) throw new IOException("更新下载地址无效");
            HttpURLConnection socket = (HttpURLConnection) uri.toURL().openConnection(); connection = socket;
            socket.setConnectTimeout(15000); socket.setReadTimeout(30000); socket.setInstanceFollowRedirects(false);
            socket.setRequestProperty("User-Agent", "Zijian-Updater"); socket.setRequestProperty("Accept-Encoding", "identity");
            if (offset > 0) socket.setRequestProperty("Range", "bytes=" + offset + "-");
            int code = socket.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = socket.getHeaderField("Location"); socket.disconnect();
                if (location == null) throw new IOException("更新下载重定向缺少地址");
                uri = uri.resolve(location); continue;
            }
            return socket;
        }
        throw new IOException("更新下载重定向次数过多");
    }
    private void checkCancelled() throws InterruptedException { if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new InterruptedException(); }
    @Override public void onTimeout(int startId, int fgsType) { pause(); stopSelf(); }
    @Override public void onDestroy() { cancelled.set(true); HttpURLConnection socket = connection; if (socket != null) socket.disconnect(); super.onDestroy(); }
}
