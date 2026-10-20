package io.myreads.app;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.WebViewListener;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebView;
import io.myreads.app.tts.ZijianTtsPlugin;
import io.myreads.app.tts.ZijianAccessibilityPlugin;
import io.myreads.app.update.UpdatePlugin;

public class MainActivity extends BridgeActivity {
    private static final String RECOVERY_TIME = "myreads.rendererRecoveryTime";
    private long lastRendererRecovery;

    @Override public void onCreate(Bundle savedInstanceState) {
        lastRendererRecovery = savedInstanceState == null ? 0 : savedInstanceState.getLong(RECOVERY_TIME);
        registerPlugin(ZijianTtsPlugin.class);
        registerPlugin(ZijianAccessibilityPlugin.class);
        registerPlugin(UpdatePlugin.class);
        super.onCreate(savedInstanceState);
        if (bridge == null) return;
        bridge.addWebViewListener(new WebViewListener() {
            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                Log.w("MyReads", "WebView renderer lost; crashed=" + detail.didCrash());
                // A terminated renderer's WebView cannot be reloaded or reused.
                // Tear down Capacitor before destroying it, then build a fresh Activity.
                if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
                bridge.onDestroy();
                bridge = null;
                view.destroy();
                long now = SystemClock.elapsedRealtime();
                boolean repeated = lastRendererRecovery > 0 && now - lastRendererRecovery < 30_000;
                lastRendererRecovery = now;
                getWindow().getDecorView().post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (!repeated) recreate();
                    else new AlertDialog.Builder(MainActivity.this)
                        .setTitle("阅读界面需要恢复")
                        .setMessage("系统连续关闭了阅读界面。可尝试重新打开，已保存的阅读进度会保留。")
                        .setPositiveButton("重新打开", (dialog, which) -> recreate())
                        .setCancelable(false).show();
                });
                return true;
            }
        });
    }

    @Override public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putLong(RECOVERY_TIME, lastRendererRecovery);
    }

    private void redrawWebView() {
        if (bridge == null) return;
        WebView view = bridge.getWebView();
        view.postOnAnimation(() -> {
            if (bridge == null || isFinishing() || isDestroyed()) return;
            view.requestLayout();
            view.invalidate();
        });
    }

    @Override public void onResume() {
        super.onResume();
        redrawWebView();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) redrawWebView();
    }
}
