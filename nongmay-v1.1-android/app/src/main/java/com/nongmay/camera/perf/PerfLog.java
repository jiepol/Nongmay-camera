package com.nongmay.camera.perf;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;

import com.nongmay.camera.BuildInfo;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Local-only timing log. Every metric sample is appended to an app-private file so cold-start and
 * shutter numbers accumulate across launches (p50/p95 need many runs). Nothing leaves the phone
 * unless Pol exports the report himself.
 */
public final class PerfLog {
    public static final String COLD_START = "cold_start_process_to_first_preview_frame";
    public static final String WARM_START = "warm_start_to_first_preview_frame";
    public static final String CAMERA_OPEN = "camera_open_request_to_opened";
    public static final String SESSION_READY = "camera_opened_to_session_configured";
    public static final String OPEN_TO_FRAME = "camera_open_request_to_first_preview_frame";
    public static final String TAP_TO_ACK = "shutter_touch_to_visual_ack_frame";
    public static final String TAP_TO_REQUEST = "shutter_touch_to_capture_request";
    public static final String TAP_TO_SENSOR = "shutter_touch_to_sensor_exposure_start";
    public static final String TAP_TO_JPEG = "shutter_touch_to_jpeg_ready";
    public static final String TAP_TO_SAVED = "shutter_touch_to_mediastore_saved";
    public static final String VOICE_END_TO_TRIGGER = "voice_utterance_end_to_trigger";
    public static final String VOICE_END_TO_REQUEST = "voice_utterance_end_to_capture_request";
    public static final String CAMERA_SWITCH = "camera_switch_to_first_frame";
    public static final String MODE_SWITCH = "mode_switch_to_first_frame";
    public static final String SETTING_APPLY = "manual_setting_request_to_matching_result";
    public static final String VIDEO_START = "record_tap_to_recorder_started";
    public static final String VIDEO_STOP = "stop_tap_to_video_saved";
    public static final String EXPORT_PHOTO = "story_photo_export_ms";
    public static final String EXPORT_VIDEO_RT = "story_video_export_ms_per_second_of_video";

    private static final int MAX_LINES = 4000;
    private static final Map<String, List<Double>> mem = new LinkedHashMap<>();
    private static File file;
    private static long processStartElapsed;
    private static boolean coldStartPending = true;
    /** Samples written by an earlier app version: kept on disk until cleared, left out of the report. */
    private static int olderSamples;

    private PerfLog() {}

    public static synchronized void init(Context ctx) {
        file = new File(ctx.getFilesDir(), "perf_samples.tsv");
        processStartElapsed = android.os.Process.getStartElapsedRealtime();
        load();
    }

    public static long processStartElapsed() {
        return processStartElapsed;
    }

    /** First preview frame after process start counts as a cold start exactly once. */
    public static synchronized boolean consumeColdStart() {
        boolean r = coldStartPending;
        coldStartPending = false;
        return r;
    }

    public static double msSince(long elapsedNanos) {
        return (SystemClock.elapsedRealtimeNanos() - elapsedNanos) / 1e6;
    }

    public static synchronized void add(String metric, double ms) {
        if (Double.isNaN(ms) || ms < 0 || ms > 600_000) return;
        List<Double> l = mem.get(metric);
        if (l == null) { l = new ArrayList<>(); mem.put(metric, l); }
        l.add(ms);
        append(metric, ms);
    }

    public static synchronized Stats stats(String metric) {
        List<Double> l = mem.get(metric);
        if (l == null) return Stats.of(new double[0]);
        double[] v = new double[l.size()];
        for (int i = 0; i < v.length; i++) v[i] = l.get(i);
        return Stats.of(v);
    }

    public static synchronized void clear() {
        mem.clear();
        olderSamples = 0;
        if (file != null) //noinspection ResultOfMethodCallIgnored
            file.delete();
    }

    private static void append(String metric, double ms) {
        if (file == null) return;
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8)) {
            w.write(System.currentTimeMillis() + "\t" + metric + "\t" + String.format(Locale.ROOT, "%.3f", ms)
                    + "\t" + com.nongmay.camera.BuildInfo.VERSION_CODE + "\n");
        } catch (IOException ignored) {
        }
    }

    private static void load() {
        mem.clear();
        olderSamples = 0;
        if (file == null || !file.exists()) return;
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = r.readLine()) != null) lines.add(line);
        } catch (IOException ignored) {
        }
        int from = Math.max(0, lines.size() - MAX_LINES);
        for (int i = from; i < lines.size(); i++) {
            String[] p = lines.get(i).split("\t");
            if (p.length != 3 && p.length != 4) continue;
            try {
                // 1.1.0 wrote no version column; its first-frame samples contained a stale-frame artefact
                if (p.length == 3 || Integer.parseInt(p[3]) != com.nongmay.camera.BuildInfo.VERSION_CODE) {
                    olderSamples++;
                    continue;
                }
                double v = Double.parseDouble(p[2]);
                List<Double> l = mem.get(p[1]);
                if (l == null) { l = new ArrayList<>(); mem.put(p[1], l); }
                l.add(v);
            } catch (NumberFormatException ignored) {
            }
        }
        if (lines.size() > MAX_LINES) rewrite(lines.subList(from, lines.size()));
    }

    private static void rewrite(List<String> keep) {
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(file, false), StandardCharsets.UTF_8)) {
            for (String s : keep) w.write(s + "\n");
        } catch (IOException ignored) {
        }
    }

    public static synchronized String reportMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# NongMay Camera — on-device timing report\n\n");
        sb.append("- App ").append(BuildInfo.VERSION).append(" · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" · Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("- Exported ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date())).append("\n");
        sb.append("- Clocks: touch→ack uses the input/vsync clock; touch→sensor uses SENSOR_TIMESTAMP when the camera reports REALTIME timestamps, otherwise the onCaptureStarted callback time.\n");
        if (olderSamples > 0) sb.append("- Only this app version's samples are shown; ").append(olderSamples)
                .append(" older samples are hidden (Diagnostics › ล้างสถิติความเร็ว deletes them).\n");
        sb.append("\n| Metric | n | p50 ms | p95 ms | min | max |\n|---|---|---|---|---|---|\n");
        String[] order = {COLD_START, WARM_START, CAMERA_OPEN, SESSION_READY, OPEN_TO_FRAME, TAP_TO_ACK, TAP_TO_REQUEST,
                TAP_TO_SENSOR, TAP_TO_JPEG, TAP_TO_SAVED, VOICE_END_TO_TRIGGER, VOICE_END_TO_REQUEST, CAMERA_SWITCH,
                MODE_SWITCH, SETTING_APPLY, VIDEO_START, VIDEO_STOP, EXPORT_PHOTO, EXPORT_VIDEO_RT};
        for (String k : order) {
            Stats s = stats(k);
            if (s.n == 0) {
                sb.append("| ").append(k).append(" | 0 | NOT MEASURED |  |  |  |\n");
            } else {
                sb.append(String.format(Locale.ROOT, "| %s | %d | %.1f | %.1f | %.1f | %.1f |\n", k, s.n, s.p50, s.p95, s.min, s.max));
            }
        }
        java.util.List<String> selfTest = new java.util.ArrayList<>();
        for (String k : mem.keySet()) if (k.startsWith("selftest_")) selfTest.add(k);
        if (!selfTest.isEmpty()) {
            java.util.Collections.sort(selfTest);
            sb.append("\nSelf-test samples (programmatic requests from Diagnostics, not finger or voice):\n\n");
            sb.append("| Metric | n | p50 ms | p95 ms | min | max |\n|---|---|---|---|---|---|\n");
            for (String k : selfTest) {
                Stats s = stats(k);
                sb.append(String.format(Locale.ROOT, "| %s | %d | %.1f | %.1f | %.1f | %.1f |\n", k, s.n, s.p50, s.p95, s.min, s.max));
            }
        }
        return sb.toString();
    }

    public static synchronized String csv() {
        StringBuilder sb = new StringBuilder("metric,ms\n");
        for (Map.Entry<String, List<Double>> e : mem.entrySet()) {
            for (double v : e.getValue()) sb.append(e.getKey()).append(',').append(String.format(Locale.ROOT, "%.3f", v)).append('\n');
        }
        return sb.toString();
    }
}
