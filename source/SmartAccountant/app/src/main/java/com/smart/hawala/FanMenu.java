package com.smart.hawala;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;
import java.util.List;

/**
 * 10.15 (من «أموالي» 1.6): قائمة «+» المروحية فوق زر «تسجيل» في الشريط الزجاجي: حركة عادية، قص عملات، بين مكتبين،
 * من واتساب، وأزرار الصندوق… كما يختارها كل مستخدم (ما لا يملك صلاحيته لا يظهر له).
 *
 * أموالي 1.3: قائمة «+» المروحية — نصف دائرة من شرائح فوق الزر الوسطي تنفتح كالمروحة (مثل الفيديو المرسل) بألوان التطبيق:
 * شرائح زجاجية فاتحة متدرجة، ولكل شريحة أيقونة صغيرة في دائرة ملوّنة وكلمة واحدة (استلام، تسليم، بيع، مصروف، جرد).
 * الخلفية تُعتَّم بالكحلي، والضغط خارج المروحة أو على الزر يغلقها، والشرائح تُطوى عكسيًا عند الإغلاق.
 */
@SuppressLint("ViewConstructor")
final class FanMenu extends View {
    static final class Item {
        final String label;
        final Drawable icon;
        final int color;
        final boolean enabled;
        final Runnable action;

        Item(String label, Drawable icon, int color, boolean enabled, Runnable action) {
            this.label = label;
            this.icon = icon;
            this.color = color;
            this.enabled = enabled;
            this.action = action;
        }
    }

    private final List<Item> items;
    private final java.util.function.Supplier<float[]> center;
    private float cx, cy;
    private final float dp;
    private final Runnable onClosed;
    private float t; // 0 مغلقة … 1 مفتوحة
    private boolean closing;
    private int pressed = -1;
    private final Paint scrim = new Paint(), wedge = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG),
            dot = new Paint(Paint.ANTI_ALIAS_FLAG), label = new Paint(Paint.ANTI_ALIAS_FLAG), shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path p = new Path();
    private final RectF outer = new RectF(), inner = new RectF();

    /** center: مركز الزر الوسطي داخل هذه الطبقة (يُحسب عند كل رسم فيتبع الشريط). */
    private final NavPrefs st;
    private final float k; // مقياس الحجم

    FanMenu(Context c, List<Item> items, java.util.function.Supplier<float[]> center, Typeface font, NavPrefs st, Runnable onClosed) {
        super(c);
        this.st = st == null ? new NavPrefs() : st;
        this.k = this.st.fanSize == 0 ? 0.74f : this.st.fanSize == 1 ? 0.86f : 1f;
        this.items = items;
        this.center = center;
        this.onClosed = onClosed;
        this.dp = c.getResources().getDisplayMetrics().density;
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        setClickable(true);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(1f * dp);
        edge.setColor(Color.argb(120, 255, 255, 255));
        label.setTextAlign(Paint.Align.CENTER);
        label.setTextSize((this.st.fanSize == 0 ? 11.5f : this.st.fanSize == 1 ? 12.5f : 13.5f) * dp);
        label.setColor(this.st.fanStyle == 0 ? Color.rgb(22, 50, 92) : Color.WHITE);
        if (this.st.fanStyle != 0) edge.setColor(Color.argb(60, 255, 255, 255));
        if (font != null) label.setTypeface(Typeface.create(font, Typeface.BOLD));
        else label.setFakeBoldText(true);
        shadow.setColor(Color.argb(70, 8, 20, 40));
        shadow.setMaskFilter(new BlurMaskFilter(14 * dp, BlurMaskFilter.Blur.NORMAL));
        for (Item it : items) it.icon.mutate().setTint(Color.WHITE);
        setContentDescription("قائمة التسجيل");
    }

    void open() {
        if (Ui.NO_MOTION) {
            t = 1;
            invalidate();
            return;
        }
        ValueAnimator a = ValueAnimator.ofFloat(0, 1);
        a.setDuration(520);
        a.setInterpolator(new PathInterpolator(0.2f, 0.9f, 0.25f, 1f));
        a.addUpdateListener(x -> {
            t = (float) x.getAnimatedValue();
            invalidate();
        });
        a.start();
    }

    void close(final Runnable then) {
        if (closing) return;
        closing = true;
        ValueAnimator a = ValueAnimator.ofFloat(t, 0);
        a.setDuration(Ui.NO_MOTION ? 0 : 300);
        a.setInterpolator(new DecelerateInterpolator());
        a.addUpdateListener(x -> {
            t = (float) x.getAnimatedValue();
            invalidate();
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator x) {
                ViewGroup g = (ViewGroup) getParent();
                if (g != null) g.removeView(FanMenu.this);
                if (onClosed != null) onClosed.run();
                if (then != null) then.run();
            }
        });
        a.start();
    }

    boolean isClosing() { return closing; }

    private float outerR() { return Math.min(getWidth() * 0.47f * k, 172 * dp * k); }

    private float innerR() { return 34 * dp; }

    /** زاوية بداية الشريحة i (بالدرجات، 180 = اليسار، 360 = اليمين). الأولى على اليمين لأن الواجهة عربية. */
    private float span() { return SWEEP / items.size(); }

    /** المروحة أقل من نصف دائرة (من 195° إلى 345°) حتى لا تختفي الأزرار الطرفية خلف الشريط. */
    static final float START = 195f, SWEEP = 150f, END = START + SWEEP;

    @Override
    protected void onDraw(Canvas c) {
        int n = items.size();
        float[] cc = center.get();
        cx = cc[0];
        cy = cc[1];
        scrim.setColor(Color.argb((int) (175 * Math.min(1f, t * 1.4f)), 10, 22, 42));
        c.drawRect(0, 0, getWidth(), getHeight(), scrim);
        float R = outerR(), r0 = innerR(), sp = span();
        outer.set(cx - R, cy - R, cx + R, cy + R);
        inner.set(cx - r0, cy - r0, cx + r0, cy + r0);
        // ظل المروحة كاملة
        float allSweep = SWEEP * Math.min(1f, t);
        if (allSweep > 1) {
            p.reset();
            p.arcTo(outer, START, allSweep);
            p.arcTo(inner, START + allSweep, -allSweep);
            p.close();
            c.drawPath(p, shadow);
        }
        float[] starts = new float[n], lts = new float[n];
        for (int k = 0; k < n; k++) {
            // الشرائح تنفتح كالمروحة من اليسار: الشريحة تدور من 180° إلى موضعها، بتتابع خفيف
            int i = n - 1 - k; // i = ترتيب العنصر (0 على اليمين)
            float delay = k * 0.06f;
            float lt = Math.max(0f, Math.min(1f, (t - delay) / (1f - delay)));
            lts[i] = lt;
            float home = END - (i + 1) * sp; // موضعها النهائي
            starts[i] = START + (home - START) * lt;
        }
        // الشرائح أولًا ثم الأيقونات والكلمات فوقها كلها (حتى لا تغطي شريحةٌ كلمةَ جارتها)
        for (int k = 0; k < n; k++) {
            int i = n - 1 - k;
            float lt = lts[i];
            if (lt <= 0) continue;
            float alpha = Math.min(1f, lt * 1.6f);
            p.reset();
            p.arcTo(outer, starts[i], sp);
            p.arcTo(inner, starts[i] + sp, -sp);
            p.close();
            int[] pal = st.fanStyle == 1 ? new int[]{Color.rgb(28, 58, 102), Color.rgb(36, 70, 120), Color.rgb(52, 92, 150)}
                    : st.fanStyle == 2 ? new int[]{Color.rgb(36, 39, 46), Color.rgb(48, 52, 60), Color.rgb(70, 76, 88)}
                    : new int[]{Color.rgb(250, 251, 253), Color.rgb(236, 241, 248), Color.rgb(222, 231, 245)};
            int base = i == pressed ? pal[2] : pal[i % 2];
            wedge.setColor(base);
            wedge.setAlpha((int) (255 * alpha));
            c.drawPath(p, wedge);
            edge.setAlpha((int) (120 * alpha));
            c.drawPath(p, edge);
        }
        for (int i = 0; i < n; i++) {
            float lt = lts[i];
            if (lt <= 0) continue;
            float alpha = Math.min(1f, lt * 1.6f);
            Item it = items.get(i);
            // الأيقونة في دائرة ملوّنة والكلمة تحتها (معتدلة دائمًا للقراءة)
            double mid = Math.toRadians(starts[i] + sp / 2f);
            float rr = r0 + (R - r0) * 0.55f;
            float ix = cx + (float) Math.cos(mid) * rr, iy = cy + (float) Math.sin(mid) * rr - 6 * dp;
            float dr = 19 * dp * k * (0.6f + 0.4f * lt);
            dot.setColor(it.enabled ? it.color : Color.rgb(170, 178, 190));
            dot.setAlpha((int) (255 * alpha));
            c.drawCircle(ix, iy, dr, dot);
            int is = (int) (20 * dp * k * (0.6f + 0.4f * lt));
            it.icon.setBounds((int) (ix - is / 2f), (int) (iy - is / 2f), (int) (ix + is / 2f), (int) (iy + is / 2f));
            it.icon.setAlpha((int) (255 * alpha));
            it.icon.draw(c);
            label.setAlpha((int) (255 * alpha * (it.enabled ? 1f : 0.5f)));
            c.drawText(it.label, ix, iy + dr + 14 * dp * k + 2 * dp, label);
        }
    }

    /** رقم الشريحة تحت النقطة، أو −1. */
    private int hit(float x, float y) {
        float dx = x - cx, dy = y - cy;
        float d = (float) Math.sqrt(dx * dx + dy * dy);
        if (d < innerR() || d > outerR() || dy > 0) return -1;
        float ang = (float) Math.toDegrees(Math.atan2(dy, dx));
        if (ang < 0) ang += 360f;
        if (ang < START || ang > END) return -1;
        int fromRight = (int) ((END - ang) / span());
        return fromRight >= 0 && fromRight < items.size() ? fromRight : -1;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (closing) return true;
        int h = hit(e.getX(), e.getY());
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                if (h != pressed) {
                    pressed = h;
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                pressed = -1;
                if (h < 0) {
                    close(null);
                    return true;
                }
                final Item it = items.get(h);
                if (getContext() instanceof Ui) ((Ui) getContext()).tick(this);
                if (!it.enabled) {
                    invalidate();
                    android.widget.Toast.makeText(getContext(), "غير متاح الآن", android.widget.Toast.LENGTH_SHORT).show();
                    return true;
                }
                close(it.action);
                return true;
            case MotionEvent.ACTION_CANCEL:
                pressed = -1;
                invalidate();
                return true;
            default:
                return true;
        }
    }
}
