package io.myreads.app.tts;

import java.lang.ref.WeakReference;
import android.os.Handler;
import android.os.Looper;
import com.getcapacitor.JSObject;

final class TtsEvents {
    private static WeakReference<ZijianTtsPlugin> plugin = new WeakReference<>(null);
    private static final Handler main = new Handler(Looper.getMainLooper());
    static void attach(ZijianTtsPlugin value) { plugin = new WeakReference<>(value); }
    static void emit(String event, JSObject status) {
        main.post(() -> {
            ZijianTtsPlugin current = plugin.get();
            if (current != null) current.publish(event, status);
        });
    }
}
