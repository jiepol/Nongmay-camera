package com.nongmay.camera.storymax;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.nongmay.camera.App;
import com.nongmay.camera.settings.Settings;
import com.nongmay.camera.storymax.quality.LabActivity;
import com.nongmay.camera.ui.UiKit;
import com.nongmay.camera.util.Fmt;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * StoryMax: import (Photo Picker / share sheet / fresh capture) → exact 9:16 framing →
 * export a copy (original untouched) → save + Android share. Works without the camera engine.
 */
public final class StoryActivity extends Activity {
    private static final int REQ_PICK = 31;

    private Settings prefs;
    private final Handler h = new Handler(Looper.getMainLooper());
    private MediaInfo source;
    private ExportJob running;
    private ExportJob lastJob;
    private StoryProfiles.Profile profile;
    private boolean advanced;

    private StoryCanvasView canvas;
    private TextView summary, tech, techToggle, status, resultText, empty;
    private LinearLayout modeRow, profileRow, resultBox, controls;
    private ProgressBar bar;
    private TextView exportBtn, cancelBtn;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = App.get().settings();
        buildUi();
        Uri uri = incomingUri(getIntent());
        if (uri != null) load(uri);
        else showEmpty();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Uri uri = incomingUri(intent);
        if (uri != null) load(uri);
    }

    @SuppressWarnings("deprecation")
    private static Uri incomingUri(Intent i) {
        if (i == null) return null;
        if (Intent.ACTION_SEND.equals(i.getAction())) {
            Parcelable p = i.getParcelableExtra(Intent.EXTRA_STREAM);
            if (p instanceof Uri) return (Uri) p;
        }
        return i.getData();
    }

    private void buildUi() {
        int pad = UiKit.dp(this, 16);
        LinearLayout col = UiKit.col(this);
        col.setBackgroundColor(UiKit.BG);
        col.setPadding(pad, pad * 2, pad, pad * 2);
        LinearLayout head = UiKit.row(this);
        TextView back = UiKit.text(this, "‹", 30, UiKit.TEXT, false);
        back.setPadding(0, 0, UiKit.dp(this, 12), 0);
        back.setContentDescription("กลับ");
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        head.addView(back);
        head.addView(UiKit.text(this, "StoryMax", 24, UiKit.TEXT, true), UiKit.weight(1));
        TextView imp = UiKit.chip(this, "นำเข้า");
        imp.setContentDescription("นำเข้ารูปหรือวิดีโอ (Photo Picker)");
        imp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pick();
            }
        });
        TextView lab = UiKit.chip(this, "Lab");
        lab.setContentDescription("Quality Lab");
        lab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(StoryActivity.this, LabActivity.class);
                if (source != null) {
                    i.setData(source.uri);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                }
                startActivity(i);
            }
        });
        head.addView(imp, UiKit.lpm(ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(this, 38), 0, 0, UiKit.dp(this, 6), 0));
        head.addView(lab, UiKit.lp(ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(this, 38)));
        col.addView(head);

        empty = UiKit.text(this, "เลือกรูปหรือวิดีโอเพื่อทำ Story 9:16\nไฟล์ต้นฉบับจะไม่ถูกแก้ไข — ส่งออกเป็นไฟล์ใหม่เสมอ", 15, UiKit.MUTED, false);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, UiKit.dp(this, 60), 0, UiKit.dp(this, 20));
        col.addView(empty);

        FrameLayout canvasBox = new FrameLayout(this);
        canvas = new StoryCanvasView(this);
        canvas.setSafeZones(prefs.getBool(Settings.STORY_SAFE_ZONES, true));
        canvas.setListener(new StoryCanvasView.Listener() {
            @Override
            public void onFramingChanged() {
                refreshFramingNote();
            }
        });
        canvasBox.addView(canvas, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
        col.addView(canvasBox, UiKit.lpm(ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 520), 0, UiKit.dp(this, 12), 0, 0));

        controls = UiKit.col(this);
        col.addView(controls);
        status = UiKit.text(this, "", 13, UiKit.MUTED, false);
        controls.addView(status);
        HorizontalScrollView ms = new HorizontalScrollView(this);
        ms.setHorizontalScrollBarEnabled(false);
        modeRow = UiKit.row(this);
        ms.addView(modeRow);
        controls.addView(ms, UiKit.lpm(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, UiKit.dp(this, 8), 0, 0));
        summary = UiKit.text(this, "", 14, UiKit.TEXT, false);
        summary.setPadding(0, UiKit.dp(this, 10), 0, 0);
        controls.addView(summary);
        techToggle = UiKit.text(this, "Technical Info ▾", 13, UiKit.ACCENT, true);
        techToggle.setPadding(0, UiKit.dp(this, 6), 0, UiKit.dp(this, 4));
        techToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean show = tech.getVisibility() != View.VISIBLE;
                tech.setVisibility(show ? View.VISIBLE : View.GONE);
                techToggle.setText(show ? "Technical Info ▴" : "Technical Info ▾");
            }
        });
        controls.addView(techToggle);
        tech = UiKit.mono(this, "", 11);
        tech.setVisibility(View.GONE);
        controls.addView(tech);
        controls.addView(UiKit.section(this, "โปรไฟล์ส่งออก"));
        HorizontalScrollView ps = new HorizontalScrollView(this);
        ps.setHorizontalScrollBarEnabled(false);
        profileRow = UiKit.row(this);
        ps.addView(profileRow);
        controls.addView(ps);
        exportBtn = UiKit.button(this, "Export Story", true);
        exportBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startExport();
            }
        });
        controls.addView(exportBtn, UiKit.lpm(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, UiKit.dp(this, 14), 0, 0));
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setVisibility(View.GONE);
        controls.addView(bar, UiKit.lpm(ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 20), 0, UiKit.dp(this, 8), 0, 0));
        cancelBtn = UiKit.button(this, "ยกเลิกการส่งออก", false);
        cancelBtn.setVisibility(View.GONE);
        cancelBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (running != null) running.cancelled = true;
            }
        });
        controls.addView(cancelBtn, UiKit.lpm(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, UiKit.dp(this, 6), 0, 0));
        resultBox = UiKit.col(this);
        resultBox.setBackground(UiKit.round(UiKit.CARD, UiKit.dp(this, 16)));
        resultBox.setPadding(pad, pad, pad, pad);
        resultText = UiKit.text(this, "", 13, UiKit.TEXT, false);
        resultBox.addView(resultText);
        resultBox.setVisibility(View.GONE);
        controls.addView(resultBox, UiKit.lpm(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, UiKit.dp(this, 12), 0, 0));
        TextView honesty = UiKit.text(this, "Story-optimized export for Galaxy S25 Ultra — ไม่ใช่ “lossless Instagram”: Instagram เข้ารหัสใหม่ทุกครั้ง "
                + "ค่าที่แสดงเป็นการวัดก่อนอัปโหลด ดูผลจริงจาก Story ที่โพสต์ (Quality Lab › เวิร์กชีต A/B)", 11, UiKit.MUTED, false);
        honesty.setPadding(0, UiKit.dp(this, 14), 0, 0);
        controls.addView(honesty);
        controls.setVisibility(View.GONE);
        canvasBox.setVisibility(View.GONE);
        setContentView(UiKit.scroll(this, col));
    }

    private void showEmpty() {
        empty.setVisibility(View.VISIBLE);
        TextView pickBtn = UiKit.button(this, "เลือกรูป/วิดีโอ", true);
        pickBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pick();
            }
        });
        ViewGroup parent = (ViewGroup) empty.getParent();
        if (parent.indexOfChild(pickBtn) < 0) parent.addView(pickBtn, parent.indexOfChild(empty) + 1,
                UiKit.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        pick();
    }

    private void pick() {
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
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null && data.getData() != null) {
            load(data.getData());
        }
    }

    private void load(final Uri uri) {
        status.setText("กำลังอ่านไฟล์…");
        controls.setVisibility(View.VISIBLE);
        resultBox.setVisibility(View.GONE);
        App.get().background().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final MediaInfo m = MediaProbe.probe(StoryActivity.this, uri);
                    final Bitmap preview = StoryEngine.loadPreview(StoryActivity.this, m, 1600);
                    h.post(new Runnable() {
                        @Override
                        public void run() {
                            onLoaded(m, preview);
                        }
                    });
                } catch (final IOException | RuntimeException | OutOfMemoryError e) {
                    h.post(new Runnable() {
                        @Override
                        public void run() {
                            status.setText("เปิดไฟล์นี้ไม่ได้: " + e.getMessage() + "\nลองไฟล์ JPEG/HEIC/PNG หรือ MP4 (H.264/HEVC)");
                            status.setTextColor(UiKit.DANGER);
                        }
                    });
                }
            }
        });
    }

    private void onLoaded(MediaInfo m, Bitmap preview) {
        source = m;
        empty.setVisibility(View.GONE);
        ((View) canvas.getParent()).setVisibility(View.VISIBLE);
        controls.setVisibility(View.VISIBLE);
        status.setTextColor(UiKit.MUTED);
        canvas.setSource(preview, m.orientedW(), m.orientedH());
        canvas.setMode(CropMath.FILL, false);
        summary.setText(m.summary());
        tech.setText(m.technical());
        profile = m.video ? StoryProfiles.byCode(prefs.getString(Settings.STORY_VIDEO_PROFILE, "V1080_SDR"))
                : StoryProfiles.byCode(prefs.getString(Settings.STORY_PHOTO_PROFILE, "P1080_SDR"));
        if (profile == null || profile.video != m.video && !profile.isDirect()) profile = m.video ? StoryProfiles.V1080_SDR : StoryProfiles.P1080_SDR;
        buildModeRow();
        buildProfileRow();
        refreshFramingNote();
        if (m.hdr) {
            status.setText("ต้นฉบับเป็นวิดีโอ HDR: โปรไฟล์มาตรฐานจะแปลงเป็น SDR (tone-map) — ไม่แปะป้ายหลอก ถ้าเครื่องแปลงไม่ได้จะแจ้ง");
        }
    }

    private void buildModeRow() {
        modeRow.removeAllViews();
        String[] labels = source != null && source.video ? new String[]{"Fill", "Fit (ดำ)", "รีเซ็ตกรอบ", "โซน IG"}
                : new String[]{"Fill", "Fit (ดำ)", "Fit (เบลอ)", "รีเซ็ตกรอบ", "โซน IG"};
        for (final String l : labels) {
            TextView c = UiKit.chip(this, l);
            boolean on = (l.equals("Fill") && canvas.mode() == CropMath.FILL)
                    || (l.equals("Fit (ดำ)") && canvas.mode() == CropMath.FIT && !canvas.blur())
                    || (l.equals("Fit (เบลอ)") && canvas.mode() == CropMath.FIT && canvas.blur())
                    || (l.equals("โซน IG") && prefs.getBool(Settings.STORY_SAFE_ZONES, true));
            UiKit.chipState(c, on);
            c.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    switch (l) {
                        case "Fill": canvas.setMode(CropMath.FILL, false); break;
                        case "Fit (ดำ)": canvas.setMode(CropMath.FIT, false); break;
                        case "Fit (เบลอ)": canvas.setMode(CropMath.FIT, true); break;
                        case "รีเซ็ตกรอบ": canvas.reset(); break;
                        default:
                            boolean z = !prefs.getBool(Settings.STORY_SAFE_ZONES, true);
                            prefs.put(Settings.STORY_SAFE_ZONES, z);
                            canvas.setSafeZones(z);
                    }
                    buildModeRow();
                }
            });
            modeRow.addView(c, UiKit.lpm(ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(this, 38), 0, 0, UiKit.dp(this, 6), 0));
        }
    }

    private void buildProfileRow() {
        profileRow.removeAllViews();
        if (source == null) return;
        List<StoryProfiles.Profile> all = source.video ? StoryProfiles.video() : StoryProfiles.photo();
        for (final StoryProfiles.Profile p : all) {
            boolean std = p.status == StoryProfiles.STABLE;
            if (!advanced && !std) continue;
            TextView c = UiKit.chip(this, std ? "Standard " + p.width + "×" + p.height : p.code);
            if (p.status == StoryProfiles.NOT_IMPLEMENTED) {
                UiKit.chipDisabled(c);
            } else {
                UiKit.chipState(c, p == profile);
            }
            c.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (p.status == StoryProfiles.NOT_IMPLEMENTED) {
                        status.setText(p.code + ": NOT IMPLEMENTED — " + p.note);
                        return;
                    }
                    profile = p;
                    prefs.put(source.video ? Settings.STORY_VIDEO_PROFILE : Settings.STORY_PHOTO_PROFILE, p.code);
                    status.setText(p.label + " · " + p.statusLabel() + " · " + p.note);
                    buildProfileRow();
                }
            });
            profileRow.addView(c, UiKit.lpm(ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(this, 38), 0, 0, UiKit.dp(this, 6), 0));
        }
        TextView adv = UiKit.chip(this, advanced ? "ซ่อน Advanced" : "Advanced ▸");
        adv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                advanced = !advanced;
                if (!advanced && profile.status != StoryProfiles.STABLE) profile = source.video ? StoryProfiles.V1080_SDR : StoryProfiles.P1080_SDR;
                buildProfileRow();
            }
        });
        profileRow.addView(adv, UiKit.lp(ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(this, 38)));
    }

    private void refreshFramingNote() {
        if (source == null) return;
        if (canvas.mode() == CropMath.FIT) {
            status.setText("Fit: เห็นทั้งภาพ เติมพื้นหลัง" + (canvas.blur() ? "เบลอ (สร้างจากภาพเดียวกัน)" : "ดำ") + " ให้เต็ม 9:16");
        } else {
            double[] r = canvas.cropRectFull();
            status.setText(String.format(Locale.ROOT, "Fill: ใช้ %.0f×%.0f px จากต้นฉบับ (%.0f%% ของภาพ) · จีบนิ้วซูม ลากจัดตำแหน่ง แตะสองครั้งรีเซ็ต",
                    r[2] - r[0], r[3] - r[1], canvas.keptFraction() * 100));
        }
    }

    private void startExport() {
        if (source == null || running != null) return;
        final ExportJob job = new ExportJob();
        job.source = source;
        job.profile = profile;
        job.mode = canvas.mode();
        job.blurBackground = canvas.blur();
        job.cropOriented = canvas.mode() == CropMath.FIT ? null : canvas.cropRectFull();
        running = job;
        exportBtn.setEnabled(false);
        exportBtn.setAlpha(0.5f);
        bar.setVisibility(View.VISIBLE);
        bar.setProgress(0);
        cancelBtn.setVisibility(View.VISIBLE);
        resultBox.setVisibility(View.GONE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                StoryEngine.export(getApplicationContext(), job, new ExportJob.Progress() {
                    @Override
                    public void onProgress(final float f, final String stage) {
                        h.post(new Runnable() {
                            @Override
                            public void run() {
                                bar.setProgress((int) (f * 1000));
                                status.setText(stage + String.format(Locale.ROOT, " %.0f%%", f * 100));
                            }
                        });
                    }
                });
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        onExportDone(job);
                    }
                });
            }
        }, "nm-export");
        t.start();
    }

    private void onExportDone(final ExportJob job) {
        running = null;
        lastJob = job;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        exportBtn.setEnabled(true);
        exportBtn.setAlpha(1f);
        bar.setVisibility(View.GONE);
        cancelBtn.setVisibility(View.GONE);
        resultBox.setVisibility(View.VISIBLE);
        while (resultBox.getChildCount() > 1) resultBox.removeViewAt(1);
        if (!job.ok()) {
            resultText.setTextColor(UiKit.DANGER);
            resultText.setText("ส่งออกไม่สำเร็จ: " + job.error + "\nต้นฉบับไม่ถูกแตะต้อง · ลองโปรไฟล์อื่นหรือ DIRECT_ORIGINAL");
            status.setText("");
            return;
        }
        resultText.setTextColor(UiKit.TEXT);
        StringBuilder sb = new StringBuilder();
        sb.append("บันทึกแล้ว: ").append(job.outputName).append("\nใน ").append(job.source.video ? "Movies" : "Pictures").append("/NongMay/Story\n\n");
        if (job.actual != null) sb.append("วัดจากไฟล์จริง: ").append(job.actual.summary()).append(" · ").append(Fmt.bytes(job.actual.sizeBytes)).append("\n");
        sb.append(String.format(Locale.ROOT, "ใช้เวลา %.1f วินาที\n\n", job.elapsedMs / 1000.0));
        sb.append(job.notesText());
        resultText.setText(sb.toString());
        status.setText("เสร็จแล้ว — แชร์ได้เลย (แอปไม่โพสต์ให้อัตโนมัติ)");
        final String mime = job.source.video ? "video/mp4" : "image/jpeg";
        LinearLayout row = UiKit.row(this);
        TextView share = UiKit.button(this, "Share…", true);
        share.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(ShareUtil.chooser(job.output, mime, "แชร์ Story"));
            }
        });
        row.addView(share, UiKit.weight(1));
        if (ShareUtil.instagramInstalled(this)) {
            TextView ig = UiKit.button(this, "Open Instagram", false);
            ig.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        startActivity(ShareUtil.instagram(job.output, mime));
                    } catch (ActivityNotFoundException e) {
                        startActivity(ShareUtil.chooser(job.output, mime, "แชร์ Story"));
                    }
                }
            });
            row.addView(ig, UiKit.lpm(0, ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(this, 8), 0, 0, 0));
            ((LinearLayout.LayoutParams) ig.getLayoutParams()).weight = 1;
        }
        resultBox.addView(row, UiKit.lpm(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, UiKit.dp(this, 12), 0, 0));
        TextView open = UiKit.button(this, "เปิดดูไฟล์", false);
        open.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(ShareUtil.view(job.output, mime));
                } catch (ActivityNotFoundException e) {
                    UiKit.toast(StoryActivity.this, "ไม่มีแอปเปิดไฟล์นี้");
                }
            }
        });
        resultBox.addView(open, UiKit.lpm(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, UiKit.dp(this, 8), 0, 0));
        resultText.setTypeface(Typeface.DEFAULT);
    }

    @Override
    protected void onDestroy() {
        if (running != null) running.cancelled = true;
        super.onDestroy();
    }
}
