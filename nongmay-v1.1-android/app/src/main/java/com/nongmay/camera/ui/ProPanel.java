package com.nongmay.camera.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Collapsible Pro deck: one row of compact chips (ISO, S, EV, WB, AF/MF, …). Tapping a chip opens
 * one adjuster with the numeric value, −/+ steps, AUTO and a slider — never more than two taps
 * from the preview. Unsupported controls stay visible but explain why they are disabled.
 */
public final class ProPanel extends LinearLayout {

    public static final class Control {
        public final String key;
        public final String title;
        public String[] options = new String[0];
        public int index;
        public boolean hasAuto;
        public boolean auto;
        public boolean slider = true;
        public boolean actions;   // options are one-shot actions (presets)
        public String disabledReason;
        public String hint;

        public Control(String key, String title) {
            this.key = key;
            this.title = title;
        }

        public String value() {
            if (disabledReason != null) return "—";
            if (hasAuto && auto) return "A";
            if (actions || options.length == 0) return "";
            return options[Math.max(0, Math.min(options.length - 1, index))];
        }
    }

    public interface Listener {
        void onControlChanged(Control c, boolean fromUser);
    }

    private final LinearLayout chips;
    private final LinearLayout adjust;
    private final TextView adjTitle, adjValue, adjHint;
    private final TextView autoBtn, minusBtn, plusBtn;
    private final SeekBar seek;
    private final LinearLayout optionRow;
    private final HorizontalScrollView optionScroll;
    private final List<Control> controls = new ArrayList<>();
    private final List<TextView> chipViews = new ArrayList<>();
    private Control open;
    private Listener listener;
    private boolean updating;

    public ProPanel(Context c) {
        super(c);
        setOrientation(VERTICAL);
        int d8 = UiKit.dp(c, 8);
        adjust = UiKit.col(c);
        adjust.setPadding(d8 * 2, d8, d8 * 2, d8);
        adjust.setBackground(UiKit.round(UiKit.PANEL, UiKit.dp(c, 14)));
        LinearLayout head = UiKit.row(c);
        adjTitle = UiKit.text(c, "", 13, UiKit.MUTED, true);
        adjValue = UiKit.text(c, "", 20, UiKit.ACCENT, true);
        adjValue.setPadding(d8, 0, d8, 0);
        head.addView(adjTitle);
        head.addView(adjValue, UiKit.weight(1));
        autoBtn = UiKit.chip(c, "AUTO");
        minusBtn = UiKit.chip(c, "−");
        plusBtn = UiKit.chip(c, "+");
        minusBtn.setContentDescription("ลดค่า (decrease)");
        plusBtn.setContentDescription("เพิ่มค่า (increase)");
        head.addView(minusBtn, UiKit.lpm(UiKit.dp(c, 44), UiKit.dp(c, 40), 0, 0, d8 / 2, 0));
        head.addView(plusBtn, UiKit.lpm(UiKit.dp(c, 44), UiKit.dp(c, 40), 0, 0, d8 / 2, 0));
        head.addView(autoBtn, UiKit.lp(ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(c, 40)));
        adjust.addView(head);
        seek = new SeekBar(c);
        seek.setPadding(d8, d8, d8, d8);
        adjust.addView(seek, UiKit.lp(ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(c, 44)));
        optionRow = UiKit.row(c);
        optionScroll = new HorizontalScrollView(c);
        optionScroll.setHorizontalScrollBarEnabled(false);
        optionScroll.addView(optionRow);
        adjust.addView(optionScroll);
        adjHint = UiKit.text(c, "", 11, UiKit.MUTED, false);
        adjust.addView(adjHint);
        adjust.setVisibility(GONE);
        addView(adjust, UiKit.lpm(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, d8, 0, d8, d8 / 2));

        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        chips = UiKit.row(c);
        chips.setPadding(d8, 0, d8, 0);
        hs.addView(chips);
        addView(hs);

        autoBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (open == null || !open.hasAuto) return;
                open.auto = !open.auto;
                fire(open);
            }
        });
        minusBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                step(-1);
            }
        });
        plusBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                step(1);
            }
        });
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (!user || updating || open == null) return;
                if (p != open.index) {
                    open.index = p;
                    open.auto = false;
                    s.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    fire(open);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
    }

    public void setListener(Listener l) {
        listener = l;
    }

    private void step(int dir) {
        if (open == null || open.options.length == 0) return;
        open.index = Math.max(0, Math.min(open.options.length - 1, open.index + dir));
        open.auto = false;
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        fire(open);
    }

    private void fire(Control c) {
        refreshAdjuster();
        refreshChips();
        if (listener != null) listener.onControlChanged(c, true);
    }

    public void setControls(List<Control> list) {
        String openKey = open == null ? null : open.key;
        controls.clear();
        controls.addAll(list);
        chips.removeAllViews();
        chipViews.clear();
        open = null;
        Context c = getContext();
        for (final Control ctl : controls) {
            TextView chip = UiKit.chip(c, "");
            chip.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggle(ctl);
                }
            });
            chips.addView(chip, UiKit.lpm(ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(c, 38), 0, 0, UiKit.dp(c, 6), 0));
            chipViews.add(chip);
            if (ctl.key.equals(openKey)) open = ctl;
        }
        refreshChips();
        refreshAdjuster();
    }

    public Control get(String key) {
        for (Control c : controls) if (c.key.equals(key)) return c;
        return null;
    }

    public void refresh() {
        refreshChips();
        refreshAdjuster();
    }

    public void collapse() {
        open = null;
        refreshAdjuster();
        refreshChips();
    }

    private void toggle(Control c) {
        open = open == c ? null : c;
        refreshAdjuster();
        refreshChips();
    }

    private void refreshChips() {
        for (int i = 0; i < controls.size(); i++) {
            Control ctl = controls.get(i);
            TextView chip = chipViews.get(i);
            String v = ctl.value();
            chip.setText(v.isEmpty() ? ctl.title : ctl.title + " " + v);
            chip.setContentDescription(ctl.title + " " + (v.equals("A") ? "อัตโนมัติ" : v));
            if (ctl.disabledReason != null) UiKit.chipDisabled(chip);
            else UiKit.chipState(chip, ctl == open || (!ctl.actions && ctl.hasAuto && !ctl.auto));
        }
    }

    private void refreshAdjuster() {
        updating = true;
        Context c = getContext();
        if (open == null) {
            adjust.setVisibility(GONE);
            updating = false;
            return;
        }
        adjust.setVisibility(VISIBLE);
        adjTitle.setText(open.title);
        if (open.disabledReason != null) {
            adjValue.setText("ไม่รองรับ");
            adjHint.setText(open.disabledReason);
            seek.setVisibility(GONE);
            optionScroll.setVisibility(GONE);
            autoBtn.setVisibility(GONE);
            minusBtn.setVisibility(GONE);
            plusBtn.setVisibility(GONE);
            updating = false;
            return;
        }
        adjValue.setText(open.value().equals("A") ? "AUTO" : open.value());
        adjHint.setText(open.hint == null ? "" : open.hint);
        adjHint.setVisibility(open.hint == null ? GONE : VISIBLE);
        autoBtn.setVisibility(open.hasAuto ? VISIBLE : GONE);
        UiKit.chipState(autoBtn, open.hasAuto && open.auto);
        boolean slider = open.slider && !open.actions && open.options.length > 6;
        seek.setVisibility(slider ? VISIBLE : GONE);
        minusBtn.setVisibility(slider ? VISIBLE : GONE);
        plusBtn.setVisibility(slider ? VISIBLE : GONE);
        if (slider) {
            seek.setMax(open.options.length - 1);
            seek.setProgress(open.index);
        }
        optionScroll.setVisibility(slider ? GONE : VISIBLE);
        optionRow.removeAllViews();
        if (!slider) {
            for (int i = 0; i < open.options.length; i++) {
                final int idx = i;
                TextView o = UiKit.chip(c, open.options[i]);
                UiKit.chipState(o, !open.actions && !(open.hasAuto && open.auto) && i == open.index);
                o.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        open.index = idx;
                        open.auto = false;
                        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        fire(open);
                    }
                });
                optionRow.addView(o, UiKit.lpm(ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(c, 38), 0, UiKit.dp(c, 4), UiKit.dp(c, 6), UiKit.dp(c, 4)));
            }
        }
        adjust.setGravity(Gravity.CENTER_VERTICAL);
        updating = false;
    }
}
