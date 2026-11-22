package com.nongmay.camera.perf;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.Bundle;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.nongmay.camera.App;
import com.nongmay.camera.camera.MediaStoreUtil;
import com.nongmay.camera.camera.inspect.CapabilityInspector;
import com.nongmay.camera.storymax.ShareUtil;
import com.nongmay.camera.ui.UiKit;
import com.nongmay.camera.util.Json;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/** Capability inspector, on-device self-test and the timing report — all exportable as files. */
public final class DiagnosticsActivity extends Activity {
    private TextView out;
    private TextureView tv;
    private SelfTest running;

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
        head.addView(UiKit.text(this, "Diagnostics", 24, UiKit.TEXT, true));
        col.addView(head);
        col.addView(UiKit.text(this, "รายงานทั้งหมดบันทึกที่ Download/NongMay และไม่มีรูป เสียง ตำแหน่ง หรือข้อมูลบัญชี", 13, UiKit.MUTED, false));
        button(col, "ตรวจความสามารถกล้อง (DEVICE_CAPABILITIES)", true, new Runnable() {
            @Override
            public void run() {
                inspect();
            }
        });
        button(col, "ทดสอบระบบอัตโนมัติ (Self-test ~1–2 นาที)", false, new Runnable() {
            @Override
            public void run() {
                selfTest();
            }
        });
        button(col, "รายงานความเร็ว (PERFORMANCE)", false, new Runnable() {
            @Override
            public void run() {
                perf();
            }
        });
        button(col, "ล้างสถิติความเร็ว", false, new Runnable() {
            @Override
            public void run() {
                PerfLog.clear();
                out.setText("ล้างแล้ว");
            }
        });
        tv = new TextureView(this);
        tv.setVisibility(View.GONE);
        col.addView(tv, UiKit.lpm(UiKit.dp(this, 120), UiKit.dp(this, 160), 0, UiKit.dp(this, 10), 0, 0));
        out = UiKit.mono(this, "", 11);
        out.setPadding(0, UiKit.dp(this, 12), 0, 0);
        col.addView(out);
        setContentView(UiKit.scroll(this, col));
    }

    private void button(LinearLayout col, String text, boolean primary, final Runnable r) {
        TextView b = UiKit.button(this, text, primary);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                r.run();
            }
        });
        col.addView(b, UiKit.lpm(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, UiKit.dp(this, 8), 0, 0));
    }

    private String stamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmm", Locale.ROOT).format(new Date());
    }

    private void inspect() {
        out.setText("กำลังอ่านความสามารถกล้องและ encoder…");
        App.get().background().execute(new Runnable() {
            @Override
            public void run() {
                final Map<String, Object> data = CapabilityInspector.collect(DiagnosticsActivity.this);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        CapabilityInspector.checkSpeech(DiagnosticsActivity.this, new CapabilityInspector.SpeechCallback() {
                            @Override
                            public void onResult(Map<String, Object> speech) {
                                data.put("speech_recognition_os", speech);
                                save(data);
                            }
                        });
                    }
                });
            }
        });
    }

    private void save(Map<String, Object> data) {
        try {
            String s = stamp();
            Uri json = MediaStoreUtil.saveText(this, Json.pretty(data), "DEVICE_CAPABILITIES_" + s + ".json", "application/json");
            Uri md = MediaStoreUtil.saveText(this, CapabilityInspector.markdown(data), "DEVICE_CAPABILITIES_" + s + ".md", "text/markdown");
            out.setText(CapabilityInspector.markdown(data));
            startActivity(ShareUtil.chooser(md, "text/markdown", "DEVICE_CAPABILITIES"));
            if (json == null) out.append("\n(บันทึก JSON ไม่สำเร็จ)");
        } catch (IOException e) {
            out.setText("บันทึกรายงานไม่ได้: " + e.getMessage());
        }
    }

    private static final int REQ_MIC = 41;
    private boolean micAsked;

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC) selfTest(); // runs either way; without the mic the voice/audio checks SKIP
    }

    private void selfTest() {
        if (running != null) return;
        if (!micAsked && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // first self-test on the S25 Ultra skipped the microphone loop and recorded video without sound
            micAsked = true;
            out.setText("Self-test ขอใช้ไมค์เพื่อทดสอบ Voice Shutter และเสียงในวิดีโอ (ไม่มีการบันทึกเสียงเก็บไว้) — ปฏิเสธได้ จะข้ามข้อนั้น");
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            out.setText("ต้องอนุญาตกล้องก่อน (เปิดหน้ากล้องหลักหนึ่งครั้ง)");
            return;
        }
        out.setText("Self-test: หันกล้องหลังไปที่ที่สว่างและไกลกว่า 2 เมตร (เช่น นอกหน้าต่าง) ตลอดการทดสอบ — ที่มืดหรือใกล้ กล้องจะไม่ยอมใช้เลนส์ซูม 3×/5× จริง\nไฟล์ทดสอบจะถูกลบเมื่อจบ เหลือแค่รายงาน\n");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        tv.setVisibility(View.VISIBLE);
        if (tv.isAvailable()) launch(tv.getSurfaceTexture());
        else tv.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                launch(st);
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                if (running != null) running.cancel();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture st) {
            }
        });
    }

    private void launch(SurfaceTexture st) {
        running = new SelfTest(this, st, new SelfTest.Sink() {
            @Override
            public void line(String s) {
                out.append(s + "\n");
            }

            @Override
            public void done(String report) {
                running = null;
                tv.setVisibility(View.GONE);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                try {
                    Uri u = MediaStoreUtil.saveText(DiagnosticsActivity.this, report + "\n" + PerfLog.reportMarkdown(),
                            "SELFTEST_" + stamp() + ".md", "text/markdown");
                    out.append("\nบันทึก Download/NongMay/SELFTEST_*.md แล้ว\n");
                    startActivity(ShareUtil.chooser(u, "text/markdown", "รายงาน Self-test"));
                } catch (IOException e) {
                    out.append("\nบันทึกรายงานไม่ได้: " + e.getMessage());
                }
            }
        });
        running.start();
    }

    private void perf() {
        String md = PerfLog.reportMarkdown();
        out.setText(md);
        try {
            String s = stamp();
            Uri u = MediaStoreUtil.saveText(this, md, "PERFORMANCE_" + s + ".md", "text/markdown");
            MediaStoreUtil.saveText(this, PerfLog.csv(), "PERFORMANCE_" + s + ".csv", "text/csv");
            startActivity(ShareUtil.chooser(u, "text/markdown", "PERFORMANCE"));
        } catch (IOException e) {
            out.append("\nบันทึกไม่ได้: " + e.getMessage());
        }
    }

    @Override
    protected void onPause() {
        if (running != null && isFinishing()) running.cancel();
        super.onPause();
    }
}
