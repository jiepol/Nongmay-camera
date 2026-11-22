package com.nongmay.camera.voice;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import com.nongmay.camera.App;
import com.nongmay.camera.settings.Settings;
import com.nongmay.camera.ui.Icons;
import com.nongmay.camera.ui.UiKit;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Voice Shutter setup: choose/record trigger phrases (default "น้องเม"), test them live with the
 * real detector, set sensitivity and countdown, delete voice data. Everything stays on the phone.
 */
public final class VoiceActivity extends Activity implements AudioKwsEngine.Listener {
    private static final int REQ_MIC = 21;
    private static final int SAMPLES = 3;
    private static final String[] SENS = {"เข้มงวด", "ปกติ", "ไว"};

    private Settings prefs;
    private VoiceProfileStore store;
    private AudioKwsEngine engine;
    private VoiceTemplate draft;
    private VoiceTemplate testing;
    private KwsCore.Event lastTestEvent;
    private TextView notThisBtn;
    private TextView isThisBtn;
    private TextView clearAntiBtn;
    private int stage; // 0 idle, 1 recording samples, 2 live test
    private String pendingText, pendingId;

    private LinearLayout listBox, enrollCard, sensRow;
    private TextView enrollTitle, enrollDesc, dots, enrollStatus, testResult, warn;
    private View meter;
    private TextView saveBtn, redoBtn, cancelBtn;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = App.get().settings();
        store = new VoiceProfileStore(this);
        engine = new AudioKwsEngine(this);
        buildUi();
        refreshList();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (stage != 0) startEngine();
    }

    @Override
    protected void onPause() {
        engine.stop();
        super.onPause();
    }

    private void buildUi() {
        int pad = UiKit.dp(this, 20);
        LinearLayout col = UiKit.col(this);
        col.setPadding(pad, pad * 2, pad, pad * 2);
        col.setBackgroundColor(UiKit.BG);

        LinearLayout head = UiKit.row(this);
        TextView back = UiKit.text(this, "‹", 30, UiKit.TEXT, false);
        back.setPadding(0, 0, UiKit.dp(this, 14), 0);
        back.setContentDescription("กลับ");
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        head.addView(back);
        head.addView(UiKit.text(this, "Voice Shutter", 24, UiKit.TEXT, true));
        col.addView(head);

        TextView privacy = UiKit.text(this, "ทำงานในเครื่องทั้งหมด · แอปไม่มีสิทธิ์อินเทอร์เน็ต · ไม่เก็บไฟล์เสียง "
                + "(เก็บเฉพาะลายเสียง MFCC ของตัวอย่างที่สอน) · ไมค์ทำงานเฉพาะตอนเปิดหน้ากล้องอยู่", 13, UiKit.MUTED, false);
        privacy.setPadding(0, UiKit.dp(this, 10), 0, UiKit.dp(this, 6));
        col.addView(privacy);

        Switch enabled = UiKit.switchRow(col, "เปิดใช้ Voice Shutter", "พูดคำสั่งแล้วกล้องถ่ายภาพนิ่ง (AUTO / PRO / Story รูป)", prefs.voiceEnabled());
        enabled.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                if (on && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
                }
                prefs.put(Settings.VOICE_ENABLED, on);
            }
        });

        col.addView(UiKit.section(this, "คำสั่งเสียง"));
        listBox = UiKit.col(this);
        col.addView(listBox);
        final EditText input = new EditText(this);
        input.setHint("คำสั่งใหม่ เช่น โฮ่ง ๆ, ชาลี, ถ่ายเลย, cheese");
        input.setTextColor(UiKit.TEXT);
        input.setHintTextColor(UiKit.MUTED);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setSingleLine(true);
        col.addView(input, UiKit.lpm(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, UiKit.dp(this, 8), 0, 0));
        TextView add = UiKit.button(this, "+ สอนคำสั่งใหม่", false);
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String t = ThaiText.display(input.getText().toString());
                if (ThaiText.key(t).isEmpty()) {
                    UiKit.toast(VoiceActivity.this, "พิมพ์คำสั่งก่อน");
                    return;
                }
                for (VoiceTemplate x : store.list()) {
                    if (ThaiText.sameKey(x.text, t)) {
                        UiKit.toast(VoiceActivity.this, "มีคำสั่งนี้แล้ว — กด “สอนใหม่” ที่รายการด้านบนแทน");
                        return;
                    }
                }
                beginEnroll(t, ThaiText.isDefault(t) ? VoiceProfileStore.DEFAULT_ID : VoiceProfileStore.newId());
                input.setText("");
            }
        });
        col.addView(add, UiKit.lpm(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, UiKit.dp(this, 8), 0, 0));
        TextView restore = UiKit.button(this, "คืนค่าเป็น “" + ThaiText.DEFAULT_PHRASE + "”", false);
        restore.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                VoiceTemplate d = store.get(VoiceProfileStore.DEFAULT_ID);
                if (d != null && d.isReady()) {
                    prefs.put(Settings.VOICE_PROFILE, VoiceProfileStore.DEFAULT_ID);
                    UiKit.toast(VoiceActivity.this, "ใช้ “" + ThaiText.DEFAULT_PHRASE + "” แล้ว");
                    refreshList();
                } else {
                    beginEnroll(ThaiText.DEFAULT_PHRASE, VoiceProfileStore.DEFAULT_ID);
                }
            }
        });
        col.addView(restore, UiKit.lpm(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, UiKit.dp(this, 8), 0, 0));

        enrollCard = UiKit.col(this);
        enrollCard.setPadding(pad, pad, pad, pad);
        enrollCard.setBackground(UiKit.round(UiKit.CARD, UiKit.dp(this, 18)));
        enrollTitle = UiKit.text(this, "", 22, UiKit.TEXT, true);
        enrollDesc = UiKit.text(this, "", 14, UiKit.MUTED, false);
        dots = UiKit.text(this, "", 34, UiKit.VOICE, true);
        dots.setGravity(Gravity.CENTER);
        enrollStatus = UiKit.text(this, "", 16, UiKit.OK, true);
        enrollStatus.setGravity(Gravity.CENTER);
        meter = new View(this);
        meter.setBackground(UiKit.round(UiKit.VOICE, UiKit.dp(this, 3)));
        meter.setPivotX(0);
        testResult = UiKit.text(this, "", 16, UiKit.OK, false);
        testResult.setGravity(Gravity.CENTER);
        warn = UiKit.text(this, "", 13, UiKit.ACCENT, false);
        enrollCard.addView(enrollTitle);
        enrollCard.addView(enrollDesc);
        enrollCard.addView(dots, UiKit.lpm(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, UiKit.dp(this, 12), 0, 0));
        enrollCard.addView(enrollStatus);
        enrollCard.addView(meter, UiKit.lpm(ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 6), 0, UiKit.dp(this, 10), 0, UiKit.dp(this, 10)));
        enrollCard.addView(testResult);
        notThisBtn = UiKit.button(this, "อันนี้ไม่ใช่คำสั่ง — จำไว้ ไม่ให้ถ่าย", false);
        notThisBtn.setVisibility(View.GONE);
        notThisBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                markNotThis();
            }
        });
        enrollCard.addView(notThisBtn, UiKit.lpm(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, UiKit.dp(this, 6), 0, UiKit.dp(this, 6)));
        isThisBtn = UiKit.button(this, "อันนี้ใช่คำสั่ง — เพิ่มเป็นตัวอย่าง", true);
        isThisBtn.setVisibility(View.GONE);
        isThisBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                markIsThis();
            }
        });
        enrollCard.addView(isThisBtn, UiKit.lpm(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, UiKit.dp(this, 6), 0, UiKit.dp(this, 6)));
        clearAntiBtn = UiKit.button(this, "ล้างเสียงที่ห้ามถ่าย", false);
        clearAntiBtn.setVisibility(View.GONE);
        clearAntiBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                VoiceTemplate t = draft != null ? draft : testing;
                if (t == null) return;
                t.clearAnti();
                if (t == testing) {
                    try {
                        store.save(t);
                    } catch (IOException ignored) {
                    }
                }
                refreshClearAnti();
                testResult.setTextColor(UiKit.OK);
                testResult.setText("ล้างเสียงที่ห้ามถ่ายแล้ว");
            }
        });
        enrollCard.addView(clearAntiBtn, UiKit.lpm(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, UiKit.dp(this, 6), 0, UiKit.dp(this, 6)));
        enrollCard.addView(warn);
        enrollCard.addView(UiKit.text(this, "ความไวในการฟัง", 14, UiKit.TEXT, true));
        sensRow = UiKit.row(this);
        enrollCard.addView(sensRow, UiKit.lpm(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, UiKit.dp(this, 6), 0, 0));
        enrollCard.addView(UiKit.text(this, "พูดแล้วไม่ค่อยถ่าย → “ไว” · ถ่ายเองบ่อยเกินไป → “เข้มงวด”", 12, UiKit.MUTED, false));
        saveBtn = UiKit.button(this, "บันทึกและเริ่มใช้", true);
        redoBtn = UiKit.button(this, "อัดใหม่ทั้งหมด", false);
        cancelBtn = UiKit.button(this, "ปิดหน้าทดสอบ", false);
        enrollCard.addView(saveBtn, UiKit.lpm(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, UiKit.dp(this, 14), 0, 0));
        enrollCard.addView(redoBtn, UiKit.lpm(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, UiKit.dp(this, 8), 0, 0));
        enrollCard.addView(cancelBtn, UiKit.lpm(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, UiKit.dp(this, 8), 0, 0));
        enrollCard.setVisibility(View.GONE);
        col.addView(enrollCard, UiKit.lpm(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, UiKit.dp(this, 16), 0, 0));
        saveBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveDraft();
            }
        });
        redoBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (draft != null) beginEnroll(draft.text, draft.id);
                else if (testing != null) beginEnroll(testing.text, testing.id);
            }
        });
        cancelBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                endSession();
            }
        });

        col.addView(UiKit.section(this, "หลังได้ยินคำสั่ง"));
        LinearLayout cd = UiKit.row(this);
        final int[] cds = {0, 2, 3, 5};
        final String[] cdl = {"ถ่ายทันที", "นับ 2", "นับ 3", "นับ 5"};
        final TextView[] cdv = new TextView[cds.length];
        for (int i = 0; i < cds.length; i++) {
            final int idx = i;
            cdv[i] = UiKit.chip(this, cdl[i]);
            UiKit.chipState(cdv[i], prefs.voiceCountdown() == cds[i]);
            cdv[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    prefs.put(Settings.VOICE_COUNTDOWN, cds[idx]);
                    for (int k = 0; k < cdv.length; k++) UiKit.chipState(cdv[k], k == idx);
                }
            });
            cd.addView(cdv[i], UiKit.lpm(0, UiKit.dp(this, 40), 0, 0, UiKit.dp(this, 6), 0));
            ((LinearLayout.LayoutParams) cdv[i].getLayoutParams()).weight = 1;
        }
        col.addView(cd);
        col.addView(UiKit.text(this, "ถ่ายรูปหมู่: เลือกนับถอยหลังเพื่อมีเวลาโพสหลังพูด", 12, UiKit.MUTED, false));

        col.addView(UiKit.section(this, "ดีบัก / ความเป็นส่วนตัว"));
        Switch dbg = UiKit.switchRow(col, "แสดงคะแนนบนหน้ากล้อง", "ช่วยปรับความไว (ไม่มีการบันทึกเสียง)", prefs.getBool("voice_debug", false));
        dbg.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                prefs.put("voice_debug", on);
            }
        });
        TextView wipe = UiKit.button(this, "ลบข้อมูลเสียงทั้งหมด", false);
        wipe.setTextColor(UiKit.DANGER);
        wipe.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(VoiceActivity.this)
                        .setMessage("ลบลายเสียงทุกคำสั่งออกจากเครื่องทันที? (ต้องสอนใหม่ก่อนใช้)")
                        .setPositiveButton("ลบ", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                endSession();
                                int n = store.deleteAll();
                                prefs.put(Settings.VOICE_ENABLED, false);
                                UiKit.toast(VoiceActivity.this, "ลบแล้ว " + n + " ไฟล์");
                                refreshList();
                            }
                        })
                        .setNegativeButton("ยกเลิก", null).show();
            }
        });
        col.addView(wipe, UiKit.lpm(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, UiKit.dp(this, 8), 0, 0));
        TextView lim = UiKit.text(this, "ข้อจำกัดที่ควรรู้: ระบบนี้จับคู่ “เสียงของคุณ” กับตัวอย่างที่สอน (speaker-dependent) "
                + "ไม่ใช่ระบบแปลงเสียงพูดภาษาไทยเป็นข้อความ คนอื่นพูดอาจไม่ติด · ประโยคยาว/คุยกันจะไม่ถ่าย (กันถ่ายเอง) · "
                + "เสียงเห่าจริงของสุนัขไม่นับเป็น “โฮ่ง ๆ” · คำสั้นพยางค์เดียวแยกจากเสียงอื่นได้ยากกว่า "
                + "· ไม่มีการส่งเสียงไปประมวลผลบนคลาวด์ในกรณีใดเลย", 12, UiKit.MUTED, false);
        lim.setPadding(0, UiKit.dp(this, 16), 0, 0);
        col.addView(lim);
        setContentView(UiKit.scroll(this, col));
    }

    private void refreshList() {
        listBox.removeAllViews();
        List<VoiceTemplate> all = store.list();
        String active = prefs.getString(Settings.VOICE_PROFILE, VoiceProfileStore.DEFAULT_ID);
        if (all.isEmpty()) {
            listBox.addView(UiKit.text(this, "ยังไม่ได้สอนเสียง — เริ่มจาก “" + ThaiText.DEFAULT_PHRASE + "” ด้วยปุ่มคืนค่าด้านล่าง",
                    14, UiKit.MUTED, false));
        }
        for (final VoiceTemplate t : all) {
            LinearLayout row = UiKit.row(this);
            row.setPadding(0, UiKit.dp(this, 6), 0, UiKit.dp(this, 6));
            boolean on = t.id.equals(active);
            TextView name = UiKit.text(this, (on ? "● " : "○ ") + ThaiText.display(t.text)
                    + (t.id.equals(VoiceProfileStore.DEFAULT_ID) ? " (ค่าเริ่มต้น)" : ""), 16, on ? UiKit.VOICE : UiKit.TEXT, on);
            TextView meta = UiKit.text(this, t.size() + " ตัวอย่าง · " + SENS[Math.max(0, Math.min(2, t.sensitivity))], 12, UiKit.MUTED, false);
            LinearLayout texts = UiKit.col(this);
            texts.addView(name);
            texts.addView(meta);
            row.addView(texts, UiKit.weight(1));
            TextView use = UiKit.chip(this, on ? "ใช้อยู่" : "ใช้");
            UiKit.chipState(use, on);
            use.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    prefs.put(Settings.VOICE_PROFILE, t.id);
                    refreshList();
                }
            });
            TextView test = UiKit.chip(this, "ทดสอบ");
            test.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    beginTest(t);
                }
            });
            TextView del = UiKit.chip(this, "ลบ");
            del.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    endSession();
                    store.delete(t.id);
                    if (t.id.equals(prefs.getString(Settings.VOICE_PROFILE, ""))) prefs.put(Settings.VOICE_PROFILE, VoiceProfileStore.DEFAULT_ID);
                    refreshList();
                }
            });
            int m = UiKit.dp(this, 4);
            row.addView(use, UiKit.lpm(ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(this, 36), m, 0, 0, 0));
            row.addView(test, UiKit.lpm(ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(this, 36), m, 0, 0, 0));
            row.addView(del, UiKit.lpm(ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(this, 36), m, 0, 0, 0));
            listBox.addView(row);
        }
    }

    private void beginEnroll(String text, String id) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingText = text;
            pendingId = id;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        VoiceTemplate old = store.get(id);
        draft = new VoiceTemplate(id, ThaiText.display(text));
        if (old != null) draft.sensitivity = old.sensitivity;
        testing = null;
        stage = 1;
        enrollCard.setVisibility(View.VISIBLE);
        enrollTitle.setText("สอนเสียง “" + draft.text + "”");
        enrollDesc.setText("พูดคำว่า “" + draft.text + "” ให้ฟัง " + SAMPLES + " ครั้ง ด้วยน้ำเสียงปกติ เว้นช่วงสั้น ๆ ระหว่างครั้ง "
                + "และอยู่ห่างประมาณที่จะใช้จริง แอปจะจำลายเสียงไว้ในเครื่องนี้เท่านั้น");
        testResult.setText("");
        lastTestEvent = null;
        if (notThisBtn != null) notThisBtn.setVisibility(View.GONE);
        if (isThisBtn != null) isThisBtn.setVisibility(View.GONE);
        if (clearAntiBtn != null) refreshClearAnti();
        warn.setText("");
        refreshEnroll("พร้อมฟังแล้ว พูดได้เลย", UiKit.OK);
        startEngine();
    }

    private void beginTest(VoiceTemplate t) {
        if (!t.isReady()) {
            beginEnroll(t.text, t.id);
            return;
        }
        draft = null;
        testing = t;
        stage = 2;
        enrollCard.setVisibility(View.VISIBLE);
        enrollTitle.setText("ทดสอบ “" + ThaiText.display(t.text) + "”");
        enrollDesc.setText("พูดเพื่อดูว่าระบบจะถ่ายหรือไม่ ลองทั้งคำนี้และคำอื่น ๆ (ไม่มีการถ่ายรูปจริงในหน้านี้)");
        testResult.setText("");
        lastTestEvent = null;
        if (notThisBtn != null) notThisBtn.setVisibility(View.GONE);
        if (isThisBtn != null) isThisBtn.setVisibility(View.GONE);
        if (clearAntiBtn != null) refreshClearAnti();
        warn.setText("");
        refreshEnroll("กำลังฟัง…", UiKit.OK);
        startEngine();
    }

    private void startEngine() {
        if (stage == 1) engine.start(null, true, this);
        else if (stage == 2) engine.start(draft != null ? draft : testing, false, this);
    }

    private void endSession() {
        engine.stop();
        stage = 0;
        draft = null;
        testing = null;
        enrollCard.setVisibility(View.GONE);
    }

    private void refreshEnroll(String status, int color) {
        VoiceTemplate t = draft != null ? draft : testing;
        int n = t == null ? 0 : t.size();
        StringBuilder d = new StringBuilder();
        for (int i = 0; i < SAMPLES; i++) d.append(i < n ? "● " : "○ ");
        dots.setText(d.toString().trim());
        dots.setVisibility(stage == 1 || draft != null ? View.VISIBLE : View.GONE);
        enrollStatus.setText(status);
        enrollStatus.setTextColor(color);
        saveBtn.setVisibility(draft != null && stage == 2 ? View.VISIBLE : View.GONE);
        redoBtn.setVisibility(View.VISIBLE);
        sensRow.removeAllViews();
        final VoiceTemplate target = draft != null ? draft : testing;
        for (int i = 0; i < SENS.length; i++) {
            final int s = i;
            TextView chip = UiKit.chip(this, SENS[i]);
            UiKit.chipState(chip, target != null && target.sensitivity == i);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (target == null) return;
                    target.sensitivity = s;
                    if (target == testing) {
                        try {
                            store.save(testing);
                        } catch (IOException e) {
                            UiKit.toast(VoiceActivity.this, "บันทึกไม่ได้: " + e.getMessage());
                        }
                        refreshList();
                    }
                    engine.setTemplate(target);
                    refreshEnroll(enrollStatus.getText().toString(), enrollStatus.getCurrentTextColor());
                }
            });
            sensRow.addView(chip, UiKit.lpm(0, UiKit.dp(this, 40), 0, 0, UiKit.dp(this, 6), 0));
            ((LinearLayout.LayoutParams) chip.getLayoutParams()).weight = 1;
        }
    }

    private void saveDraft() {
        if (draft == null || !draft.isReady()) return;
        try {
            store.save(draft);
        } catch (IOException e) {
            UiKit.toast(this, "บันทึกไม่สำเร็จ: " + e.getMessage());
            return;
        }
        prefs.put(Settings.VOICE_PROFILE, draft.id);
        prefs.put(Settings.VOICE_ENABLED, true);
        UiKit.toast(this, "บันทึก “" + draft.text + "” แล้ว กลับไปหน้ากล้องแล้วพูดได้เลย");
        endSession();
        refreshList();
    }

    // ---- AudioKwsEngine.Listener ---------------------------------------------------------

    @Override
    public void onSegment(KwsCore.Event e) {
        if (stage == 1 && draft != null) {
            if ("too_long".equals(e.reject)) {
                refreshEnroll("ยาวเกินไป พูดแค่คำสั่งคำเดียวแล้วหยุด", UiKit.DANGER);
                return;
            }
            if (e.features == null || "too_short".equals(e.reject) || e.durMs < 220) {
                refreshEnroll("สั้นเกินไป ลองพูดให้ชัดขึ้น", UiKit.DANGER);
                return;
            }
            if ("too_quiet".equals(e.reject)) {
                refreshEnroll("เบาเกินไปเมื่อเทียบกับเสียงรอบข้าง", UiKit.DANGER);
                return;
            }
            if (e.durMs > 2200) {
                refreshEnroll("ยาวเกินไป (" + e.durMs + " ms)", UiKit.DANGER);
                return;
            }
            draft.add(e);
            if (draft.size() < SAMPLES) {
                refreshEnroll("ได้ครั้งที่ " + draft.size() + " แล้ว (" + e.durMs + " ms) พูดอีกครั้ง", UiKit.OK);
                return;
            }
            draft.calibrate(engine.core().config());
            StringBuilder w = new StringBuilder();
            if (!draft.consistent()) w.append("⚠ ตัวอย่างทั้ง 3 ต่างกันมาก ลอง “อัดใหม่ทั้งหมด” ด้วยน้ำเสียงเดิม\n");
            if (ThaiText.likelyTooShort(draft.text, draft.avgDurMs())) {
                w.append("⚠ คำสั่งสั้นมาก (≈").append(Math.round(draft.avgDurMs())).append(" ms) อาจถ่ายเองบ่อยหรือพลาดง่าย ลองคำที่ยาวขึ้น\n");
            }
            warn.setText(w.toString().trim());
            stage = 2;
            engine.core().setEnrollMode(false);
            engine.setTemplate(draft);
            refreshEnroll("เรียบร้อย! ลองพูด “" + draft.text + "” เพื่อทดสอบ แล้วกดบันทึก", UiKit.OK);
            return;
        }
        if (stage == 2) {
            if (e.mel != null) {
                lastTestEvent = e;
                boolean fired = e.type == KwsCore.Event.TRIGGER;
                boolean plausible = !fired && e.durMs >= 220 && !"too_long".equals(e.reject)
                        && !"too_quiet".equals(e.reject) && !"too_short".equals(e.reject);
                notThisBtn.setVisibility(fired ? View.VISIBLE : View.GONE);
                isThisBtn.setVisibility(plausible ? View.VISIBLE : View.GONE);
            }
            if (e.type == KwsCore.Event.TRIGGER) {
                testResult.setTextColor(UiKit.OK);
                testResult.setText(String.format(Locale.ROOT, "✓ ได้ยินคำสั่ง (คะแนน %.2f จากเกณฑ์ %.2f · %d ms)", e.score, e.threshold, e.durMs));
            } else {
                testResult.setTextColor(UiKit.DANGER);
                String score = Double.isNaN(e.score) ? "" : String.format(Locale.ROOT, " (คะแนน %.2f / เกณฑ์ %.2f)", e.score, e.threshold);
                testResult.setText("✗ ไม่ถ่าย: " + KwsCore.rejectLabel(e.reject) + score);
            }
        }
    }

    @Override
    public void onTrigger(KwsCore.Event e, long speechEndElapsedNs, long decisionElapsedNs) {
        // onSegment already shows the result for triggers in this screen.
    }

    /** Shown when the phrase has “not this” sounds, e.g. to undo a real “น้องเม” marked by mistake. */
    private void refreshClearAnti() {
        VoiceTemplate t = draft != null ? draft : testing;
        int n = t == null ? 0 : t.antiCount();
        clearAntiBtn.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
        clearAntiBtn.setText("ล้างเสียงที่ห้ามถ่าย (" + n + ")");
    }

    /** Test area: the matcher missed the owner's real phrase (said faster, farther, louder…) — learn it as a take. */
    private void markIsThis() {
        VoiceTemplate t = draft != null ? draft : testing;
        KwsCore.Event e = lastTestEvent;
        if (t == null || e == null) return;
        t.addExtraTake(e.mel, e.noise, e.durMs);
        t.calibrate(engine.core().config());
        lastTestEvent = null;
        isThisBtn.setVisibility(View.GONE);
        notThisBtn.setVisibility(View.GONE);
        if (t == testing) {
            try {
                store.save(t);
            } catch (IOException ignored) {
            }
        }
        testResult.setTextColor(UiKit.OK);
        testResult.setText("เพิ่มเป็นตัวอย่างแล้ว (" + t.size() + "/" + VoiceTemplate.MAX_TAKES + " ตัวอย่าง) — ลองพูดอีกครั้ง");
    }

    /** Test area: the last sound heard was not the phrase (e.g. a loud “เฮ้ย”) — never trigger on it. */
    private void markNotThis() {
        VoiceTemplate t = draft != null ? draft : testing;
        KwsCore.Event e = lastTestEvent;
        if (t == null || e == null) return;
        t.addAnti(e.mel, e.noise);
        lastTestEvent = null;
        notThisBtn.setVisibility(View.GONE);
        if (t == testing) {
            try {
                store.save(t);
            } catch (IOException ignored) {
            }
        }
        testResult.setTextColor(UiKit.OK);
        testResult.setText("จำเสียงนี้แล้ว จะไม่ถ่ายเพราะเสียงนี้อีก (เสียงที่ห้ามถ่าย " + t.antiCount() + "/" + VoiceTemplate.MAX_ANTI + ")");
        refreshClearAnti();
    }

    @Override
    public void onLevel(float dbfs) {
        float v = Math.max(0, Math.min(1, (dbfs + 70) / 60f));
        meter.setScaleX(Math.max(0.02f, v));
    }

    @Override
    public void onEngineError(String message) {
        refreshEnroll(message, UiKit.DANGER);
    }

    @Override
    public void onSilenced(boolean silenced) {
        if (silenced) refreshEnroll("ไมค์ถูกระบบปิดเสียงอยู่ (สายโทร / ปิดไมค์ในแผงด่วน)", UiKit.DANGER);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        boolean ok = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (!ok) {
            prefs.put(Settings.VOICE_ENABLED, false);
            UiKit.toast(this, "ต้องอนุญาตไมโครโฟนก่อนจึงจะสอนเสียงได้");
            return;
        }
        if (pendingText != null) {
            String t = pendingText, id = pendingId;
            pendingText = null;
            pendingId = null;
            beginEnroll(t, id);
        }
    }

    @SuppressWarnings("unused")
    private static Icons icon(int type) {
        return new Icons(type, UiKit.TEXT);
    }
}
