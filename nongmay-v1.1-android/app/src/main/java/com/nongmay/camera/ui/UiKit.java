package com.nongmay.camera.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Programmatic UI building blocks (no XML layouts) with one consistent dark "pro camera" style. */
public final class UiKit {
    public static final int BG = 0xFF000000;
    public static final int PANEL = 0xD90E0F12;
    public static final int CARD = 0xFF16181D;
    public static final int CHIP = 0xFF25272E;
    public static final int TEXT = 0xFFFFFFFF;
    public static final int MUTED = 0xFFA3A7B0;
    public static final int ACCENT = 0xFFFFD54F;   // pro values / active state
    public static final int VOICE = 0xFFFF7BA7;    // น้องเม signature colour (voice only)
    public static final int DANGER = 0xFFFF453A;
    public static final int OK = 0xFF34C759;

    private UiKit() {}

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    public static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusPx);
        return d;
    }

    public static GradientDrawable roundStroke(int fill, int stroke, int strokePx, float radiusPx) {
        GradientDrawable d = round(fill, radiusPx);
        d.setStroke(strokePx, stroke);
        return d;
    }

    public static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    public static TextView mono(Context c, String s, float sp) {
        TextView t = text(c, s, sp, TEXT, false);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        return t;
    }

    public static TextView chip(Context c, String s) {
        TextView t = text(c, s, 13, TEXT, true);
        int h = dp(c, 12), v = dp(c, 7);
        t.setPadding(h, v, h, v);
        t.setGravity(Gravity.CENTER);
        t.setBackground(round(CHIP, dp(c, 16)));
        t.setMinWidth(dp(c, 44));
        t.setMinHeight(dp(c, 36));
        return t;
    }

    public static void chipState(TextView chip, boolean on) {
        chip.setBackground(round(on ? ACCENT : CHIP, dp(chip.getContext(), 16)));
        chip.setTextColor(on ? 0xFF111111 : TEXT);
    }

    public static void chipDisabled(TextView chip) {
        chip.setBackground(round(0xFF1A1B1F, dp(chip.getContext(), 16)));
        chip.setTextColor(0xFF5A5D66);
    }

    public static TextView button(Context c, String s, boolean primary) {
        TextView t = text(c, s, 15, primary ? 0xFF111111 : TEXT, true);
        int h = dp(c, 18), v = dp(c, 12);
        t.setPadding(h, v, h, v);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(c, 48));
        t.setBackground(round(primary ? ACCENT : CHIP, dp(c, 24)));
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public static LinearLayout.LayoutParams lpm(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    public static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
    }

    public static View space(Context c, int wDp, int hDp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, wDp), dp(c, hDp)));
        return v;
    }

    public static ScrollView scroll(Context c, View child) {
        ScrollView s = new ScrollView(c);
        s.setFillViewport(true);
        s.addView(child);
        return s;
    }

    public static TextView section(Context c, String s) {
        TextView t = text(c, s, 13, ACCENT, true);
        t.setPadding(0, dp(c, 20), 0, dp(c, 6));
        return t;
    }

    /** A settings row: title + description on the left, a Switch on the right. */
    public static Switch switchRow(LinearLayout parent, String title, String desc, boolean value) {
        Context c = parent.getContext();
        LinearLayout row = row(c);
        row.setPadding(0, dp(c, 8), 0, dp(c, 8));
        LinearLayout texts = col(c);
        texts.addView(text(c, title, 15, TEXT, false));
        if (desc != null) {
            TextView d = text(c, desc, 12, MUTED, false);
            texts.addView(d);
        }
        row.addView(texts, weight(1));
        Switch sw = new Switch(c);
        sw.setChecked(value);
        sw.setContentDescription(title);
        row.addView(sw);
        parent.addView(row);
        return sw;
    }

    public static void toast(Context c, String s) {
        Toast.makeText(c, s, Toast.LENGTH_SHORT).show();
    }
}
