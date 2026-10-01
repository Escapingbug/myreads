package io.myreads.app.tts;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.media3.common.*;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.*;
import com.getcapacitor.JSObject;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import io.myreads.app.MainActivity;
import io.myreads.app.R;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class BookPlaybackService extends MediaSessionService {
    static volatile BookPlaybackService current;
    static final String LAUNCH_TOKEN = UUID.randomUUID().toString();
    private static final ExecutorService inference = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object bufferLock = new Object();
    private final Set<String> protectedCache = ConcurrentHashMap.newKeySet();
    private ExoPlayer player;
    private MediaSession session;
    private volatile MossOnnxDemoEngine engine;
    private NativeTokenizer tokenizer;
    private PowerManager.WakeLock synthesisWake;
    private volatile int generation, buffered;
    private volatile boolean producing;
    private volatile boolean wantsPlayback = true;
    private volatile SynthesisGate synthesisGate = new SynthesisGate();
    private String bookId = "", bookTitle = "", chapterTitle = "", voice = "Junhao", error = "", phase = "idle", text = "";
    private int chapter, paragraph;
    private float speed = 1;
    private long progressAt;
    private volatile JSObject snapshot = new JSObject().put("phase", "idle");
    private long nextProgress;
    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (player != null && player.isPlaying()) { publish(false); main.postDelayed(this, 1000); }
        }
    };
    static JSObject status(Context context) {
        BookPlaybackService active = current;
        if (active != null) return active.snapshot;
        try {
            JSObject result = new JSObject(context.getSharedPreferences("tts-playback", MODE_PRIVATE).getString("position", "{}"));
            result.put("phase", "idle"); return result;
        } catch (Exception ignored) { return new JSObject().put("phase", "idle"); }
    }
    @Override public void onCreate() {
        super.onCreate(); current = this;
        synthesisWake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zijian:tts-synthesis");
        synthesisWake.setReferenceCounted(false);
        player = new ExoPlayer.Builder(this).build();
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true);
        player.setHandleAudioBecomingNoisy(true);
        player.setWakeMode(C.WAKE_MODE_LOCAL);
        session = new MediaSession.Builder(this, new ForwardingPlayer(player) {
            @Override public void stop() { stopPlayback(); }
        }).setSessionActivity(openApp()).build();
        setMediaNotificationProvider(new DefaultMediaNotificationProvider.Builder(this).setNotificationId(3002).build());
        player.addListener(new Player.Listener() {
            @Override public void onMediaItemTransition(@Nullable MediaItem item, int reason) {
                if (item != null && item.mediaMetadata.extras != null) {
                    Bundle extras = item.mediaMetadata.extras;
                    chapter = extras.getInt("chapter"); paragraph = extras.getInt("paragraph");
                    progressAt = System.currentTimeMillis();
                    chapterTitle = extras.getString("chapterTitle", ""); text = extras.getString("text", "");
                    if (player.getCurrentMediaItemIndex() > 0) player.removeMediaItems(0, player.getCurrentMediaItemIndex());
                    updateBuffer(); publish();
                }
            }
            @Override public void onPlayWhenReadyChanged(boolean value, int reason) {
                wantsPlayback = value;
                synthesisGate.setPaused(!value);
                synchronized (bufferLock) { bufferLock.notifyAll(); }
                refreshPhase();
            }
            @Override public void onPlaybackStateChanged(int state) { refreshPhase(); }
            @Override public void onIsPlayingChanged(boolean value) {
                main.removeCallbacks(heartbeat);
                if (value) main.postDelayed(heartbeat, 1000);
                refreshPhase();
            }
            @Override public void onPlaybackParametersChanged(PlaybackParameters parameters) { speed = parameters.speed; publish(); }
            @Override public void onPlayerError(PlaybackException failure) { fail("音频播放失败：" + failure.getLocalizedMessage()); }
        });
    }
    private PendingIntent openApp() {
        return PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
    private void foreground() {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager.class).createNotificationChannel(
            new NotificationChannel("tts-playback", "小说听书", NotificationManager.IMPORTANCE_LOW));
        startForeground(3002, new NotificationCompat.Builder(this, "tts-playback").setSmallIcon(R.drawable.zijian_icon)
            .setContentTitle(bookTitle.isEmpty() ? "纸间听书" : bookTitle).setContentText("正在准备声音…")
            .setContentIntent(openApp()).setOnlyAlertOnce(true).setOngoing(true).build());
    }
    @Override public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("bookId") && LAUNCH_TOKEN.equals(intent.getStringExtra("access"))
            && intent.getStringExtra("bookId").matches("[a-zA-Z0-9-]{1,100}")) {
            foreground(); startBook(intent);
        }
        return super.onStartCommand(intent, flags, startId);
    }
    private void startBook(Intent intent) {
        int token = ++generation;
        synthesisGate.cancel();
        SynthesisGate gate = new SynthesisGate(); synthesisGate = gate;
        if (engine != null) engine.setCancelled(true);
        producing = true; wantsPlayback = true; error = ""; phase = "loading";
        bookId = intent.getStringExtra("bookId"); bookTitle = intent.getStringExtra("title");
        chapter = intent.getIntExtra("chapter", 0); paragraph = intent.getIntExtra("paragraph", 0);
        progressAt = System.currentTimeMillis();
        voice = intent.getStringExtra("voice"); speed = intent.getFloatExtra("speed", 1);
        player.stop(); player.clearMediaItems(); player.setPlaybackSpeed(speed); player.setPlayWhenReady(true);
        buffered = 0; protectedCache.clear(); publish();
        synchronized (bufferLock) { bufferLock.notifyAll(); }
        final String activeBook = bookId, selectedVoice = voice;
        final int startChapter = chapter, startParagraph = paragraph;
        inference.execute(() -> produce(token, gate, activeBook, selectedVoice, startChapter, startParagraph));
    }
    private void produce(int token, SynthesisGate gate, String activeBook, String selectedVoice, int startChapter, int startParagraph) {
        try {
            check(token);
            ModelRepository models = new ModelRepository(this);
            if (!models.ready()) throw new IOException("请先下载完整的听书模型");
            gate.awaitReady(() -> {}, () -> {});
            check(token);
            JSONObject metadata = new JSONObject(LocalTtsFiles.text(new File(getFilesDir(), "tts-books/" + activeBook + ".json")));
            JSONArray chapters = metadata.getJSONArray("chapters");
            for (int ch = startChapter; ch < chapters.length(); ch++) {
                check(token);
                JSONObject reference = chapters.getJSONObject(ch);
                if (!reference.getBoolean("downloaded")) break;
                File contentFile = new File(getFilesDir(), "books/" + activeBook + "/" + ch + ".json");
                if (!contentFile.isFile() || contentFile.length() > 4 * 1024 * 1024) throw new IOException("本地章节缺失，请重新下载这一章");
                JSONArray paragraphs = new JSONObject(LocalTtsFiles.text(contentFile)).getJSONArray("paragraphs");
                int first = ch == startChapter ? Math.min(startParagraph, Math.max(0, paragraphs.length() - 1)) : 0;
                String title = reference.getString("title");
                for (int p = first; p < paragraphs.length(); p++) {
                    String prose = (p == 0 ? title + "。" : "") + paragraphs.getString(p);
                    ArrayDeque<String> pending = new ArrayDeque<>(SpeechText.segments(prose));
                    while (!pending.isEmpty()) {
                        String segment = pending.removeFirst();
                        check(token);
                        synchronized (bufferLock) {
                            while ((buffered >= 3 || !wantsPlayback) && token == generation) bufferLock.wait();
                        }
                        check(token);
                        File audio;
                        try { audio = audio(token, gate, models, selectedVoice, segment); }
                        catch (FrameLimitException limit) {
                            List<String> smaller = SpeechText.shorterSegments(segment);
                            if (smaller.size() < 2) throw new IOException("这一段未能完整生成，请换一个声音后重试");
                            for (int i = smaller.size() - 1; i >= 0; i--) pending.addFirst(smaller.get(i));
                            android.util.Log.i("ZijianTts", "Retrying capped segment in smaller pieces");
                            continue;
                        }
                        check(token);
                        int chapterIndex = ch, paragraphIndex = p;
                        CountDownLatch added = new CountDownLatch(1);
                        main.post(() -> {
                            try {
                                if (token != generation) return;
                                Bundle extras = new Bundle(); extras.putInt("chapter", chapterIndex); extras.putInt("paragraph", paragraphIndex);
                                extras.putString("chapterTitle", title); extras.putString("text", segment);
                                MediaItem item = new MediaItem.Builder().setUri(Uri.fromFile(audio)).setMediaId(audio.getName())
                                    .setMediaMetadata(new MediaMetadata.Builder().setTitle(title).setArtist(bookTitle).setExtras(extras).build()).build();
                                boolean ended = player.getPlaybackState() == Player.STATE_ENDED;
                                player.addMediaItem(item); protectedCache.add(audio.getName());
                                if (ended) player.seekTo(player.getMediaItemCount() - 1, 0);
                                if (player.getPlaybackState() == Player.STATE_IDLE || ended) player.prepare();
                                player.setPlayWhenReady(wantsPlayback); updateBuffer(); refreshPhase();
                            } finally { added.countDown(); }
                        });
                        if (!added.await(15, TimeUnit.SECONDS)) throw new IOException("播放队列未能及时响应");
                    }
                }
            }
            main.post(() -> { if (token == generation) { producing = false; refreshPhase(); } });
        } catch (InterruptedException ignored) {
        } catch (Throwable failure) {
            android.util.Log.e("ZijianTts", "Synthesis failed", failure);
            main.post(() -> { if (token == generation) fail("听书未能继续：" + (failure instanceof OutOfMemoryError ? "可用内存不足" : failure.getMessage())); });
        }
    }
    private static final class FrameLimitException extends IOException {}
    private void loadEngine(int token, SynthesisGate gate, ModelRepository models) throws Exception {
        check(token); gate.awaitReady(() -> {}, () -> {});
        if (engine != null) return;
        synthesisWake.acquire(10 * 60 * 1000L);
        NativeTokenizer nextTokenizer = null;
        try {
            nextTokenizer = new NativeTokenizer(new File(models.root, "MOSS-TTS-Nano-100M-ONNX/tokenizer.model"));
            MossOnnxDemoEngine nextEngine = new MossOnnxDemoEngine(models.root, new File(getCacheDir(), "tts"), 2);
            tokenizer = nextTokenizer; engine = nextEngine;
        } catch (Throwable failure) {
            if (nextTokenizer != null) nextTokenizer.close();
            throw failure;
        } finally { if (synthesisWake.isHeld()) synthesisWake.release(); }
    }
    private File audio(int token, SynthesisGate gate, ModelRepository models, String selectedVoice, String segment) throws Exception {
        File directory = new File(getCacheDir(), "tts"); directory.mkdirs();
        byte[] hash = MessageDigest.getInstance("SHA-256").digest((models.id + ":" + SpeechText.AUDIO_REVISION + ":" + selectedVoice + ":" + segment).getBytes(StandardCharsets.UTF_8));
        StringBuilder name = new StringBuilder(); for (byte value : hash) name.append(String.format(Locale.ROOT, "%02x", value & 255));
        File destination = new File(directory, name + ".wav");
        if (destination.isFile() && destination.length() > 44) {
            destination.setLastModified(System.currentTimeMillis());
            android.util.Log.i("ZijianTts", "Reusing cached audio");
            return destination;
        }
        // Replaying cached audio needs neither the tokenizer nor ONNX sessions.
        loadEngine(token, gate, models); check(token); engine.setCancelled(false);
        File partial = new File(directory, name + ".part");
        synthesisWake.acquire(10 * 60 * 1000L);
        try {
            SynthesisResult result = engine.synthesize(tokenizer.tokenize(segment), partial, selectedVoice, 375, 1234L, () -> {
                check(token);
                gate.awaitReady(() -> {
                    if (synthesisWake.isHeld()) synthesisWake.release();
                    android.util.Log.i("ZijianTts", "Synthesis suspended");
                }, () -> {
                    synthesisWake.acquire(10 * 60 * 1000L);
                    android.util.Log.i("ZijianTts", "Synthesis resumed");
                });
                check(token);
            });
            check(token);
            if (result.getGeneratedFrames() >= 375) throw new FrameLimitException();
            if (result.getDurationMs() <= 0 || !partial.renameTo(destination)) throw new IOException("音频文件保存失败");
            android.util.Log.i("ZijianTts", "Generated " + result.getDurationMs() + "ms audio in " + result.getElapsedMs() + "ms");
        } finally {
            partial.delete();
            if (synthesisWake.isHeld()) synthesisWake.release();
        }
        trimCache(directory, destination); return destination;
    }
    private void trimCache(File directory, File newest) {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".wav")); if (files == null) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        long total = 0; for (File file : files) total += file.length();
        for (File file : files) if (total > 128L * 1024 * 1024 && !file.equals(newest) && !protectedCache.contains(file.getName())) {
            long size = file.length(); if (file.delete()) total -= size;
        }
    }
    private void check(int token) throws InterruptedException { if (token != generation) throw new InterruptedException(); }
    private void updateBuffer() {
        protectedCache.clear();
        for (int i = player.getCurrentMediaItemIndex(); i < player.getMediaItemCount(); i++) protectedCache.add(player.getMediaItemAt(i).mediaId);
        buffered = Math.max(0, player.getMediaItemCount() - player.getCurrentMediaItemIndex());
        synchronized (bufferLock) { bufferLock.notifyAll(); }
    }
    private void refreshPhase() {
        if (phase.equals("idle") || phase.equals("error")) return;
        if (!wantsPlayback) phase = "paused";
        else if (player.isPlaying()) phase = "playing";
        else if (!producing && (player.getPlaybackState() == Player.STATE_ENDED || player.getMediaItemCount() == 0)) phase = "completed";
        else phase = engine == null ? "loading" : "buffering";
        publish();
    }
    private void publish() {
        publish(true);
    }
    private void publish(boolean saveNow) {
        snapshot = new JSObject().put("phase", phase).put("bookId", bookId).put("title", bookTitle)
            .put("chapter", chapter).put("paragraph", paragraph).put("chapterTitle", chapterTitle).put("text", text)
            .put("voice", voice).put("speed", speed).put("error", error)
            .put("updatedAt", progressAt)
            .put("positionMs", player == null ? 0 : player.getCurrentPosition());
        if (!bookId.isEmpty() && (saveNow || SystemClock.elapsedRealtime() >= nextProgress)) {
            nextProgress = SystemClock.elapsedRealtime() + 5000;
            getSharedPreferences("tts-playback", MODE_PRIVATE).edit().putString("position", snapshot.toString()).apply();
        }
        TtsEvents.emit("playbackState", snapshot);
    }
    void pausePlayback() { wantsPlayback = false; player.pause(); refreshPhase(); }
    void resumePlayback() { wantsPlayback = true; player.play(); refreshPhase(); }
    void setSpeed(float value) { player.setPlaybackSpeed(value); }
    void stopPlayback() {
        ++generation; producing = false;
        synthesisGate.cancel();
        if (engine != null) engine.setCancelled(true);
        inference.execute(this::closeEngine);
        phase = "idle"; player.stop(); player.clearMediaItems(); updateBuffer(); publish();
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
    }
    private void fail(String message) {
        ++generation; producing = false; error = message; phase = "error";
        synthesisGate.cancel();
        if (engine != null) engine.setCancelled(true);
        inference.execute(this::closeEngine);
        player.pause(); player.clearMediaItems(); updateBuffer(); publish();
        stopForeground(STOP_FOREGROUND_REMOVE);
    }
    @Override @Nullable public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return controllerInfo.getUid() == android.os.Process.myUid() || controllerInfo.isTrusted() ? session : null;
    }
    private void closeEngine() {
        if (tokenizer != null) { tokenizer.close(); tokenizer = null; }
        if (engine != null) {
            engine.close(); engine = null;
            android.util.Log.i("ZijianTts", "Released speech model");
        }
    }
    @Override public void onDestroy() {
        ++generation;
        synthesisGate.cancel();
        if (engine != null) engine.setCancelled(true);
        synchronized (bufferLock) { bufferLock.notifyAll(); }
        inference.execute(this::closeEngine);
        main.removeCallbacks(heartbeat);
        if (session != null) session.release();
        if (player != null) { player.release(); player = null; }
        if (current == this) current = null;
        super.onDestroy();
    }
}
