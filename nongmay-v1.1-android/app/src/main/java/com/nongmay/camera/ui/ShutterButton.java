package com.nongmay.camera.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Large thumb-zone shutter: white disc (photo), red dot (video), red square while recording. */
public final class ShutterButton extends View {
    public static final int PHOTO = 0, VIDEO = 1;
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int kind = PHOTO;
    private boolean recording, busy;
    private float progress = -1;
    private final RectF r = new RectF();

    public ShutterButton(Context c) {
        super(c);
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(0xFFFFFFFF);
        arc.setStyle(Paint.Style.STROKE);
        arc.setColor(UiKit.ACCENT);
        arc.setStrokeCap(Paint.Cap.ROUND);
        setClickable(true);
        setFocusable(true);
        setContentDescription("ชัตเตอร์ (Shutter)");
    }

    public void setKind(int k) {
        kind = k;
        setContentDescription(k == VIDEO ? (recording ? "หยุดอัด (Stop)" : "อัดวิดีโอ (Record)") : "ถ่ายรูป (Shutter)");
        invalidate();
    }

    public void setRecording(boolean rec) {
        recording = rec;
        setKind(kind);
    }

    public void setBusy(boolean b) {
        busy = b;
        invalidate();
    }

    /** Countdown / interval progress 0..1, or negative to hide. */
    public void setProgress(float p) {
        progress = p;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float rad = Math.min(w, h) / 2f;
        float stroke = rad * 0.09f;
        ring.setStrokeWidth(stroke);
        canvas.drawCircle(cx, cy, rad - stroke, ring);
        float inner = rad - stroke * 2.6f;
        if (isPressed()) inner *= 0.9f;
        if (kind == PHOTO) {
            disc.setColor(busy ? 0xFFDDDDDD : 0xFFFFFFFF);
            canvas.drawCircle(cx, cy, inner, disc);
        } else if (recording) {
            disc.setColor(UiKit.DANGER);
            float s = inner * 0.55f;
            r.set(cx - s, cy - s, cx + s, cy + s);
            canvas.drawRoundRect(r, s * 0.25f, s * 0.25f, disc);
        } else {
            disc.setColor(UiKit.DANGER);
            canvas.drawCircle(cx, cy, inner * 0.9f, disc);
        }
        if (progress >= 0) {
            arc.setStrokeWidth(stroke);
            r.set(cx - rad + stroke, cy - rad + stroke, cx + rad - stroke, cy + rad - stroke);
            canvas.drawArc(r, -90, 360 * Math.min(1f, progress), false, arc);
        }
    }

    @Override
    public void setPressed(boolean pressed) {
        super.setPressed(pressed);
        invalidate();
    }
}
