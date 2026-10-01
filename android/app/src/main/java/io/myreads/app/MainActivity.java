package io.myreads.app;

import com.getcapacitor.BridgeActivity;
import android.os.Bundle;
import io.myreads.app.tts.ZijianTtsPlugin;
import io.myreads.app.update.UpdatePlugin;

public class MainActivity extends BridgeActivity {
    @Override public void onCreate(Bundle savedInstanceState) {
        registerPlugin(ZijianTtsPlugin.class);
        registerPlugin(UpdatePlugin.class);
        super.onCreate(savedInstanceState);
    }
}
