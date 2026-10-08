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
    private volatile Set<String> protectedCache = Collections.emptySet();
    private ExoPlayer player;
    private MediaSession session;
    private PowerManager.WakeLock synthesisWake;
    private volatile int generation;
    private volatile long bufferedMs;
    private final Set<String> pendingCache = ConcurrentHashMap.newKeySet();
    private String preparation = "", preparingChapterTitle = "";
    private int preparedUnits, totalUnits;
    private volatile boolean producing;
    private volatile boolean wantsPlayback = true;
    private boolean continuity = true;
    private volatile SynthesisGate synthesisGate = new SynthesisGate();
    private String bookId = "", bookTitle = "", chapterTitle = "", voice = "Junhao", error = "", phase = "idle", text = "";
    private int chapter, paragraph;
    private volatile float speed = 1;
    private long progressAt;
    private volatile JSObject snapshot = new JSObject().put("phase", "idle");
    private long nextProgress;
    private long nextPreparation;
    private long playbackIntent;
    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (player != null && player.isPlaying()) { updateBuffer(); publish(false); main.postDelayed(this, 1000); }
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
            @Override public void pause() { pausePlayback(); }
            @Override public void play() { resumePlayback(); }
            @Override public void setPlayWhenReady(boolean value) { if (value) resumePlayback(); else pausePlayback(); }
            // Preparing a chapter is an active media request before the first WAV is queued.
            // Expose buffering so headset/lock-screen pause can suspend the producer too.
            @Override public int getPlaybackState() {
                int state = super.getPlaybackState();
                return producing && (state == Player.STATE_IDLE || state == Player.STATE_ENDED)
                    ? Player.STATE_BUFFERING : state;
            }
            @Override public MediaMetadata getMediaMetadata() {
                return producing && player.getMediaItemCount() == 0
                    ? new MediaMetadata.Builder().setTitle(bookTitle).setArtist("正在准备声音").build()
                    : super.getMediaMetadata();
            }
        }).setSessionActivity(openApp()).build();
        setMediaNotificationProvider(new DefaultMediaNotificationProvider.Builder(this).setNotificationId(3002).build());
        player.addListener(new Player.Listener() {
            @Override public void onMediaItemTransition(@Nullable MediaItem item, int reason) {
                if (item != null && item.mediaMetadata.extras != null && item.mediaMetadata.extras.containsKey("chapter")) {
                    Bundle extras = item.mediaMetadata.extras;
                    chapter = extras.getInt("chapter"); paragraph = extras.getInt("paragraph");
                    progressAt = System.currentTimeMillis();
                    chapterTitle = extras.getString("chapterTitle", ""); text = extras.getString("text", "");
                    if (player.getCurrentMediaItemIndex() > 0) player.removeMediaItems(0, player.getCurrentMediaItemIndex());
                    updateBuffer(); publish();
                }
            }
            @Override public void onPlayWhenReadyChanged(boolean value, int reason) {
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS || reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) playbackIntent++;
                wantsPlayback = value;
                synthesisGate.setPaused(!value);
                synchronized (bufferLock) { bufferLock.notifyAll(); }
                refreshPhase();
            }
            @Override public void onPlaybackStateChanged(int state) { updateBuffer(); refreshPhase(); }
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
        playbackIntent++;
        int token = ++generation;
        synthesisGate.cancel();
        SynthesisGate gate = new SynthesisGate(); synthesisGate = gate;
        producing = true; wantsPlayback = true; error = ""; phase = "loading";
        bookId = intent.getStringExtra("bookId"); bookTitle = intent.getStringExtra("title");
        chapter = intent.getIntExtra("chapter", 0); paragraph = intent.getIntExtra("paragraph", 0);
        progressAt = System.currentTimeMillis();
        voice = intent.getStringExtra("voice"); speed = intent.getFloatExtra("speed", 1);
        continuity = intent.getBooleanExtra("continuity", true);
        text = ""; chapterTitle = "";
        preparation = "buffer"; preparedUnits = totalUnits = 0; preparingChapterTitle = "";
        player.stop(); player.clearMediaItems(); player.setPlaybackSpeed(speed); player.setPlayWhenReady(true);
        bufferedMs = 0; protectedCache = Collections.emptySet(); pendingCache.clear();
        publish();
        synchronized (bufferLock) { bufferLock.notifyAll(); }
        final String activeBook = bookId, selectedVoice = voice;
        final int startChapter = chapter, startParagraph = paragraph;
        final String mode = intent.getStringExtra("mode");
        final boolean continuous = continuity;
        inference.execute(() -> produce(token, gate, activeBook, selectedVoice, startChapter, startParagraph, mode, continuous));
    }
    private static final class Planned {
        final NarrationPlanner.Unit unit;
        final int paragraph;
        final boolean resetContext;
        Planned(NarrationPlanner.Unit unit, int paragraph) {
            this(unit, paragraph, false);
        }
        Planned(NarrationPlanner.Unit unit, int paragraph, boolean resetContext) {
            this.unit = unit; this.paragraph = paragraph; this.resetContext = resetContext;
        }
    }
    private static final class Ready {
        final NarrationCache.Clip clip;
        final int paragraph;
        Ready(NarrationCache.Clip clip, int paragraph) { this.clip = clip; this.paragraph = paragraph; }
    }
    private void produce(int token, SynthesisGate gate, String activeBook, String selectedVoice,
                         int startChapter, int startParagraph, String mode, boolean continuous) {
        SpeechModelRuntime.retainNarration();
        try {
            check(token);
            ModelRepository models = new ModelRepository(this);
            if (!models.ready()) throw new IOException("请先补充下载听书模型");
            gate.awaitReady(() -> {}, () -> {}); check(token);
            NarrationPlanner.TokenCounter counter = value -> {
                try { return SpeechModelRuntime.tokenize(this, value).length; }
                catch (Exception failure) { throw new IllegalStateException(failure); }
            };
            NarrationBuffer buffer = new NarrationBuffer(mode == null ? "stream" : mode,
                Double.longBitsToDouble(getSharedPreferences("tts-performance", MODE_PRIVATE).getLong("ratio", 0)));
            JSONObject metadata = new JSONObject(LocalTtsFiles.text(new File(getFilesDir(), "tts-books/" + activeBook + ".json")));
            JSONArray chapters = metadata.getJSONArray("chapters");
            NarrationCache.Clip previous = null;
            NarrationContext history = new NarrationContext();
            for (int ch = startChapter; ch < chapters.length(); ch++) {
                check(token);
                history.clear();
                JSONObject reference = chapters.getJSONObject(ch);
                if (!reference.getBoolean("downloaded")) break;
                awaitBuffer(token, gate, buffer.aheadMs(speed));
                File contentFile = new File(getFilesDir(), "books/" + activeBook + "/" + ch + ".json");
                if (!contentFile.isFile() || contentFile.length() > 4 * 1024 * 1024) throw new IOException("本地章节缺失，请重新下载这一章");
                JSONArray paragraphs = new JSONObject(LocalTtsFiles.text(contentFile)).getJSONArray("paragraphs");
                int first = ch == startChapter ? Math.min(startParagraph, Math.max(0, paragraphs.length() - 1)) : 0;
                String title = reference.getString("title");
                ArrayDeque<Planned> units = new ArrayDeque<>();
                if (first == 0) {
                    for (NarrationPlanner.Unit unit : NarrationPlanner.paragraph(title + "。", counter))
                        units.add(new Planned(unit.ending(NarrationPlanner.Boundary.TITLE), 0));
                }
                boolean sceneStart = false;
                for (int p = first; p < paragraphs.length(); p++) {
                    gate.awaitReady(() -> {}, () -> {}); check(token);
                    String original = paragraphs.getString(p);
                    if (NarrationPlanner.sceneBreak(original)) { sceneStart = true; continue; }
                    List<NarrationPlanner.Unit> prose = NarrationPlanner.paragraph(original, counter);
                    for (int i = 0; i < prose.size(); i++) {
                        NarrationPlanner.Unit unit = prose.get(i);
                        if (p == paragraphs.length() - 1 && i == prose.size() - 1) unit = unit.ending(NarrationPlanner.Boundary.CHAPTER);
                        units.add(new Planned(unit, p, sceneStart && i == 0));
                    }
                    if (!prose.isEmpty()) sceneStart = false;
                }
                if (units.isEmpty()) throw new IOException("这一章没有可朗读的内容");
                boolean wholeChapter = buffer.prepareChapter();
                int done = 0, count = units.size();
                List<Ready> ready = new ArrayList<>();
                preparing(token, title, wholeChapter, done, count);
                while (!units.isEmpty()) {
                    check(token);
                    if (!wholeChapter) awaitBuffer(token, gate, buffer.aheadMs(speed));
                    else { gate.awaitReady(() -> {}, () -> {}); check(token); }
                    Planned planned = units.removeFirst();
                    if (planned.resetContext) history.clear();
                    NarrationContext.Window context = continuous ? history.before(planned.unit) : null;
                    NarrationCache.Clip clip;
                    try { clip = audio(token, gate, models, selectedVoice, planned.unit, previous, context); }
                    catch (SpeechModelRuntime.YieldNarration yielded) {
                        units.addFirst(planned);
                        gate.awaitReady(() -> {}, () -> {});
                        check(token);
                        continue;
                    }
                    catch (FrameLimitException limit) {
                        if (context != null) {
                            // Retry unplayed text once from the fixed reference. A bad
                            // continuation must never contaminate subsequent history.
                            history.clear(); units.addFirst(new Planned(planned.unit, planned.paragraph, true));
                            android.util.Log.i("ZijianTts", "Retrying capped continuation from the fixed voice reference");
                            continue;
                        }
                        history.clear();
                        List<NarrationPlanner.Unit> smaller = NarrationPlanner.retry(planned.unit, counter);
                        if (smaller.size() < 2) throw new IOException("这一句未能完整生成，请换一个声音后重试");
                        for (int i = smaller.size() - 1; i >= 0; i--)
                            units.addFirst(new Planned(smaller.get(i), planned.paragraph));
                        count += smaller.size() - 1;
                        android.util.Log.i("ZijianTts", "Retrying capped sentence at a smaller semantic boundary");
                        continue;
                    }
                    catch (SpeechModelRuntime.RunawaySpeech runaway) {
                        if (context == null) throw runaway;
                        history.clear(); units.addFirst(new Planned(planned.unit, planned.paragraph, true));
                        android.util.Log.i("ZijianTts", "Re-anchoring abnormal short continuation before playback");
                        continue;
                    }
                    check(token);
                    if (continuous) history.accept(clip, context, counter);
                    previous = clip;
                    buffer.generated(clip.activeMs, clip.durationMs);
                    if (clip.activeMs > 0) getSharedPreferences("tts-performance", MODE_PRIVATE).edit()
                        .putLong("ratio", Double.doubleToLongBits(buffer.ratio())).apply();
                    ready.add(new Ready(clip, planned.paragraph)); pendingCache.add(clip.file.getName());
                    done++;
                    preparing(token, title, wholeChapter, done, count);
                    if (!wholeChapter) {
                        // Queue the first complete phrase immediately, even if generation
                        // is slow. Keep working ahead while audio plays; gaps may buffer.
                        enqueue(token, ch, title, ready); ready.clear();
                    }
                }
                if (!ready.isEmpty()) enqueue(token, ch, title, ready);
            }
            main.post(() -> { if (token == generation) { producing = false; preparation = ""; refreshPhase(); } });
        } catch (InterruptedException ignored) {
        } catch (Throwable failure) {
            android.util.Log.e("ZijianTts", "Synthesis failed", failure);
            main.post(() -> { if (token == generation) fail("听书未能继续：" + (failure instanceof OutOfMemoryError ? "可用内存不足" : failure.getMessage())); });
        } finally { SpeechModelRuntime.releaseNarration(); }
    }
    private void preparing(int token, String title, boolean wholeChapter, int done, int count) {
        long now = SystemClock.elapsedRealtime();
        if (done > 0 && done < count && now < nextPreparation) return;
        nextPreparation = now + 250;
        main.post(() -> {
            if (token != generation) return;
            preparingChapterTitle = title; preparation = wholeChapter ? "chapter" : "buffer";
            preparedUnits = done; totalUnits = count; refreshPhase();
        });
    }
    private void awaitBuffer(int token, SynthesisGate gate, long targetMs) throws InterruptedException {
        gate.awaitReady(() -> {}, () -> {}); check(token);
        synchronized (bufferLock) {
            while ((bufferedMs >= targetMs || !wantsPlayback) && token == generation) bufferLock.wait();
        }
        check(token);
    }
    private void enqueue(int token, int chapterIndex, String title, List<Ready> clips) throws Exception {
        List<Ready> batch = new ArrayList<>(clips);
        float outputSpeed = speed;
        File outputStart = PlaybackWarmup.audio(new File(getCacheDir(), "tts-output"), outputSpeed);
        CountDownLatch added = new CountDownLatch(1);
        main.post(() -> {
            try {
                if (token != generation) return;
                List<MediaItem> items = new ArrayList<>();
                for (Ready ready : batch) {
                    NarrationCache.Clip clip = ready.clip;
                    Bundle extras = new Bundle(); extras.putInt("chapter", chapterIndex); extras.putInt("paragraph", ready.paragraph);
                    extras.putString("chapterTitle", title); extras.putString("text", clip.unit.text); extras.putLong("durationMs", clip.durationMs);
                    items.add(new MediaItem.Builder().setUri(Uri.fromFile(clip.file)).setMediaId(clip.file.getName())
                        .setMediaMetadata(new MediaMetadata.Builder().setTitle(title).setArtist(bookTitle).setExtras(extras).build()).build());
                }
                boolean ended = player.getPlaybackState() == Player.STATE_ENDED;
                boolean cold = player.getPlaybackState() == Player.STATE_IDLE || ended;
                if (cold) {
                    Bundle extras = new Bundle(); extras.putLong("durationMs", PlaybackWarmup.durationMs(outputSpeed));
                    items.add(0, new MediaItem.Builder().setUri(Uri.fromFile(outputStart)).setMediaId("output-start")
                        .setMediaMetadata(new MediaMetadata.Builder().setTitle(title).setArtist(bookTitle).setExtras(extras).build()).build());
                    android.util.Log.i("ZijianTts", "Opening narration output before speech, recovery=" + ended);
                }
                int next = player.getMediaItemCount();
                player.addMediaItems(items);
                if (ended) player.seekTo(next, 0);
                if (cold) player.prepare();
                preparation = "";
                player.setPlayWhenReady(wantsPlayback); updateBuffer();
                for (Ready ready : batch) pendingCache.remove(ready.clip.file.getName());
                refreshPhase();
            } finally { added.countDown(); }
        });
        if (!added.await(15, TimeUnit.SECONDS)) throw new IOException("播放队列未能及时响应");
        check(token);
    }
    private static final class FrameLimitException extends IOException {}
    private NarrationCache.Clip audio(int token, SynthesisGate gate, ModelRepository models, String selectedVoice,
                                     NarrationPlanner.Unit unit, NarrationCache.Clip previous, NarrationContext.Window context) throws Exception {
        File directory = new File(getCacheDir(), "tts"); directory.mkdirs();
        String name = NarrationCache.key(models.id, selectedVoice, unit, previous, context);
        File destination = new File(directory, name + ".wav"), sidecar = new File(directory, name + ".codes");
        NarrationCache.Clip cached = NarrationCache.read(destination, sidecar, unit);
        if (cached != null) {
            destination.setLastModified(System.currentTimeMillis());
            android.util.Log.i("ZijianTts", "Reusing cached narration, contextUnits=" + (context == null ? 0 : context.units));
            return cached;
        }
        if (directory.getUsableSpace() < 64L * 1024 * 1024) throw new IOException("存储空间不足，请先释放至少 64 MiB 空间");
        check(token); gate.awaitReady(() -> {}, () -> {});
        File partial = new File(directory, name + ".part"), partialCodes = new File(directory, name + ".codes.part");
        synthesisWake.acquire(10 * 60 * 1000L);
        NarrationCache.Clip clip;
        try {
            SynthesisResult result = SpeechModelRuntime.synthesize(this, unit.text, partial, selectedVoice, () -> {
                check(token);
                // Release the shared model at a checkpoint; retry the uncommitted phrase
                // after guidance. Never block a paused narrator while holding ONNX.
                SpeechModelRuntime.checkNarration(gate);
                gate.awaitModelReady(() -> { if (synthesisWake.isHeld()) synthesisWake.release(); },
                    () -> synthesisWake.acquire(10 * 60 * 1000L), SpeechModelRuntime::guidancePending);
                check(token);
            }, previous, context);
            check(token);
            if (result.getGeneratedFrames() >= 375) throw new FrameLimitException();
            if (result.getDurationMs() <= 0 || !partial.renameTo(destination)) throw new IOException("音频文件保存失败");
            clip = new NarrationCache.Clip(destination, unit, result.getAudioCodes(), result.getDurationMs(), result.getElapsedMs(),
                result.getRawLeading(), result.getRawTrailing(), result.getRetainedTail());
            NarrationCache.write(partialCodes, clip);
            if (!partialCodes.renameTo(sidecar)) throw new IOException("语音缓存保存失败");
            android.util.Log.i("ZijianTts", "Generated " + clip.durationMs + "ms audio in " + clip.activeMs + "ms, voice="
                + selectedVoice + ", ending=" + unit.ending + ", contextUnits=" + (context == null ? 0 : context.units)
                + ", contextFrames=" + (context == null ? 0 : context.codes.size()) + ", edgeMs="
                + clip.rawLeading / 48 + "/" + clip.rawTrailing / 48 + ", stagesMs="
                + result.getPrefillMs() + "/" + result.getGenerationMs() + "/" + result.getCodecMs() + "/" + result.getVadMs()
                + ", codecWarmFrames=" + result.getCodecWarmFrames() + ", retryMs=" + result.getRetryMs());
        } finally {
            partial.delete(); partialCodes.delete();
            if (synthesisWake.isHeld()) synthesisWake.release();
        }
        pendingCache.add(destination.getName()); trimCache(directory, destination); return clip;
    }
    private void trimCache(File directory, File newest) {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".wav")); if (files == null) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        long total = 0; for (File file : files) total += file.length();
        for (File file : files) if (total > 128L * 1024 * 1024 && !file.equals(newest)
            && !protectedCache.contains(file.getName()) && !pendingCache.contains(file.getName())) {
            long size = file.length(); if (file.delete()) { total -= size; new File(directory, file.getName().replace(".wav", ".codes")).delete(); }
        }
    }
    private void check(int token) throws InterruptedException { if (token != generation) throw new InterruptedException(); }
    private void updateBuffer() {
        Set<String> protectedFiles = new HashSet<>(); long duration = 0;
        for (int i = Math.max(0, player.getCurrentMediaItemIndex()); i < player.getMediaItemCount(); i++) {
            MediaItem item = player.getMediaItemAt(i); protectedFiles.add(item.mediaId);
            if (item.mediaMetadata.extras != null) duration += item.mediaMetadata.extras.getLong("durationMs");
        }
        protectedCache = protectedFiles;
        bufferedMs = Math.max(0, duration - player.getCurrentPosition());
        synchronized (bufferLock) { bufferLock.notifyAll(); }
    }
    private void refreshPhase() {
        if (phase.equals("idle") || phase.equals("error")) return;
        if (!wantsPlayback) phase = "paused";
        else if (player.isPlaying()) phase = "playing";
        else if (!producing && (player.getPlaybackState() == Player.STATE_ENDED || player.getMediaItemCount() == 0)) phase = "completed";
        else phase = SpeechModelRuntime.loaded() ? "buffering" : "loading";
        publish();
    }
    private void publish() {
        publish(true);
    }
    private void publish(boolean saveNow) {
        snapshot = new JSObject().put("phase", phase).put("bookId", bookId).put("title", bookTitle)
            .put("chapter", chapter).put("paragraph", paragraph).put("chapterTitle", chapterTitle).put("text", text)
            .put("voice", voice).put("speed", speed).put("error", error)
            .put("preparation", preparation).put("continuity", continuity).put("preparingChapterTitle", preparingChapterTitle)
            .put("preparedUnits", preparedUnits).put("totalUnits", totalUnits).put("bufferedSeconds", bufferedMs / 1000)
            .put("updatedAt", progressAt)
            .put("positionMs", player == null ? 0 : player.getCurrentPosition());
        if (!bookId.isEmpty() && (saveNow || SystemClock.elapsedRealtime() >= nextProgress)) {
            nextProgress = SystemClock.elapsedRealtime() + 5000;
            getSharedPreferences("tts-playback", MODE_PRIVATE).edit().putString("position", snapshot.toString()).apply();
        }
        TtsEvents.emit("playbackState", snapshot);
    }
    void pausePlayback() { playbackIntent++; wantsPlayback = false; player.pause(); refreshPhase(); }
    void wakeForGuidance() { synthesisGate.wakeForPriority(); }
    void resumePlayback() { playbackIntent++; wantsPlayback = true; player.play(); refreshPhase(); }
    JSObject pauseForPrompt() {
        wantsPlayback = false; player.pause(); refreshPhase();
        return new JSObject().put("bookId", bookId).put("intent", playbackIntent);
    }
    boolean resumeAfterPrompt(String expectedBook, long expectedIntent) {
        if (!bookId.equals(expectedBook) || playbackIntent != expectedIntent || !phase.equals("paused")) return false;
        resumePlayback(); return true;
    }
    void setSpeed(float value) { player.setPlaybackSpeed(value); }
    void stopPlayback() {
        playbackIntent++;
        ++generation; producing = false; preparation = ""; pendingCache.clear();
        synthesisGate.cancel();
        phase = "idle"; player.stop(); player.clearMediaItems(); updateBuffer(); publish();
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
    }
    private void fail(String message) {
        playbackIntent++;
        ++generation; producing = false; preparation = ""; pendingCache.clear(); error = message; phase = "error";
        synthesisGate.cancel();
        player.pause(); player.clearMediaItems(); updateBuffer(); publish();
        stopForeground(STOP_FOREGROUND_REMOVE);
    }
    @Override @Nullable public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return controllerInfo.getUid() == android.os.Process.myUid() || controllerInfo.isTrusted() ? session : null;
    }
    @Override public void onDestroy() {
        ++generation;
        synthesisGate.cancel();
        synchronized (bufferLock) { bufferLock.notifyAll(); }
        main.removeCallbacks(heartbeat);
        if (session != null) session.release();
        if (player != null) { player.release(); player = null; }
        if (current == this) current = null;
        super.onDestroy();
    }
}
