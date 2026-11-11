package io.myreads.app.tts;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.media3.common.*;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
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
    private volatile long lastGenerationMs, lastGeneratedAudioMs;
    private final Set<String> pendingCache = ConcurrentHashMap.newKeySet();
    private String preparation = "", preparingChapterTitle = "";
    private int preparedUnits, totalUnits;
    private volatile boolean producing;
    private volatile boolean wantsPlayback = true;
    private volatile SynthesisGate synthesisGate = new SynthesisGate();
    private String bookId = "", bookTitle = "", chapterTitle = "", voice = "audio8-zh", error = "", phase = "idle", text = "";
    private int chapter, paragraph;
    private volatile float speed = 1;
    private String playingPlan = "", playingRandom = "";
    private int playingUnit = -1;
    private long playingDraws;
    private long progressAt;
    private volatile JSObject snapshot = new JSObject().put("phase", "idle");
    private long nextProgress;
    private long nextPreparation;
    private long playbackIntent;
    private int audioUnderruns;
    private long nextUnderrunLog;
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
        player.addAnalyticsListener(new AnalyticsListener() {
            @Override public void onAudioUnderrun(EventTime eventTime, int bufferSize, long bufferSizeMs,
                                                  long elapsedSinceLastFeedMs) {
                audioUnderruns++;
                long now = SystemClock.elapsedRealtime();
                if (now < nextUnderrunLog) return;
                nextUnderrunLog = now + 5000;
                // Distinguish AudioTrack starvation from slow speech already in
                // a complete local WAV; do not log the user's book or text.
                android.util.Log.w("ZijianTts", "Audio output underrun, count=" + audioUnderruns
                    + ", bufferMs=" + bufferSizeMs + ", sinceFeedMs=" + elapsedSinceLastFeedMs
                    + ", speed=" + player.getPlaybackParameters().speed + ", queuedMs=" + bufferedMs);
            }
        });
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
                    playingPlan = extras.getString("plan", ""); playingUnit = extras.getInt("unit", -1);
                    playingRandom = extras.getString("rng", ""); playingDraws = extras.getLong("rngDraws", 0);
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
        audioUnderruns = 0; nextUnderrunLog = 0;
        lastGenerationMs = lastGeneratedAudioMs = 0;
        bookId = intent.getStringExtra("bookId"); bookTitle = intent.getStringExtra("title");
        chapter = intent.getIntExtra("chapter", 0); paragraph = intent.getIntExtra("paragraph", 0);
        progressAt = System.currentTimeMillis();
        voice = intent.getStringExtra("voice"); speed = intent.getFloatExtra("speed", 1);
        NarrationPlan.Resume restored = null;
        try {
            restored = NarrationPlan.Resume.read(new JSONObject(getSharedPreferences("tts-playback", MODE_PRIVATE)
                .getString("position", "{}")), bookId, chapter, paragraph, voice);
        } catch (Exception ignored) {}
        final NarrationPlan.Resume resume = restored;
        // Keep a valid checkpoint while the first resumed WAV is being prepared.
        // A second process death during buffering must not erase that checkpoint.
        playingPlan = resume == null ? "" : resume.plan;
        playingUnit = resume == null ? -1 : resume.unit;
        playingRandom = resume == null ? "" : resume.random.key();
        playingDraws = resume == null ? 0 : resume.random.draws();
        text = ""; chapterTitle = "";
        preparation = "buffer"; preparedUnits = totalUnits = 0; preparingChapterTitle = "";
        player.stop(); player.clearMediaItems(); player.setPlaybackSpeed(speed); player.setPlayWhenReady(true);
        bufferedMs = 0; protectedCache = Collections.emptySet(); pendingCache.clear();
        publish();
        synchronized (bufferLock) { bufferLock.notifyAll(); }
        final String activeBook = bookId, selectedVoice = voice;
        final int startChapter = chapter, startParagraph = paragraph;
        final String mode = intent.getStringExtra("mode");
        inference.execute(() -> produce(token, gate, activeBook, selectedVoice, startChapter, startParagraph, mode, resume));
    }
    private static final class Planned {
        final NarrationPlanner.Unit unit;
        final int paragraph, pauseSamples;
        final boolean title;
        final NarrationPlan plan;
        final int index;
        Planned(OfficialNarration.Chunk chunk, boolean title) { this(chunk, title, null, -1); }
        Planned(OfficialNarration.Chunk chunk, boolean title, NarrationPlan plan, int index) {
            this.unit = new NarrationPlanner.Unit(chunk.text, title ? NarrationPlanner.Boundary.TITLE
                : NarrationPlanner.Boundary.SENTENCE, false);
            this.paragraph = chunk.paragraph; this.pauseSamples = chunk.pauseSamples; this.title = title;
            this.plan = plan; this.index = index;
        }
    }
    private static final class Ready {
        final NarrationCache.Clip clip;
        final int paragraph, index;
        final String plan, random;
        final long draws;
        Ready(NarrationCache.Clip clip, Planned planned, OfficialRandom start) {
            this.clip = clip; paragraph = planned.paragraph; index = planned.index;
            plan = planned.plan == null ? "" : planned.plan.id; random = start.key(); draws = start.draws();
        }
    }
    private void produce(int token, SynthesisGate gate, String activeBook, String selectedVoice,
                         int startChapter, int startParagraph, String mode, NarrationPlan.Resume resume) {
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
                Double.longBitsToDouble(getSharedPreferences("tts-performance-" + models.id, MODE_PRIVATE).getLong("ratio", 0)));
            JSONObject metadata = new JSONObject(LocalTtsFiles.text(new File(getFilesDir(), "tts-books/" + activeBook + ".json")));
            JSONArray chapters = metadata.getJSONArray("chapters");
            for (int ch = startChapter; ch < chapters.length(); ch++) {
                check(token);
                JSONObject reference = chapters.getJSONObject(ch);
                awaitBuffer(token, gate, buffer.aheadMs(speed));
                File contentFile = new File(getFilesDir(), "books/" + activeBook + "/" + ch + ".json");
                // Chapters may finish downloading after playback started. Atomic
                // chapter files, rather than the startup snapshot, decide availability.
                if (!reference.getBoolean("downloaded") && !contentFile.isFile()) break;
                if (!contentFile.isFile() || contentFile.length() > 4 * 1024 * 1024) throw new IOException("本地章节缺失，请重新下载这一章");
                JSONArray paragraphs = new JSONObject(LocalTtsFiles.text(contentFile)).getJSONArray("paragraphs");
                int first = ch == startChapter ? Math.min(startParagraph, Math.max(0, paragraphs.length() - 1)) : 0;
                String title = reference.getString("title");
                File planDirectory = new File(getFilesDir(), "tts-plans");
                String sourceHash = ModelRepository.hash(contentFile);
                NarrationPlan plan = null; int firstUnit = 0;
                OfficialRandom bodyRandom = new OfficialRandom(), titleRandom = new OfficialRandom();
                if (ch == startChapter && resume != null) {
                    plan = NarrationPlan.load(planDirectory, resume.plan, models.id, selectedVoice, sourceHash);
                    if (plan != null && resume.unit < plan.chunks.size() && plan.chunks.get(resume.unit).paragraph == first) {
                        firstUnit = resume.unit; bodyRandom = resume.random.copy();
                    } else plan = null;
                }
                boolean resumed = plan != null;
                if (plan == null) {
                    List<OfficialNarration.Paragraph> prose = new ArrayList<>();
                    for (int p = first; p < paragraphs.length(); p++) {
                        gate.awaitReady(() -> {}, () -> {}); check(token);
                        String original = paragraphs.getString(p);
                        if (!NarrationPlanner.sceneBreak(original)) prose.add(new OfficialNarration.Paragraph(original, p));
                    }
                    List<OfficialNarration.Chunk> chunks = prose.isEmpty() ? Collections.emptyList()
                        : SpeechModelRuntime.planChapter(this, prose, selectedVoice);
                    plan = NarrationPlan.create(planDirectory, models.id, selectedVoice, sourceHash, first, chunks);
                }
                ArrayDeque<Planned> units = new ArrayDeque<>();
                if (!resumed && first == 0) {
                    List<OfficialNarration.Paragraph> heading = Collections.singletonList(new OfficialNarration.Paragraph(title, 0));
                    for (OfficialNarration.Chunk chunk : SpeechModelRuntime.planChapter(this, heading, selectedVoice))
                        units.add(new Planned(new OfficialNarration.Chunk(chunk.text, 0,
                            0), true));
                }
                for (int i = firstUnit; i < plan.chunks.size(); i++) units.add(new Planned(plan.chunks.get(i), false, plan, i));
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
                    OfficialRandom random = planned.title ? titleRandom : bodyRandom;
                    OfficialRandom chunkStart = random.copy();
                    NarrationCache.Clip clip;
                    try { clip = audio(token, gate, models, selectedVoice, planned, random); }
                    catch (SpeechModelRuntime.YieldNarration yielded) {
                        units.addFirst(planned);
                        gate.awaitReady(() -> {}, () -> {});
                        check(token);
                        continue;
                    }
                    catch (FrameLimitException limit) {
                        int budget = Math.max(1, counter.count(planned.unit.text) / 2);
                        List<String> smaller = Audio8Text.split(planned.unit.text, counter::count, budget);
                        if (smaller.size() < 2) throw new IOException("这一句未能完整生成，请换一个声音后重试");
                        List<OfficialNarration.Chunk> replacement = new ArrayList<>();
                        for (int i = 0; i < smaller.size(); i++) {
                            String part = smaller.get(i);
                            int pause = 0;
                            replacement.add(new OfficialNarration.Chunk(part, planned.paragraph, pause));
                        }
                        if (planned.plan != null) {
                            // Only ungenerated suffixes are changed, so already
                            // queued unit indices and saved positions stay valid.
                            planned.plan.replace(planned.index, replacement);
                            units.clear();
                            for (int i = planned.index; i < planned.plan.chunks.size(); i++)
                                units.addLast(new Planned(planned.plan.chunks.get(i), false, planned.plan, i));
                        } else for (int i = replacement.size() - 1; i >= 0; i--) units.addFirst(new Planned(replacement.get(i), true));
                        count += smaller.size() - 1;
                        android.util.Log.i("ZijianTts", "Retrying oversized Audio8 chunk at a smaller token budget");
                        continue;
                    }
                    check(token);
                    buffer.generated(clip.activeMs, clip.durationMs);
                    if (clip.activeMs > 0) {
                        lastGenerationMs = clip.activeMs; lastGeneratedAudioMs = clip.durationMs;
                        getSharedPreferences("tts-performance-" + models.id, MODE_PRIVATE).edit()
                            .putLong("ratio", Double.doubleToLongBits(buffer.ratio())).apply();
                    }
                    ready.add(new Ready(clip, planned, chunkStart)); pendingCache.add(clip.file.getName());
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
                    extras.putString("plan", ready.plan); extras.putInt("unit", ready.index);
                    extras.putString("rng", ready.random); extras.putLong("rngDraws", ready.draws);
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
                                     Planned planned, OfficialRandom random) throws Exception {
        NarrationPlanner.Unit unit = planned.unit;
        File directory = new File(getCacheDir(), "tts"); directory.mkdirs();
        String name = NarrationCache.key(models.id, selectedVoice, unit, random, planned.pauseSamples);
        File destination = new File(directory, name + ".wav"), sidecar = new File(directory, name + ".codes");
        NarrationCache.Clip cached = NarrationCache.read(destination, sidecar, unit);
        if (cached != null) {
            random.advance(cached.randomDraws);
            destination.setLastModified(System.currentTimeMillis());
            android.util.Log.i("ZijianTts", "Reusing Audio8 narration cache");
            return cached;
        }
        if (directory.getUsableSpace() < 64L * 1024 * 1024) throw new IOException("存储空间不足，请先释放至少 64 MiB 空间");
        check(token); gate.awaitReady(() -> {}, () -> {});
        File partial = new File(directory, name + ".part"), partialCodes = new File(directory, name + ".codes.part");
        synthesisWake.acquire(10 * 60 * 1000L);
        NarrationCache.Clip clip;
        try {
            SynthesisResult result = SpeechModelRuntime.synthesizeOfficial(this, unit.text, partial, selectedVoice, () -> {
                check(token);
                // Release the shared model at a checkpoint; retry the uncommitted phrase
                // after guidance. Never block a paused narrator while holding the model.
                SpeechModelRuntime.checkNarration(gate);
                gate.awaitModelReady(() -> { if (synthesisWake.isHeld()) synthesisWake.release(); },
                    () -> synthesisWake.acquire(10 * 60 * 1000L), SpeechModelRuntime::guidancePending);
                check(token);
            }, random, planned.pauseSamples);
            check(token);
            if (result.getGeneratedFrames() >= 375) throw new FrameLimitException();
            if (result.getDurationMs() <= 0 || !partial.renameTo(destination)) throw new IOException("音频文件保存失败");
            clip = new NarrationCache.Clip(destination, unit, result.getAudioCodes(), result.getDurationMs(), result.getElapsedMs(),
                result.getRawLeading(), result.getRawTrailing(), result.getRetainedTail(), result.getRandomDraws());
            NarrationCache.write(partialCodes, clip);
            if (!partialCodes.renameTo(sidecar)) throw new IOException("语音缓存保存失败");
            android.util.Log.i("ZijianTts", "Generated " + clip.durationMs + "ms audio in " + clip.activeMs + "ms, voice="
                + selectedVoice + ", engine=audio8-litert, rngDraws=" + result.getRandomDraws()
                + ", stagesMs=" + result.getPrefillMs() + "/" + result.getGenerationMs() + "/" + result.getCodecMs());
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
            .put("preparation", preparation).put("preparingChapterTitle", preparingChapterTitle)
            .put("preparedUnits", preparedUnits).put("totalUnits", totalUnits).put("bufferedSeconds", bufferedMs / 1000)
            .put("generationMs", lastGenerationMs).put("generatedAudioMs", lastGeneratedAudioMs)
            .put("pipeline", NarrationCache.REVISION).put("plan", playingPlan).put("unit", playingUnit)
            .put("rng", playingRandom).put("rngDraws", playingDraws)
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
