package com.nongmay.camera.storymax;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/**
 * Exact 9:16 framing canvas. FILL/REFRAME: pinch to zoom, drag to reposition — the window is
 * always clamped inside the picture (no stretching, no hidden bars). FIT: whole picture with
 * black or blurred background, clearly not part of the photo. Advisory Instagram UI zones on top.
 */
public final class StoryCanvasView extends View {
    public interface Listener {
        void onFramingChanged();
    }

    private Bitmap preview, blurred;
    private int fullW, fullH;
    private int mode = CropMath.FILL;
    private boolean blur;
    private boolean safeZones = true;
    private double zoom = 1, cx = 0.5, cy = 0.5;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint zone = new Paint();
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect srcR = new Rect();
    private final RectF dstR = new RectF();
    private final ScaleGestureDetector scale;
    private final GestureDetector drag;
    private Listener listener;

    public StoryCanvasView(Context c) {
        super(c);
        float d = getResources().getDisplayMetrics().density;
        zone.setColor(0x55000000);
        text.setColor(0xCCFFFFFF);
        text.setTextSize(11 * d);
        text.setTextAlign(Paint.Align.CENTER);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(d);
        border.setColor(0x66FFFFFF);
        setContentDescription("กรอบ Story 9:16 — จีบนิ้วเพื่อซูม ลากเพื่อจัดตำแหน่ง");
        scale = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector det) {
                if (mode == CropMath.FIT || preview == null) return false;
                zoom = Math.max(1.0, Math.min(8.0, zoom * det.getScaleFactor()));
                changed();
                return true;
            }
        });
        drag = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (mode == CropMath.FIT || preview == null) return false;
                double[] win = CropMath.coverRect(preview.getWidth(), preview.getHeight(), CropMath.STORY_ASPECT, zoom, cx, cy);
                double perPx = (win[2] - win[0]) / getWidth();
                cx += dx * perPx / preview.getWidth();
                cy += dy * perPx / preview.getHeight();
                double[] clamped = CropMath.coverRect(preview.getWidth(), preview.getHeight(), CropMath.STORY_ASPECT, zoom, cx, cy);
                cx = (clamped[0] + clamped[2]) / 2 / preview.getWidth();
                cy = (clamped[1] + clamped[3]) / 2 / preview.getHeight();
                changed();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                reset();
                return true;
            }
        });
    }

    public void setListener(Listener l) {
        listener = l;
    }

    /** @param orientedPreview downsampled, already-oriented picture; fullW/fullH = oriented full-resolution size. */
    public void setSource(Bitmap orientedPreview, int fullW, int fullH) {
        preview = orientedPreview;
        this.fullW = fullW;
        this.fullH = fullH;
        blurred = null;
        if (preview != null) {
            int bw = Math.max(1, preview.getWidth() / 24), bh = Math.max(1, preview.getHeight() / 24);
            blurred = Bitmap.createScaledBitmap(preview, bw, bh, true);
        }
        reset();
    }

    public void setMode(int m, boolean blurBg) {
        mode = m;
        blur = blurBg;
        changed();
    }

    public int mode() {
        return mode;
    }

    public boolean blur() {
        return blur;
    }

    public void setSafeZones(boolean on) {
        safeZones = on;
        invalidate();
    }

    public void reset() {
        zoom = 1;
        cx = 0.5;
        cy = 0.5;
        changed();
    }

    private void changed() {
        invalidate();
        if (listener != null) listener.onFramingChanged();
    }

    /** Crop window in full-resolution oriented source pixels (FILL/REFRAME). */
    public double[] cropRectFull() {
        return CropMath.coverRect(fullW, fullH, CropMath.STORY_ASPECT, zoom, cx, cy);
    }

    public double keptFraction() {
        return CropMath.keptFraction(cropRectFull(), fullW, fullH);
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int h = (int) Math.round(w * 16.0 / 9.0);
        int maxH = MeasureSpec.getSize(hSpec);
        if (MeasureSpec.getMode(hSpec) != MeasureSpec.UNSPECIFIED && h > maxH) {
            h = maxH;
            w = (int) Math.round(h * 9.0 / 16.0);
        }
        setMeasuredDimension(w, h);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        scale.onTouchEvent(ev);
        if (!scale.isInProgress()) drag.onTouchEvent(ev);
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN && getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        if (ev.getActionMasked() == MotionEvent.ACTION_UP) performClick();
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        c.drawColor(0xFF000000);
        if (preview != null) {
            if (mode == CropMath.FIT) {
                if (blur && blurred != null) {
                    double[] cover = CropMath.coverRect(blurred.getWidth(), blurred.getHeight(), CropMath.STORY_ASPECT, 1, 0.5, 0.5);
                    srcR.set((int) cover[0], (int) cover[1], (int) Math.ceil(cover[2]), (int) Math.ceil(cover[3]));
                    dstR.set(0, 0, w, h);
                    paint.setAlpha(150);
                    c.drawBitmap(blurred, srcR, dstR, paint);
                    paint.setAlpha(255);
                }
                double[] fit = CropMath.fitRect(preview.getWidth(), preview.getHeight(), (int) w, (int) h);
                dstR.set((float) fit[0], (float) fit[1], (float) fit[2], (float) fit[3]);
                c.drawBitmap(preview, null, dstR, paint);
            } else {
                double[] win = CropMath.coverRect(preview.getWidth(), preview.getHeight(), CropMath.STORY_ASPECT, zoom, cx, cy);
                srcR.set((int) Math.round(win[0]), (int) Math.round(win[1]), (int) Math.round(win[2]), (int) Math.round(win[3]));
                dstR.set(0, 0, w, h);
                c.drawBitmap(preview, srcR, dstR, paint);
            }
        }
        if (safeZones) {
            c.drawRect(0, 0, w, h * 0.14f, zone);
            c.drawRect(0, h * 0.80f, w, h, zone);
            c.drawText("โซน UI ของ Instagram (ประมาณการ อาจเปลี่ยนได้)", w / 2, h * 0.14f - text.getTextSize() * 0.6f, text);
            c.drawText("ปุ่ม/ข้อความ (ประมาณการ)", w / 2, h * 0.80f + text.getTextSize() * 1.4f, text);
        }
        c.drawRect(0.5f, 0.5f, w - 0.5f, h - 0.5f, border);
    }
}
