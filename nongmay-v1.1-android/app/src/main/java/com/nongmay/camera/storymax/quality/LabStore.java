package com.nongmay.camera.storymax.quality;

import android.content.Context;

import com.nongmay.camera.storymax.ExportJob;
import com.nongmay.camera.storymax.MediaInfo;

import org.json.JSONException;
import org.json.JSONObject;

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
import java.util.List;
import java.util.Locale;

/**
 * Local experiment log (files/lab/exports.jsonl): requested profile vs measured output.
 * Stores names and measurements only — never pixels, audio or location.
 */
public final class LabStore {
    private LabStore() {}

    private static File file(Context ctx) {
        File dir = new File(ctx.getFilesDir(), "lab");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, "exports.jsonl");
    }

    public static synchronized void record(Context ctx, ExportJob job) {
        JSONObject o = new JSONObject();
        try {
            o.put("time", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date()));
            o.put("experiment", job.experimentId == null ? "" : job.experimentId);
            o.put("blind", job.blindCode == null ? "" : job.blindCode);
            o.put("profile", job.profile.code);
            o.put("source", job.source.name);
            o.put("source_summary", job.source.summary());
            o.put("mode", job.mode == 1 ? "FIT" : "FILL");
            o.put("ok", job.ok());
            o.put("error", job.error == null ? "" : job.error);
            o.put("export_ms", job.elapsedMs);
            o.put("file", job.outputName == null ? "" : job.outputName);
            if (job.actual != null) {
                MediaInfo a = job.actual;
                o.put("out_w", a.orientedW());
                o.put("out_h", a.orientedH());
                o.put("out_bytes", a.sizeBytes);
                if (a.video) {
                    o.put("out_codec", a.videoMime);
                    o.put("out_fps", Double.isNaN(a.fps) ? -1 : a.fps);
                    o.put("out_bitrate", a.bitrate);
                    o.put("out_duration_s", a.durationUs / 1e6);
                    o.put("out_color", MediaInfo.standardName(a.colorStandard) + "/" + MediaInfo.transferName(a.colorTransfer));
                    o.put("out_audio", a.hasAudio ? a.audioMime : "none");
                } else {
                    o.put("out_icc", a.hasIcc);
                }
            }
            o.put("notes", job.notesText());
        } catch (JSONException ignored) {
        }
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(file(ctx), true), StandardCharsets.UTF_8)) {
            w.write(o.toString());
            w.write('\n');
        } catch (IOException ignored) {
        }
    }

    public static synchronized List<JSONObject> all(Context ctx) {
        List<JSONObject> out = new ArrayList<>();
        File f = file(ctx);
        if (!f.exists()) return out;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                try {
                    out.add(new JSONObject(line));
                } catch (JSONException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    public static synchronized void clear(Context ctx) {
        //noinspection ResultOfMethodCallIgnored
        file(ctx).delete();
    }

    public static String csv(Context ctx) {
        String[] cols = {"time", "experiment", "blind", "profile", "source", "mode", "ok", "out_w", "out_h", "out_codec", "out_fps",
                "out_bitrate", "out_duration_s", "out_color", "out_audio", "out_icc", "out_bytes", "export_ms", "file", "error"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cols.length; i++) sb.append(i == 0 ? "" : ",").append(cols[i]);
        sb.append('\n');
        for (JSONObject o : all(ctx)) {
            for (int i = 0; i < cols.length; i++) {
                String v = o.optString(cols[i], "");
                if (v.contains(",") || v.contains("\"") || v.contains("\n")) v = "\"" + v.replace("\"", "\"\"") + "\"";
                sb.append(i == 0 ? "" : ",").append(v);
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
