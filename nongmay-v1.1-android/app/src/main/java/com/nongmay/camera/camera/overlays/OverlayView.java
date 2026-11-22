package com.nongmay.camera.camera.overlays;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

/**
 * Everything drawn over the live preview: grids, level, histogram, zebra/peaking bitmap,
 * focus ring + lock badge, Story safe zones, countdown and the shutter "blink".
 * Zebra/peaking/histogram come from the YUV preview stream and only approximate the saved file.
 */
public final class OverlayView extends View {
    public static final int GRID_OFF = 0, GRID_THIRDS = 1, GRID_GOLDEN = 2, GRID_CROSS = 3;

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strong = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmpPaint = new Paint();
    private final Matrix m = new Matrix();
    private final RectF rf = new RectF();

    private int grid = GRID_OFF;
    private boolean level;
    private float roll;
    private boolean flat;
    private boolean histogram;
    private FrameAnalyzer.Result analysis;
    private int sensorOrientation = 90;
    private boolean front;
    private float focusX = -1, focusY = -1;
    private long focusAt;
    private boolean locked;
    private int focusState; // 0 pending, 1 ok, 2 failed
    private boolean storyZones;
    private String countdown;
    private long blinkAt;
    private String banner;
    private int cropMask; // 0 none, 1 = 1:1, 2 = 3:2 (post-capture crop preview)

    public OverlayView(Context c) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Math.max(1, d * 0.8f));
        line.setColor(0x88FFFFFF);
        strong.setStyle(Paint.Style.STROKE);
        strong.setStrokeWidth(d * 1.6f);
        strong.setColor(0xFFFFD54F);
        text.setColor(0xFFFFFFFF);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void setGrid(int g) { grid = g; invalidate(); }
    public int grid() { return grid; }
    public void setLevelEnabled(boolean on) { level = on; invalidate(); }
    public void setLevel(float rollDeg, boolean isFlat) { roll = rollDeg; flat = isFlat; if (level) invalidate(); }
    public void setHistogram(boolean on) { histogram = on; invalidate(); }
    public void setStoryZones(boolean on) { storyZones = on; invalidate(); }
    public void setBanner(String b) { banner = b; invalidate(); }
    public void setCropMask(int m) { cropMask = m; invalidate(); }

    public void setSensor(int orientation, boolean isFront) {
        sensorOrientation = orientation;
        front = isFront;
    }

    public void setAnalysis(FrameAnalyzer.Result r) {
        analysis = r;
        invalidate();
    }

    public void clearAnalysis() {
        analysis = null;
        invalidate();
    }

    public void showFocus(float nx, float ny, boolean lock) {
        focusX = nx;
        focusY = ny;
        locked = lock;
        focusState = 0;
        focusAt = SystemClock.uptimeMillis();
        invalidate();
    }

    public void focusResult(boolean ok) {
        focusState = ok ? 1 : 2;
        invalidate();
    }

    public void clearFocus() {
        focusX = -1;
        locked = false;
        invalidate();
    }

    public boolean isLocked() {
        return locked && focusX >= 0;
    }

    public void setCountdown(String s) {
        countdown = s;
        invalidate();
    }

    public void blink() {
        blinkAt = SystemClock.uptimeMillis();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float d = getResources().getDisplayMetrics().density;
        FrameAnalyzer.Result a = analysis;
        if (a != null && a.overlay != null && !a.overlay.isRecycled()) drawAnalysisBitmap(c, a.overlay, w, h);
        if (cropMask != 0) {
            float iw = w, ih = h;
            if (cropMask == 1) { float side = Math.min(w, h); iw = side; ih = side; }
            else if (h >= w) { iw = Math.min(w, h * 2f / 3f); ih = iw * 1.5f; }
            else { ih = Math.min(h, w * 2f / 3f); iw = ih * 1.5f; }
            float l = (w - iw) / 2f, t = (h - ih) / 2f;
            fill.setColor(0x99000000);
            c.drawRect(0, 0, w, t, fill);
            c.drawRect(0, t + ih, w, h, fill);
            c.drawRect(0, t, l, t + ih, fill);
            c.drawRect(l + iw, t, w, t + ih, fill);
        }
        if (storyZones) {
            fill.setColor(0x55000000);
            c.drawRect(0, 0, w, h * 0.14f, fill);
            c.drawRect(0, h * 0.80f, w, h, fill);
            text.setTextSize(11 * d);
            text.setColor(0xCCFFFFFF);
            c.drawText("พื้นที่ UI ของ Instagram (ประมาณการ อาจเปลี่ยน)", w / 2, h * 0.14f - 6 * d, text);
            c.drawText("โซนปุ่ม/ข้อความ (ประมาณการ)", w / 2, h * 0.80f + 16 * d, text);
            text.setColor(0xFFFFFFFF);
        }
        switch (grid) {
            case GRID_THIRDS:
                for (int i = 1; i <= 2; i++) {
                    c.drawLine(w * i / 3f, 0, w * i / 3f, h, line);
                    c.drawLine(0, h * i / 3f, w, h * i / 3f, line);
                }
                break;
            case GRID_GOLDEN:
                float g1 = 0.382f, g2 = 0.618f;
                c.drawLine(w * g1, 0, w * g1, h, line);
                c.drawLine(w * g2, 0, w * g2, h, line);
                c.drawLine(0, h * g1, w, h * g1, line);
                c.drawLine(0, h * g2, w, h * g2, line);
                break;
            case GRID_CROSS:
                float s = 18 * d;
                c.drawLine(w / 2 - s, h / 2, w / 2 + s, h / 2, line);
                c.drawLine(w / 2, h / 2 - s, w / 2, h / 2 + s, line);
                break;
            default:
                break;
        }
        if (level && !flat) {
            boolean ok = Math.abs(roll) < 1.0f;
            strong.setColor(ok ? 0xFF34C759 : 0xFFFFFFFF);
            c.save();
            c.rotate(roll, w / 2, h / 2);
            float L = w * 0.22f;
            c.drawLine(w / 2 - L, h / 2, w / 2 - 10 * d, h / 2, strong);
            c.drawLine(w / 2 + 10 * d, h / 2, w / 2 + L, h / 2, strong);
            c.restore();
            text.setTextSize(11 * d);
            c.drawText(String.format(java.util.Locale.ROOT, "%.1f°", roll), w / 2, h / 2 - 12 * d, text);
        }
        if (histogram && a != null) drawHistogram(c, a, d);
        if (focusX >= 0) drawFocus(c, w, h, d);
        if (countdown != null) {
            text.setTextSize(110 * d);
            text.setColor(0xF0FFFFFF);
            fill.setColor(0x33000000);
            c.drawCircle(w / 2, h / 2, 90 * d, fill);
            c.drawText(countdown, w / 2, h / 2 + 38 * d, text);
            text.setColor(0xFFFFFFFF);
        }
        if (banner != null) {
            text.setTextSize(13 * d);
            float tw = text.measureText(banner) + 24 * d;
            fill.setColor(0xCC000000);
            rf.set(w / 2 - tw / 2, 12 * d, w / 2 + tw / 2, 40 * d);
            c.drawRoundRect(rf, 14 * d, 14 * d, fill);
            c.drawText(banner, w / 2, 31 * d, text);
        }
        long since = SystemClock.uptimeMillis() - blinkAt;
        if (since < 140) {
            fill.setColor(((int) (0x99 * (1 - since / 140f)) << 24));
            c.drawRect(0, 0, w, h, fill);
            postInvalidateOnAnimation();
        }
    }

    private void drawAnalysisBitmap(Canvas c, Bitmap bmp, float w, float h) {
        int bw = bmp.getWidth(), bh = bmp.getHeight();
        boolean swap = sensorOrientation == 90 || sensorOrientation == 270;
        float rw = swap ? bh : bw, rh = swap ? bw : bh;
        m.reset();
        m.postTranslate(-bw / 2f, -bh / 2f);
        m.postRotate(sensorOrientation);
        if (front) m.postScale(-1, 1);
        m.postScale(w / rw, h / rh);
        m.postTranslate(w / 2f, h / 2f);
        c.drawBitmap(bmp, m, bmpPaint);
    }

    private void drawHistogram(Canvas c, FrameAnalyzer.Result a, float d) {
        float bw = 128 * d, bh = 56 * d, x0 = 10 * d, y0 = 54 * d;
        fill.setColor(0x88000000);
        rf.set(x0, y0, x0 + bw, y0 + bh);
        c.drawRoundRect(rf, 6 * d, 6 * d, fill);
        fill.setColor(0xDDFFFFFF);
        int n = a.histogram.length;
        float step = (bw - 8 * d) / n;
        double max = Math.max(1, a.maxBin);
        for (int i = 0; i < n; i++) {
            float v = (float) (Math.sqrt(a.histogram[i] / max) * (bh - 8 * d));
            c.drawRect(x0 + 4 * d + i * step, y0 + bh - 4 * d - v, x0 + 4 * d + (i + 1) * step, y0 + bh - 4 * d, fill);
        }
        text.setTextSize(9 * d);
        text.setTextAlign(Paint.Align.LEFT);
        if (a.clippedPct > 0.5) { text.setColor(0xFFFF453A); c.drawText(String.format(java.util.Locale.ROOT, "ไฮไลต์ล้น %.1f%%", a.clippedPct), x0, y0 + bh + 12 * d, text); }
        if (a.crushedPct > 2) { text.setColor(0xFF64D2FF); c.drawText(String.format(java.util.Locale.ROOT, "เงาจม %.1f%%", a.crushedPct), x0, y0 + bh + 24 * d, text); }
        text.setColor(0xFFFFFFFF);
        text.setTextAlign(Paint.Align.CENTER);
    }

    private void drawFocus(Canvas c, float w, float h, float d) {
        long age = SystemClock.uptimeMillis() - focusAt;
        if (!locked && age > 2500) {
            focusX = -1;
            return;
        }
        float x = focusX * w, y = focusY * h;
        float r = 34 * d * (age < 180 ? 1.25f - 0.25f * age / 180f : 1f);
        strong.setColor(focusState == 2 ? 0xFFFF453A : (locked ? 0xFFFFD54F : 0xFFFFFFFF));
        c.drawCircle(x, y, r, strong);
        if (locked) {
            text.setTextSize(11 * d);
            text.setColor(0xFFFFD54F);
            c.drawText("AE/AF LOCK", x, y - r - 8 * d, text);
            text.setColor(0xFFFFFFFF);
        }
        if (age < 200) postInvalidateOnAnimation();
    }
}
