package io.myreads.app.tts;

import android.app.*;
import android.content.*;
import android.os.*;
import androidx.core.app.NotificationCompat;
import java.io.*;
import java.net.*;
import java.util.concurrent.atomic.AtomicBoolean;
import io.myreads.app.MainActivity;
import io.myreads.app.R;

public final class ModelDownloadService extends Service {
    static volatile boolean running;
    private static volatile ModelDownloadService current;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile HttpURLConnection connection;
    private ModelRepository repository;
    private long lastUpdate;
    public static void pause() {
        ModelDownloadService service = current;
        if (service != null) {
            service.cancelled.set(true);
            HttpURLConnection socket = service.connection;
            if (socket != null) socket.disconnect();
        }
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (running) return START_NOT_STICKY;
        current = this; running = true; cancelled.set(false);
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager.class).createNotificationChannel(
            new NotificationChannel("tts-download", "听书模型下载", NotificationManager.IMPORTANCE_LOW));
        startForeground(3001, notification("准备下载", 0));
        final boolean mirror = intent != null && intent.getBooleanExtra("mirror", false);
        new Thread(() -> download(mirror), "tts-model-download").start();
        return START_NOT_STICKY;
    }
    private Notification notification(String text, int percent) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, "tts-download").setSmallIcon(R.drawable.zijian_icon)
            .setContentTitle("正在下载听书模型").setContentText(text).setContentIntent(open)
            .setOnlyAlertOnce(true).setOngoing(true).setProgress(100, percent, false).build();
    }
    private void download(boolean mirror) {
        try {
            repository = new ModelRepository(this);
            repository.root.mkdirs();
            long remaining = repository.total - repository.downloaded();
            if (repository.root.getUsableSpace() < remaining + 128L * 1024 * 1024)
                throw new IOException("存储空间不足，请至少留出 " + ((remaining / 1024 / 1024) + 128) + " MiB");
            repository.update("downloading", "", "");
            for (ModelRepository.Entry entry : repository.files) {
                checkCancelled();
                File destination = repository.file(entry);
                if (destination.isFile()) {
                    if (destination.length() == entry.size && ModelRepository.hash(destination).equals(entry.sha256)) continue;
                    if (!destination.delete()) throw new IOException("无法替换损坏的模型文件");
                }
                File partial = repository.partial(entry);
                partial.getParentFile().mkdirs();
                if (partial.length() > entry.size && !partial.delete()) throw new IOException("无法清理未完成文件");
                Exception failure = null;
                for (int attempt = 0; attempt < 3; attempt++) {
                    checkCancelled();
                    try {
                        if (partial.length() < entry.size) transfer(entry, partial, mirror);
                        repository.update("verifying", entry.path.substring(entry.path.indexOf('/') + 1), "");
                        if (partial.length() != entry.size || !ModelRepository.hash(partial).equals(entry.sha256)) {
                            if (!partial.delete()) throw new IOException("无法清理校验失败的文件");
                            throw new IOException("模型文件校验失败，正在重新获取");
                        }
                        checkCancelled();
                        if (!partial.renameTo(destination)) throw new IOException("无法保存模型文件");
                        failure = null; break;
                    } catch (Exception error) {
                        checkCancelled(); failure = error;
                        if (attempt < 2) Thread.sleep(600L * (attempt + 1));
                    }
                }
                if (failure != null) throw failure;
            }
            checkCancelled(); repository.installed();
        } catch (InterruptedException error) {
            if (repository != null) repository.update("paused", "", "");
        } catch (Exception error) {
            if (repository != null) repository.update("error", "", "下载未完成：" + error.getMessage());
        } finally {
            HttpURLConnection socket = connection;
            if (socket != null) socket.disconnect();
            running = false; current = null;
            if (repository != null) TtsEvents.emit("modelState", repository.status());
            new Handler(Looper.getMainLooper()).post(() -> { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); });
        }
    }
    private void transfer(ModelRepository.Entry entry, File partial, boolean mirror) throws Exception {
        String source = mirror ? entry.url.replace("https://huggingface.co/", "https://hf-mirror.com/") : entry.url;
        long offset = partial.length();
        connection = connect(source, offset);
        repository.update("downloading", entry.path.substring(entry.path.indexOf('/') + 1), "");
        try {
            ResumableTransfer.write(connection, partial, entry.size, this::checkCancelled, () -> {
                long now = SystemClock.elapsedRealtime();
                if (now - lastUpdate > 500) {
                    lastUpdate = now;
                    long bytes = repository.downloaded();
                    int percent = (int) (bytes * 100 / repository.total);
                    getSystemService(NotificationManager.class).notify(3001, notification(percent + "% · " + (bytes / 1024 / 1024) + " MiB", percent));
                    TtsEvents.emit("modelState", repository.status());
                }
            });
        } finally { connection.disconnect(); }
    }
    private HttpURLConnection connect(String source, long offset) throws Exception {
        URL url = new URL(source);
        for (int redirects = 0; redirects <= 6; redirects++) {
            checkCancelled();
            if (!url.getProtocol().equals("https")) throw new IOException("模型下载地址必须使用 HTTPS");
            HttpURLConnection socket = (HttpURLConnection) url.openConnection();
            connection = socket;
            socket.setConnectTimeout(20000); socket.setReadTimeout(30000); socket.setInstanceFollowRedirects(false);
            socket.setRequestProperty("Accept-Encoding", "identity");
            if (offset > 0) socket.setRequestProperty("Range", "bytes=" + offset + "-");
            int code = socket.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String next = socket.getHeaderField("Location"); socket.disconnect();
                if (next == null) throw new IOException("模型下载重定向缺少地址");
                url = new URL(url, next);
            } else return socket;
        }
        throw new IOException("模型下载重定向过多");
    }
    private void checkCancelled() throws InterruptedException { if (cancelled.get()) throw new InterruptedException(); }
    @Override public void onTimeout(int startId, int fgsType) { pause(); stopSelf(); }
    @Override public void onDestroy() { pause(); super.onDestroy(); }
}
