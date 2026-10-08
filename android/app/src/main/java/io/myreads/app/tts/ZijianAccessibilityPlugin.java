package io.myreads.app.tts;

import android.Manifest;
import android.content.*;
import android.media.*;
import android.net.Uri;
import android.os.*;
import android.speech.*;
import com.getcapacitor.*;
import com.getcapacitor.annotation.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@CapacitorPlugin(name = "ZijianAccessibility", permissions = {
    @Permission(alias = "microphone", strings = {Manifest.permission.RECORD_AUDIO})
})
public final class ZijianAccessibilityPlugin extends Plugin {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicInteger generation = new AtomicInteger();
    private volatile boolean enabled;
    private MediaPlayer player;
    private PluginCall speaking, recording;
    private SpeechRecognizer recognizer;
    private AudioFocusRequest focusRequest;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable recognitionTimeout = () -> finishRecognition(null, "没有听到搜索内容，请重试");
    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            // Invalidate even an in-flight prompt so unplugging headphones cannot
            // restore narration out loud after the prompt completes.
            cancelSpeech();
            if (BookPlaybackService.current != null) BookPlaybackService.current.pausePlayback();
        }
    };
    @Override public void load() {
        IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= 33) getContext().registerReceiver(noisy, filter, Context.RECEIVER_NOT_EXPORTED);
        else getContext().registerReceiver(noisy, filter);
    }

    @PluginMethod public void status(PluginCall call) {
        getActivity().runOnUiThread(() -> call.resolve(new JSObject().put("loaded", enabled && SpeechModelRuntime.loaded())
            .put("recognitionAvailable", SpeechRecognizer.isRecognitionAvailable(getContext()))
            .put("microphoneGranted", getPermissionState("microphone") == PermissionState.GRANTED)));
    }
    @PluginMethod public void configureRecognition(PluginCall call) {
        if (getPermissionState("microphone") != PermissionState.GRANTED) {
            requestPermissionForAlias("microphone", call, "recognitionConfigured"); return;
        }
        recognitionConfigured(call);
    }
    @PermissionCallback private void recognitionConfigured(PluginCall call) {
        if (getPermissionState("microphone") != PermissionState.GRANTED) { call.reject("尚未允许麦克风权限，请在系统设置中允许后重试"); return; }
        getActivity().runOnUiThread(() -> call.resolve(new JSObject()
            .put("recognitionAvailable", SpeechRecognizer.isRecognitionAvailable(getContext()))));
    }
    @PluginMethod public void enable(PluginCall call) {
        SpeechModelRuntime.beginGuidance();
        worker.execute(() -> {
            try { SpeechModelRuntime.enable(getContext()); enabled = true; call.resolve(); }
            catch (Throwable failure) { call.reject("无法加载语音模型：" + (failure instanceof OutOfMemoryError ? "可用内存不足" : failure.getMessage())); }
            finally { SpeechModelRuntime.endGuidance(); }
        });
    }
    @PluginMethod public void disable(PluginCall call) {
        enabled = false;
        cancelSpeech();
        getActivity().runOnUiThread(() -> finishRecognition(null, "录音已取消"));
        worker.execute(() -> { SpeechModelRuntime.disable(); call.resolve(); });
    }
    @PluginMethod public void stopSpeech(PluginCall call) { cancelSpeech(); call.resolve(); }
    private void cancelSpeech() {
        generation.incrementAndGet();
        getActivity().runOnUiThread(() -> finishSpeech(true, null));
    }
    @PluginMethod public void speak(PluginCall call) {
        String text = call.getString("text", "").trim(), voice = call.getString("voice", "Junhao");
        if (!enabled || !SpeechModelRuntime.loaded()) { call.reject("请先加载模型并开启无障碍模式"); return; }
        if (text.isEmpty() || text.length() > 4096 || !Arrays.asList("Junhao", "Zhiming", "Weiguo", "Xiaoyu", "Yuewen", "Lingyu").contains(voice)) {
            call.reject("语音提示参数无效"); return;
        }
        int token = generation.incrementAndGet();
        getActivity().runOnUiThread(() -> finishSpeech(true, null));
        SpeechModelRuntime.beginGuidance();
        worker.execute(() -> {
            PowerManager.WakeLock wake = getContext().getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zijian:accessibility-speech");
            try {
                check(token); wake.acquire(10 * 60 * 1000L);
                File directory = new File(getContext().getCacheDir(), "accessibility-speech"); directory.mkdirs();
                NarrationPlanner.TokenCounter counter = value -> {
                    try { return SpeechModelRuntime.tokenize(getContext(), value).length; }
                    catch (Exception failure) { throw new IllegalStateException(failure); }
                };
                ArrayDeque<NarrationPlanner.Unit> units = new ArrayDeque<>(NarrationPlanner.paragraph(text, counter));
                List<File> clips = new ArrayList<>();
                while (!units.isEmpty()) {
                    check(token);
                    NarrationPlanner.Unit unit = units.removeFirst();
                    File file = new File(directory, key(voice + ":" + unit.text) + ".wav");
                    if (!file.isFile() || file.length() <= 44) {
                        File partial = new File(file.getPath() + ".part");
                        try {
                            SynthesisResult result = SpeechModelRuntime.synthesize(getContext(), unit.text, partial, voice, () -> check(token), null);
                            check(token);
                            if (result.getGeneratedFrames() >= 375) {
                                List<NarrationPlanner.Unit> smaller = NarrationPlanner.retry(unit, counter);
                                if (smaller.size() < 2) throw new IOException("提示语音未能完整生成，请重试");
                                for (int i = smaller.size() - 1; i >= 0; i--) units.addFirst(smaller.get(i));
                                continue;
                            }
                            if (result.getDurationMs() <= 0 || !partial.renameTo(file)) throw new IOException("无法保存提示语音");
                        } finally { partial.delete(); }
                    }
                    file.setLastModified(System.currentTimeMillis()); clips.add(file);
                }
                trim(directory, clips);
                getActivity().runOnUiThread(() -> {
                    if (token != generation.get() || !enabled) { call.resolve(new JSObject().put("cancelled", true)); return; }
                    speaking = call;
                    play(clips, 0, token);
                });
            } catch (InterruptedException cancelled) { call.resolve(new JSObject().put("cancelled", true)); }
            catch (Throwable failure) { call.reject("语音提示失败：" + (failure instanceof OutOfMemoryError ? "可用内存不足" : failure.getMessage())); }
            finally { if (wake.isHeld()) wake.release(); SpeechModelRuntime.endGuidance(); }
        });
    }
    private void check(int token) throws InterruptedException {
        if (!enabled || token != generation.get()) throw new InterruptedException();
    }
    private String key(String text) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(("moss-ui-v1:" + text).getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(); for (byte b : digest) result.append(String.format("%02x", b)); return result.toString();
    }
    private void trim(File directory, List<File> protectedFiles) {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".wav")); if (files == null) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        long total = 0; for (File file : files) total += file.length();
        for (File file : files) if (total > 64L * 1024 * 1024 && !protectedFiles.contains(file)) {
            long bytes = file.length(); if (file.delete()) total -= bytes;
        }
    }
    private void play(List<File> clips, int index, int token) {
        if (token != generation.get() || !enabled) return;
        if (index >= clips.size()) { finishSpeech(false, null); return; }
        try {
            if (player != null) player.release();
            player = new MediaPlayer();
            AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
            player.setAudioAttributes(attributes);
            AudioManager audio = getContext().getSystemService(AudioManager.class);
            if (Build.VERSION.SDK_INT >= 26 && focusRequest == null) {
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener(change -> { if (change < 0) cancelSpeech(); }).build();
                if (audio.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    finishSpeech(true, null); return;
                }
            }
            player.setDataSource(getContext(), Uri.fromFile(clips.get(index)));
            player.setOnPreparedListener(value -> value.start());
            player.setOnCompletionListener(value -> play(clips, index + 1, token));
            player.setOnErrorListener((value, what, extra) -> { if (token == generation.get()) finishSpeech(false, "提示音频播放失败"); return true; });
            player.prepareAsync();
        } catch (Exception failure) { finishSpeech(false, failure.getMessage()); }
    }
    private void finishSpeech(boolean cancelled, String error) {
        if (player != null) { player.release(); player = null; }
        if (Build.VERSION.SDK_INT >= 26 && focusRequest != null) {
            getContext().getSystemService(AudioManager.class).abandonAudioFocusRequest(focusRequest); focusRequest = null;
        }
        PluginCall call = speaking; speaking = null;
        if (call != null) { if (error == null) call.resolve(new JSObject().put("cancelled", cancelled)); else call.reject(error); }
    }
    @PluginMethod public void recognize(PluginCall call) {
        if (!enabled) { call.reject("请先开启无障碍模式"); return; }
        if (getPermissionState("microphone") != PermissionState.GRANTED) {
            call.reject("需要麦克风权限，请由协助者在设置中配置语音搜索后重试"); return;
        }
        startRecognition(call);
    }
    private void startRecognition(PluginCall call) {
        if (getPermissionState("microphone") != PermissionState.GRANTED) { call.reject("需要麦克风权限才能语音搜索，请由协助者在设置中允许"); return; }
        getActivity().runOnUiThread(() -> {
            if (!SpeechRecognizer.isRecognitionAvailable(getContext())) { call.reject("此手机没有可用的语音识别服务，可使用最近搜索或请协助者配置"); return; }
            if (recording != null) { call.reject("正在听取搜索内容"); return; }
            cancelSpeech(); recording = call;
            recognizer = SpeechRecognizer.createSpeechRecognizer(getContext());
            recognizer.setRecognitionListener(new RecognitionListener() {
                public void onReadyForSpeech(Bundle params) {
                    ToneGenerator tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 70);
                    tone.startTone(ToneGenerator.TONE_PROP_BEEP, 150); main.postDelayed(tone::release, 250);
                    notifyListeners("recognitionState", new JSObject().put("listening", true));
                }
                public void onBeginningOfSpeech() {}
                public void onRmsChanged(float value) {}
                public void onBufferReceived(byte[] buffer) {}
                public void onEndOfSpeech() { notifyListeners("recognitionState", new JSObject().put("listening", false)); }
                public void onError(int error) { finishRecognition(null, error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    ? "没有听清，请重新说书名或作者" : "语音识别失败，请检查网络和识别服务后重试"); }
                public void onResults(Bundle results) {
                    ArrayList<String> texts = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    finishRecognition(texts == null || texts.isEmpty() ? null : texts.get(0), texts == null || texts.isEmpty() ? "没有听清，请重试" : null);
                }
                public void onPartialResults(Bundle results) {}
                public void onEvent(int type, Bundle params) {}
            });
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN"); intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            try { recognizer.startListening(intent); main.postDelayed(recognitionTimeout, 30000); }
            catch (Exception error) { finishRecognition(null, error.getMessage()); }
        });
    }
    @PluginMethod public void stopRecognition(PluginCall call) {
        getActivity().runOnUiThread(() -> { finishRecognition(null, "录音已取消"); call.resolve(); });
    }
    private void finishRecognition(String text, String error) {
        main.removeCallbacks(recognitionTimeout);
        if (recognizer != null) { recognizer.cancel(); recognizer.destroy(); recognizer = null; }
        PluginCall call = recording; recording = null;
        if (call != null) { if (error != null) call.reject(error); else call.resolve(new JSObject().put("text", text)); }
        notifyListeners("recognitionState", new JSObject().put("listening", false));
    }
    @Override protected void handleOnPause() { cancelSpeech(); finishRecognition(null, "录音已取消"); }
    @Override protected void handleOnDestroy() {
        enabled = false; cancelSpeech(); finishRecognition(null, "录音已取消");
        getContext().unregisterReceiver(noisy);
        worker.execute(SpeechModelRuntime::disable); worker.shutdown();
    }
}
