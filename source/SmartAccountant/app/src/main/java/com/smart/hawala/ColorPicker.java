package com.smart.hawala;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

/**
 * منتقي ألوان متدرّج: مربع للتشبع والإضاءة فوق شريط ألوان الطيف.
 * يحرّك المستخدم إصبعه ليختار أي لون دون كتابة رمزه.
 */
final class ColorPicker extends View {
    interface OnColor { void changed(int color); }

    private final float dp;
    private final float[] hsv = {150f, 0.9f, 0.42f};
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF sv = new RectF(), hue = new RectF();
    private int dragging; // 0 لا شيء، 1 المربع، 2 الشريط
    OnColor listener;

    private static final int[] SPECTRUM = {0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, 0xFFFF0000};

    ColorPicker(Context c, int initial) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        ring.setStyle(Paint.Style.STROKE);
        setColor(initial);
        setLayoutDirection(LAYOUT_DIRECTION_LTR);
    }

    void setColor(int color) {
        Color.colorToHSV(color, hsv);
        invalidate();
    }

    int color() { return Color.HSVToColor(hsv); }

    @Override protected void onMeasure(int w, int h) {
        int width = MeasureSpec.getSize(w);
        setMeasuredDimension(width, (int) (dp * (190 + 16 + 30 + 12)));
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        float pad = dp * 6;
        sv.set(pad, pad, w - pad, pad + dp * 190);
        hue.set(pad, sv.bottom + dp * 16, w - pad, sv.bottom + dp * 46);
    }

    @Override protected void onDraw(Canvas c) {
        float r = dp * 12;
        int pure = Color.HSVToColor(new float[]{hsv[0], 1f, 1f});
        fill.setShader(new LinearGradient(sv.left, 0, sv.right, 0, Color.WHITE, pure, Shader.TileMode.CLAMP));
        c.drawRoundRect(sv, r, r, fill);
        fill.setShader(new LinearGradient(0, sv.top, 0, sv.bottom, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP));
        c.drawRoundRect(sv, r, r, fill);
        fill.setShader(new LinearGradient(hue.left, 0, hue.right, 0, SPECTRUM, null, Shader.TileMode.CLAMP));
        c.drawRoundRect(hue, hue.height() / 2, hue.height() / 2, fill);
        fill.setShader(null);
        // علامة الاختيار في المربع
        float x = sv.left + hsv[1] * sv.width(), y = sv.top + (1 - hsv[2]) * sv.height();
        fill.setColor(color());
        c.drawCircle(x, y, dp * 11, fill);
        ring.setStrokeWidth(dp * 3);
        ring.setColor(Color.WHITE);
        c.drawCircle(x, y, dp * 12, ring);
        ring.setStrokeWidth(dp * 1);
        ring.setColor(Color.argb(120, 0, 0, 0));
        c.drawCircle(x, y, dp * 13.5f, ring);
        // علامة الشريط
        float hx = hue.left + hsv[0] / 360f * hue.width();
        fill.setColor(pure);
        c.drawCircle(hx, hue.centerY(), hue.height() / 2 + dp, fill);
        ring.setStrokeWidth(dp * 3);
        ring.setColor(Color.WHITE);
        c.drawCircle(hx, hue.centerY(), hue.height() / 2 + dp * 2, ring);
        ring.setStrokeWidth(dp * 1);
        ring.setColor(Color.argb(120, 0, 0, 0));
        c.drawCircle(hx, hue.centerY(), hue.height() / 2 + dp * 3.5f, ring);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = y <= sv.bottom + dp * 8 ? 1 : 2;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                apply(x, y);
                return true;
            case MotionEvent.ACTION_MOVE:
                apply(x, y);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = 0;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    private void apply(float x, float y) {
        if (dragging == 1) {
            hsv[1] = clamp((x - sv.left) / sv.width());
            hsv[2] = 1 - clamp((y - sv.top) / sv.height());
        } else if (dragging == 2) {
            hsv[0] = clamp((x - hue.left) / hue.width()) * 359.9f;
        }
        invalidate();
        if (listener != null) listener.changed(color());
    }

    private static float clamp(float v) { return v < 0 ? 0 : v > 1 ? 1 : v; }
}
