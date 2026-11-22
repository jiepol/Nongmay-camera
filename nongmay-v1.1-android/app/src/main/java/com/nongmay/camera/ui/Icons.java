package com.nongmay.camera.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Original line icons drawn on a 24-unit grid (no icon font or third-party assets). */
public final class Icons extends Drawable {
    public static final int FLASH_OFF = 0, FLASH_AUTO = 1, FLASH_ON = 2, TORCH = 3, MIC = 4, MIC_OFF = 5, SETTINGS = 6,
            SWITCH = 7, GRID = 8, TIMER = 9, LEVEL = 10, LOCK = 11, CLOSE = 12, BACK = 13, PAUSE = 14, PLAY = 15, STORY = 16;

    private final int type;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    public Icons(int type, int color) {
        this.type = type;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setColor(color);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(color);
    }

    @Override
    public void draw(Canvas canvas) {
        float s = Math.min(getBounds().width(), getBounds().height()) / 24f;
        canvas.save();
        canvas.translate(getBounds().left + (getBounds().width() - 24 * s) / 2f, getBounds().top + (getBounds().height() - 24 * s) / 2f);
        canvas.scale(s, s);
        stroke.setStrokeWidth(1.8f);
        path.reset();
        switch (type) {
            case FLASH_OFF:
            case FLASH_AUTO:
            case FLASH_ON:
                bolt();
                if (type == FLASH_ON) canvas.drawPath(path, fill); else canvas.drawPath(path, stroke);
                if (type == FLASH_OFF) canvas.drawLine(4, 3, 20, 21, stroke);
                if (type == FLASH_AUTO) {
                    fill.setTextSize(8f);
                    fill.setFakeBoldText(true);
                    canvas.drawText("A", 16.5f, 22f, fill);
                }
                break;
            case TORCH:
                canvas.drawCircle(12, 12, 9, stroke);
                bolt();
                canvas.save();
                canvas.scale(0.6f, 0.6f, 12, 12);
                canvas.drawPath(path, fill);
                canvas.restore();
                break;
            case MIC:
            case MIC_OFF:
                canvas.drawRoundRect(new RectF(9, 3, 15, 14), 3, 3, type == MIC ? fill : stroke);
                path.moveTo(6, 11);
                path.cubicTo(6, 19, 18, 19, 18, 11);
                canvas.drawPath(path, stroke);
                canvas.drawLine(12, 17.5f, 12, 21, stroke);
                if (type == MIC_OFF) canvas.drawLine(4, 3, 20, 21, stroke);
                break;
            case SETTINGS:
                for (int i = 0; i < 8; i++) {
                    canvas.save();
                    canvas.rotate(i * 45, 12, 12);
                    canvas.drawRect(10.8f, 2.5f, 13.2f, 6f, fill);
                    canvas.restore();
                }
                canvas.drawCircle(12, 12, 6.5f, stroke);
                canvas.drawCircle(12, 12, 2.6f, stroke);
                break;
            case SWITCH:
                canvas.drawArc(new RectF(4, 4, 20, 20), 200, 220, false, stroke);
                path.moveTo(4.5f, 7);
                path.lineTo(4.8f, 11.5f);
                path.lineTo(9, 10);
                canvas.drawPath(path, stroke);
                canvas.drawCircle(12, 12, 2.3f, fill);
                break;
            case GRID:
                canvas.drawRect(4, 4, 20, 20, stroke);
                canvas.drawLine(9.33f, 4, 9.33f, 20, stroke);
                canvas.drawLine(14.67f, 4, 14.67f, 20, stroke);
                canvas.drawLine(4, 9.33f, 20, 9.33f, stroke);
                canvas.drawLine(4, 14.67f, 20, 14.67f, stroke);
                break;
            case TIMER:
                canvas.drawCircle(12, 13.5f, 7.5f, stroke);
                canvas.drawLine(12, 13.5f, 12, 9, stroke);
                canvas.drawLine(10, 3, 14, 3, stroke);
                break;
            case LEVEL:
                canvas.drawLine(3, 12, 21, 12, stroke);
                canvas.drawCircle(12, 12, 3, stroke);
                break;
            case LOCK:
                canvas.drawRoundRect(new RectF(5, 10.5f, 19, 20.5f), 2, 2, fill);
                canvas.drawArc(new RectF(8, 4, 16, 14), 180, 180, false, stroke);
                canvas.drawLine(8, 9, 8, 11, stroke);
                canvas.drawLine(16, 9, 16, 11, stroke);
                break;
            case CLOSE:
                canvas.drawLine(6, 6, 18, 18, stroke);
                canvas.drawLine(18, 6, 6, 18, stroke);
                break;
            case BACK:
                path.moveTo(15, 5);
                path.lineTo(8, 12);
                path.lineTo(15, 19);
                canvas.drawPath(path, stroke);
                break;
            case PAUSE:
                canvas.drawRoundRect(new RectF(7, 5, 10.5f, 19), 1, 1, fill);
                canvas.drawRoundRect(new RectF(13.5f, 5, 17, 19), 1, 1, fill);
                break;
            case PLAY:
                path.moveTo(8, 5);
                path.lineTo(19, 12);
                path.lineTo(8, 19);
                path.close();
                canvas.drawPath(path, fill);
                break;
            case STORY:
                canvas.drawRoundRect(new RectF(7, 2.5f, 17, 21.5f), 2.5f, 2.5f, stroke);
                canvas.drawLine(12, 8.5f, 12, 15.5f, stroke);
                canvas.drawLine(8.5f, 12, 15.5f, 12, stroke);
                break;
            default:
                break;
        }
        canvas.restore();
    }

    private void bolt() {
        path.reset();
        path.moveTo(13, 2.5f);
        path.lineTo(5.5f, 13.5f);
        path.lineTo(11, 13.5f);
        path.lineTo(10, 21.5f);
        path.lineTo(18.5f, 9.5f);
        path.lineTo(13, 9.5f);
        path.close();
    }

    @Override
    public void setAlpha(int alpha) {
        stroke.setAlpha(alpha);
        fill.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        stroke.setColorFilter(cf);
        fill.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
