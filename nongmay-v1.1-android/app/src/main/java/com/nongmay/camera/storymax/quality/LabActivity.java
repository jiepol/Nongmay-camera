package com.nongmay.camera.storymax.quality;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.nongmay.camera.BuildInfo;
import com.nongmay.camera.camera.MediaStoreUtil;
import com.nongmay.camera.storymax.CropMath;
import com.nongmay.camera.storymax.ExportJob;
import com.nongmay.camera.storymax.MediaInfo;
import com.nongmay.camera.storymax.MediaProbe;
import com.nongmay.camera.storymax.ShareUtil;
import com.nongmay.camera.storymax.StoryEngine;
import com.nongmay.camera.storymax.StoryProfiles;
import com.nongmay.camera.ui.UiKit;

import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Quality Lab: batch A/B exports of one source with blind codes, the posted-Story grading
 * worksheet, and the local measurement log (CSV). Nothing is uploaded by the app.
 */
public final class LabActivity extends Activity {
    private static final int REQ_PICK = 41;
    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView log, status;
    private volatile boolean busy, cancel;
    private Uri lastWorksheet;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = UiKit.dp(this, 18);
        LinearLayout col = UiKit.col(this);
        col.setBackgroundColor(UiKit.BG);
        col.setPadding(pad, pad * 2, pad, pad * 2);
        LinearLayout head = UiKit.row(this);
        TextView back = UiKit.text(this, "‹", 30, UiKit.TEXT, false);
        back.setPadding(0, 0, UiKit.dp(this, 12), 0);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        head.addView(back);
        head.addView(UiKit.text(this, "Quality Lab", 24, UiKit.TEXT, true));
        col.addView(head);
        col.addView(UiKit.text(this, "ค่าที่นี่วัดจากไฟล์ก่อนอัปโหลดเท่านั้น คุณภาพหลัง Instagram เข้ารหัสใหม่ต้องตัดสินจาก Story ที่โพสต์จริง "
                + "ด้วยเวิร์กชีตแบบปิดชื่อ (blind)", 13, UiKit.MUTED, false));
        status = UiKit.text(this, "", 14, UiKit.ACCENT, true);
        status.setPadding(0, UiKit.dp(this, 10), 0, 0);
        col.addView(status);
        addButton(col, "สร้างชุด A/B จากไฟล์…", true, new Runnable() {
            @Override
            public void run() {
                if (getIntent().getData() != null && !busy) runAb(getIntent().getData());
                else pick();
            }
        });
        addButton(col, "เลือกไฟล์อื่นสำหรับ A/B…", false, new Runnable() {
            @Override
            public void run() {
                pick();
            }
        });
        addButton(col, "ยกเลิกชุดที่กำลังทำ", false, new Runnable() {
            @Override
            public void run() {
                cancel = true;
            }
        });
        addButton(col, "ส่งออก CSV การวัดทั้งหมด", false, new Runnable() {
            @Override
            public void run() {
                exportCsv();
            }
        });
        addButton(col, "แชร์เวิร์กชีต A/B ล่าสุด", false, new Runnable() {
            @Override
            public void run() {
                if (lastWorksheet == null) {
                    UiKit.toast(LabActivity.this, "ยังไม่มีเวิร์กชีตในรอบนี้");
                    return;
                }
                startActivity(ShareUtil.chooser(lastWorksheet, "text/markdown", "แชร์เวิร์กชีต"));
            }
        });
        addButton(col, "ล้างประวัติการวัด", false, new Runnable() {
            @Override
            public void run() {
                LabStore.clear(LabActivity.this);
                refreshLog();
            }
        });
        col.addView(UiKit.section(this, "การส่งออกล่าสุด"));
        log = UiKit.mono(this, "", 11);
        col.addView(log);
        setContentView(UiKit.scroll(this, col));
        refreshLog();
    }

    private void addButton(LinearLayout col, String text, boolean primary, final Runnable r) {
        TextView b = UiKit.button(this, text, primary);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                r.run();
            }
        });
        col.addView(b, UiKit.lpm(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, UiKit.dp(this, 8), 0, 0));
    }

    private void pick() {
        if (busy) return;
        Intent i;
        if (Build.VERSION.SDK_INT >= 33) {
            i = new Intent(MediaStore.ACTION_PICK_IMAGES);
        } else {
            i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        }
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (ActivityNotFoundException e) {
            UiKit.toast(this, "ไม่พบตัวเลือกไฟล์ของระบบ");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null && data.getData() != null) runAb(data.getData());
    }

    private void runAb(final Uri uri) {
        if (busy) return;
        busy = true;
        cancel = false;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        status.setText("กำลังเตรียม A/B…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final StringBuilder out = new StringBuilder();
                try {
                    MediaInfo src = MediaProbe.probe(LabActivity.this, uri);
                    String exp = "E" + new SimpleDateFormat("MMddHHmm", Locale.ROOT).format(new Date());
                    long seed = System.nanoTime();
                    List<StoryProfiles.Profile> set = StoryProfiles.abSet(src.video);
                    List<AbWorksheet.Row> rows = new ArrayList<>();
                    Set<String> used = new HashSet<>();
                    int i = 0;
                    for (final StoryProfiles.Profile p : set) {
                        if (cancel) break;
                        String code = AbWorksheet.blindCode(seed, i);
                        int k = 0;
                        while (!used.add(code)) code = AbWorksheet.blindCode(seed + (++k) * 7919L, i);
                        final int idx = ++i;
                        final int total = set.size();
                        ExportJob job = new ExportJob();
                        job.source = src;
                        job.profile = p;
                        job.mode = CropMath.FILL;
                        job.experimentId = exp;
                        job.blindCode = code;
                        StoryEngine.export(getApplicationContext(), job, new ExportJob.Progress() {
                            @Override
                            public void onProgress(final float f, String stage) {
                                h.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        status.setText(String.format(Locale.ROOT, "A/B %d/%d · %s · %.0f%%", idx, total, p.code, f * 100));
                                    }
                                });
                            }
                        });
                        AbWorksheet.Row r = new AbWorksheet.Row();
                        r.experimentId = exp;
                        r.blindCode = code;
                        r.profileCode = p.code;
                        r.sourceName = src.name;
                        r.fileName = job.outputName;
                        r.actual = job.ok() ? (job.actual == null ? "" : job.actual.summary()) + String.format(Locale.ROOT, " · %d ms", job.elapsedMs)
                                : "FAILED: " + job.error;
                        rows.add(r);
                        out.append(p.code).append(" → ").append(job.ok() ? "OK " + job.outputName : "FAILED " + job.error).append('\n');
                    }
                    String md = AbWorksheet.build(BuildInfo.VERSION, Build.MANUFACTURER + " " + Build.MODEL,
                            new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date()), rows);
                    lastWorksheet = MediaStoreUtil.saveText(LabActivity.this, md, "STORY_AB_TEST_" + exp + ".md", "text/markdown");
                    out.append("\nเวิร์กชีต: Download/NongMay/STORY_AB_TEST_").append(exp).append(".md");
                } catch (IOException | RuntimeException e) {
                    out.append("A/B ล้มเหลว: ").append(e.getMessage());
                }
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        busy = false;
                        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        status.setText(out.toString());
                        refreshLog();
                        if (lastWorksheet != null) startActivity(ShareUtil.chooser(lastWorksheet, "text/markdown", "เวิร์กชีต A/B"));
                    }
                });
            }
        }, "nm-ab").start();
    }

    private void exportCsv() {
        try {
            Uri u = MediaStoreUtil.saveText(this, LabStore.csv(this),
                    "STORY_EXPORTS_" + new SimpleDateFormat("yyyyMMdd_HHmm", Locale.ROOT).format(new Date()) + ".csv", "text/csv");
            startActivity(ShareUtil.chooser(u, "text/csv", "แชร์ CSV"));
        } catch (IOException e) {
            UiKit.toast(this, "บันทึก CSV ไม่ได้: " + e.getMessage());
        }
    }

    private void refreshLog() {
        List<JSONObject> all = LabStore.all(this);
        StringBuilder sb = new StringBuilder();
        for (int i = all.size() - 1, n = 0; i >= 0 && n < 60; i--, n++) {
            JSONObject o = all.get(i);
            sb.append(o.optString("time")).append("  ").append(o.optString("profile"));
            if (!o.optString("blind").isEmpty()) sb.append(" [").append(o.optString("blind")).append(']');
            sb.append('\n');
            if (o.optBoolean("ok")) {
                sb.append("  ").append(o.optInt("out_w")).append('×').append(o.optInt("out_h"));
                if (o.has("out_codec")) {
                    sb.append(' ').append(o.optString("out_codec")).append(String.format(Locale.ROOT, " %.2ffps %.1fMbps",
                            o.optDouble("out_fps", -1), o.optLong("out_bitrate", 0) / 1e6));
                }
                sb.append(String.format(Locale.ROOT, " · %d ms\n", o.optLong("export_ms")));
            } else {
                sb.append("  FAILED: ").append(o.optString("error")).append('\n');
            }
        }
        log.setText(sb.length() == 0 ? "ยังไม่มีการส่งออก" : sb.toString());
    }
}
