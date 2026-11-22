package com.nongmay.camera;

import android.app.Application;

import com.nongmay.camera.camera.MediaStoreUtil;
import com.nongmay.camera.perf.PerfLog;
import com.nongmay.camera.settings.Settings;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class App extends Application {
    private static App instance;
    private Settings settings;
    private final ExecutorService background = Executors.newSingleThreadExecutor();

    public static App get() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        PerfLog.init(this);
        settings = new Settings(this);
        // Finish or remove items a previous crash left in IS_PENDING state (never touches other apps' media).
        background.execute(new Runnable() {
            @Override
            public void run() {
                MediaStoreUtil.cleanupOrphans(App.this);
            }
        });
    }

    public Settings settings() {
        return settings;
    }

    public ExecutorService background() {
        return background;
    }
}
