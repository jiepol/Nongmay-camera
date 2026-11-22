package com.nongmay.camera.settings;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import com.nongmay.camera.App;
import com.nongmay.camera.BuildInfo;
import com.nongmay.camera.perf.DiagnosticsActivity;
import com.nongmay.camera.ui.UiKit;
import com.nongmay.camera.voice.VoiceActivity;

/** Settings: discoverable but out of the camera's way. */
public final class SettingsActivity extends Activity {
    private Settings s;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        s = App.get().settings();
        int pad = UiKit.dp(this, 20);
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
        head.addView(UiKit.text(this, "ตั้งค่า", 24, UiKit.TEXT, true));
        col.addView(head);

        col.addView(UiKit.section(this, "เปิดแอป"));
        choice(col, "เปิดมาที่", Settings.START_MODE, new String[]{"last", "auto"}, new String[]{"โหมด/เลนส์ล่าสุด", "AUTO เสมอ"}, "last");
        toggle(col, Settings.LEFT_HANDED, "ถนัดซ้าย", "สลับปุ่มแกลเลอรี/สลับกล้อง (มีผลเมื่อเปิดหน้ากล้องใหม่)", false);

        col.addView(UiKit.section(this, "ชัตเตอร์"));
        toggle(col, Settings.TOUCH_DOWN_SHUTTER, "ถ่ายทันทีที่แตะ", "ถ่ายตอนนิ้วแตะ (เร็วสุด) แทนตอนยกนิ้ว", true);
        toggle(col, Settings.VOLUME_SHUTTER, "ปุ่มเสียงเป็นชัตเตอร์", null, true);
        toggle(col, Settings.SHUTTER_SOUND, "เสียงชัตเตอร์", "บางประเทศ/บางรุ่นระบบบังคับให้มีเสียงเสมอ", true);
        toggle(col, Settings.HAPTICS, "สั่นตอบสนอง", null, true);
        toggle(col, Settings.ZSL, "Zero-shutter-lag (ถ้ากล้องรองรับ)", "ขอ ZSL จาก HAL สำหรับภาพเดี่ยว AUTO · ปิดเองเมื่อใช้แฟลช/ค่า manual", true);
        toggle(col, Settings.FOCUS_BEFORE_CAPTURE, "รอโฟกัสก่อนถ่าย", "ชัดขึ้นกับวัตถุใกล้ แต่ช้าลงสูงสุด ~0.9 วินาที", false);
        choice(col, "คุณภาพ JPEG", Settings.JPEG_QUALITY, new String[]{"90", "95", "100"}, new String[]{"90", "95", "100"}, "95");
        toggle(col, Settings.MIRROR_SELFIE, "กลับด้านภาพเซลฟี่ตอนบันทึก", "เหมือนกระจก (ต้องเข้ารหัส JPEG ใหม่)", false);

        col.addView(UiKit.section(this, "ภาพซ้อนบนพรีวิว (PRO / VIDEO)"));
        toggle(col, Settings.HISTOGRAM, "ฮิสโตแกรม + เตือนไฮไลต์ล้น/เงาจม", "คำนวณจากพรีวิว YUV ไม่ใช่ไฟล์จริง", true);
        toggle(col, Settings.ZEBRA, "Zebra", "แถบลายบนส่วนที่สว่างเกินระดับที่ตั้ง", false);
        choice(col, "ระดับ Zebra", Settings.ZEBRA_LEVEL, new String[]{"90", "95", "100"}, new String[]{"90%", "95%", "100%"}, "95");
        toggle(col, Settings.PEAKING, "Focus peaking", "ไฮไลต์ขอบที่คมที่สุด (Sobel บนพรีวิว)", false);

        col.addView(UiKit.section(this, "Story"));
        toggle(col, Settings.STORY_AUTO_OPEN, "เปิด StoryMax หลังถ่ายในโหมด STORY", null, true);
        toggle(col, Settings.STORY_SAFE_ZONES, "แสดงโซน UI ของ Instagram (ประมาณการ)", null, true);

        col.addView(UiKit.section(this, "เสียง (Voice Shutter)"));
        link(col, "จัดการคำสั่งเสียง น้องเม / คำสั่งของคุณ", VoiceActivity.class);

        col.addView(UiKit.section(this, "ความเป็นส่วนตัว"));
        col.addView(UiKit.text(this, "• แอปไม่มีสิทธิ์อินเทอร์เน็ต (ไม่มี telemetry ไม่มีบัญชี)\n"
                + "• ไมค์ใช้เฉพาะเมื่อเปิด Voice Shutter หรืออัดวิดีโอมีเสียง และเฉพาะตอนหน้าจอกล้องเปิดอยู่\n"
                + "• ไม่ขอสิทธิ์อ่านคลังรูปทั้งหมด: นำเข้าผ่าน Photo Picker, บันทึกผ่าน MediaStore\n"
                + "• ไฟล์ Story ที่ส่งออกไม่มี EXIF/GPS · ไม่มีการแท็กตำแหน่งในรูปที่ถ่าย\n"
                + "• ไฟล์ต้นฉบับไม่ถูกแก้ไขเลย", 13, UiKit.MUTED, false));

        col.addView(UiKit.section(this, "วินิจฉัย"));
        link(col, "Diagnostics: ความสามารถกล้อง · Self-test · ความเร็ว", DiagnosticsActivity.class);

        col.addView(UiKit.section(this, "เกี่ยวกับ"));
        col.addView(UiKit.text(this, "NongMay Camera " + BuildInfo.VERSION + " (" + BuildInfo.APP_ID + ")\n"
                + "รุ่นนี้สร้างใหม่ทั้งหมดเพราะไม่มีซอร์สของ v1.0 จึงติดตั้งแยกจาก v1.0 (คนละแพ็กเกจ/คนละกุญแจเซ็น) "
                + "และไม่ย้ายลายเสียงของ v1.0 มา", 12, UiKit.MUTED, false));
        setContentView(UiKit.scroll(this, col));
    }

    private void toggle(LinearLayout col, final String key, String title, String desc, boolean def) {
        Switch sw = UiKit.switchRow(col, title, desc, s.getBool(key, def));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                s.put(key, on);
            }
        });
    }

    private void choice(LinearLayout col, String title, final String key, final String[] values, String[] labels, String def) {
        col.addView(UiKit.text(this, title, 15, UiKit.TEXT, false));
        LinearLayout row = UiKit.row(this);
        boolean intKey = key.equals(Settings.JPEG_QUALITY) || key.equals(Settings.ZEBRA_LEVEL);
        String cur = intKey ? String.valueOf(s.getInt(key, Integer.parseInt(def))) : s.getString(key, def);
        final TextView[] chips = new TextView[values.length];
        for (int i = 0; i < values.length; i++) {
            final int idx = i;
            chips[i] = UiKit.chip(this, labels[i]);
            UiKit.chipState(chips[i], values[i].equals(cur));
            chips[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (key.equals(Settings.JPEG_QUALITY) || key.equals(Settings.ZEBRA_LEVEL)) s.put(key, Integer.parseInt(values[idx]));
                    else s.put(key, values[idx]);
                    for (int k = 0; k < chips.length; k++) UiKit.chipState(chips[k], k == idx);
                }
            });
            row.addView(chips[i], UiKit.lpm(ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(this, 38), 0, UiKit.dp(this, 6), UiKit.dp(this, 6), UiKit.dp(this, 8)));
        }
        col.addView(row);
    }

    private void link(LinearLayout col, String text, final Class<?> target) {
        TextView b = UiKit.button(this, text, false);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, target));
            }
        });
        col.addView(b, UiKit.lpm(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, UiKit.dp(this, 6), 0, 0));
    }
}
