package com.smart.hawala;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import java.util.List;

/**
 * 10.15 (من «أموالي» 1.6): الشريط السفلي الزجاجي بتجويف. في «المحاسب الذكي» الدائرة بلون التبويب المفتوح (أو لون مختار)،
 * ولون الشريط من الشريط العلوي للتطبيق (أو لون مختار)، وأسماء التبويبات تحت الأيقونات اختيارية، والاهتزاز حسب إعداد المستخدم.
 *
 * أموالي 1.2: الشريط السفلي الزجاجي بتجويف — مثل الخيار الثالث في الفيديو المرسل («Glassy Navigation Tabs V5»):
 * شريط زجاجي داكن مستدير بحافة فاتحة، والتبويب الحالي دائرة خضراء بارزة فوق الشريط يحتضنها تجويف ناعم في حافته،
 * وتنزلق الدائرة والتجويف معًا إلى التبويب الجديد بحركة مرنة، وخط صغير في أسفل الشريط. أيقونات بيضاء بلا كتابة.
 */
@SuppressLint("ViewConstructor")
final class NotchBar extends View {
    interface OnTab { void picked(int index); }

    /** موضع الدائرة الأخير (يبدأ منه الانزلاق في الشاشة التالية). */
    static float lastPos = -1;

    private final List<Drawable> icons;
    private final List<String> names;
    private final OnTab onTab;
    private final float dp;
    private float pos;
    /** ارتفاع الدائرة: 0 = غائصة داخل الشريط (بلا تجويف)، 1 = بارزة فوقه بالكامل. */
    private float lift = 1f;
    private int target;
    private android.animation.Animator anim;

    private final Paint barFill = new Paint(Paint.ANTI_ALIAS_FLAG), barStroke = new Paint(Paint.ANTI_ALIAS_FLAG), sheen = new Paint(Paint.ANTI_ALIAS_FLAG),
            shadow = new Paint(Paint.ANTI_ALIAS_FLAG), glow = new Paint(Paint.ANTI_ALIAS_FLAG),
            circle = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path barPath = new Path(), cut = new Path(), round = new Path();
    private final RectF r = new RectF();

    private final NavPrefs st;
    private final int[] barGrad;
    /** لون الدائرة عند كل تبويب (تتدرج بينها أثناء الانزلاق)، ولون الأيقونات. */
    private final int[] circles;
    private final int iconColor;
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);

    NotchBar(Context c, List<Drawable> icons, List<String> names, int active, NavPrefs st, int barBase, int[] circles, int iconColor, OnTab onTab) {
        super(c);
        this.st = st == null ? new NavPrefs() : st;
        this.circles = circles;
        this.iconColor = iconColor;
        // تدرج الشريط من لونه: أغمق في الأعلى وأفتح في الأسفل، بشفافية المستوى المختار
        int base = barBase, al = NavPrefs.ALPHA[this.st.barAlpha];
        boolean lightBar = NavPrefs.light(base);
        int topC = NavPrefs.mix(base, 0xFF000000, lightBar ? 0.04f : 0.42f), botC = NavPrefs.mix(base, 0xFFFFFFFF, lightBar ? 0f : 0.35f);
        barGrad = new int[]{withAlpha(topC, al + 30), withAlpha(base, al + 15), withAlpha(botC, al)};
        this.icons = icons;
        this.names = names;
        this.onTab = onTab;
        this.dp = c.getResources().getDisplayMetrics().density;
        target = active;
        boolean first = lastPos < 0 || lastPos >= icons.size();
        pos = first || Ui.NO_MOTION ? active : lastPos;
        lastPos = active;
        if (first && !Ui.NO_MOTION) {
            lift = 0f;
            post(this::rise);
        }
        setLayerType(LAYER_TYPE_SOFTWARE, null); // للتوهج والظل الناعم
        setClickable(true);
        setContentDescription(names.get(active));
        barStroke.setStyle(Paint.Style.STROKE);
        barStroke.setStrokeWidth(1.2f * dp);
        barStroke.setColor(lightBar ? Color.argb(90, 22, 50, 92) : Color.argb(150, 236, 226, 214));
        shadow.setColor(Color.argb(45, 0, 0, 0));
        shadow.setMaskFilter(new BlurMaskFilter(12 * dp, BlurMaskFilter.Blur.NORMAL));
        glow.setMaskFilter(new BlurMaskFilter(9 * dp, BlurMaskFilter.Blur.NORMAL));
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(1f * dp);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTextSize(9.5f * dp * (0.6f + 0.4f * z()));
        label.setFakeBoldText(true);
        for (int i = 0; i < icons.size(); i++) icons.set(i, icons.get(i).mutate());
        if (pos != active) post(() -> slideTo(active));
    }

    // الهندسة (1.3): الشريط موصول بطرفي الشاشة وأسفلها، بزاويتين علويتين مستديرتين، وارتفاعه 66dp؛
    // والدائرة نصف قطرها 25dp ومركزها على حافة الشريط العليا
    // 1.5: الحجم (صغير/وسط/كبير) والشكل (موصول بالحواف / جزيرة عائمة) من الإعدادات
    private float z() { return SCALE[st.barSize]; }

    static final float[] SCALE = {0.8f, 0.9f, 1f};

    private float barH() { return 66 * dp * z(); }

    private float side() { return st.barFloat ? 12 * dp : 0f; }

    private float barTop() { return barBottom() - barH(); }

    private float barBottom() { return getHeight() - (st.barFloat ? 10 * dp : 0); }

    private float radius() { return 25 * dp * z(); }

    /** ارتفاع الشريط كاملًا بالـ dp (مع بروز الدائرة) لتخطيط الشاشة. */
    static int heightDp(NavPrefs st) {
        float z = SCALE[st.barSize];
        return (int) Math.ceil(66 * z + 25 * z * 1.25f + 14 + (st.barFloat ? 10 : 0));
    }

    /** الجزء الذي يغطي المحتوى (للهامش السفلي). */
    static int coverDp(NavPrefs st) { return (int) Math.ceil(66 * SCALE[st.barSize] + (st.barFloat ? 10 : 0)); }

    private float cellW() { return (getWidth() - 2 * side() - 2 * 12 * dp) / icons.size(); }

    /** مركز التبويب i (من اليمين لأن الواجهة عربية). */
    private float cx(float i) { return getWidth() - side() - 12 * dp - (i + 0.5f) * cellW(); }

    /**
     * الانتقال الانسيابي: تنزلق الدائرة أفقيًا نحو الأيقونة المضغوطة ويرافقها التجويف، وتنخفض قليلًا في منتصف الطريق
     * (تغوص في الشريط) ثم تصعد عند الوصول بارتداد خفيف — فتبدو كأنها تسبح إلى مكانها وتطفو للأعلى.
     */
    void slideTo(final int i) {
        if (anim != null) anim.cancel();
        target = i;
        lastPos = i;
        setContentDescription(names.get(i));
        if (Ui.NO_MOTION) {
            pos = i;
            lift = 1f;
            invalidate();
            return;
        }
        final float from = pos, fromLift = lift;
        final float dist = Math.abs(i - from);
        if (dist < 0.01f && lift > 0.99f) return;
        final float dip = Math.min(0.62f, 0.3f + 0.12f * dist);
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration((long) (480 + 90 * Math.min(4f, dist)));
        a.setInterpolator(new android.view.animation.LinearInterpolator());
        final android.view.animation.Interpolator move = new android.view.animation.PathInterpolator(0.45f, 0.05f, 0.2f, 1f);
        final OvershootInterpolator settle = new OvershootInterpolator(2.2f);
        a.addUpdateListener(x -> {
            float f = (float) x.getAnimatedValue();
            pos = from + (i - from) * move.getInterpolation(f);
            // ينخفض ثم يصعد: 1 ← (1−dip) في المنتصف ← 1 مع ارتداد في آخر الحركة
            float down = (float) Math.sin(Math.PI * Math.min(1f, f / 0.78f));
            float base = fromLift + (1f - fromLift) * f;
            float l = base - dip * Math.max(0f, down);
            if (f > 0.6f) l = Math.min(l, 1f) + (settle.getInterpolation((f - 0.6f) / 0.4f) - (f - 0.6f) / 0.4f) * 0.12f;
            lift = l;
            invalidate();
        });
        anim = a;
        a.start();
    }

    /** صعود الدائرة من داخل الشريط عند التبويب الحالي (أول فتح للتطبيق). */
    private void rise() {
        ValueAnimator up = ValueAnimator.ofFloat(lift, 1f);
        up.setDuration(650);
        up.setInterpolator(new OvershootInterpolator(1.6f));
        up.addUpdateListener(a -> {
            lift = (float) a.getAnimatedValue();
            invalidate();
        });
        anim = up;
        up.start();
    }

    /** لون الدائرة في الموضع p (بين تبويبين أثناء الانزلاق يتدرج). */
    private int circleAt(float p) {
        int n = circles.length;
        if (n == 0) return 0xFF2C7058;
        float q = Math.max(0, Math.min(n - 1, p));
        int i = (int) Math.floor(q), j = Math.min(n - 1, i + 1);
        return NavPrefs.mix(circles[i], circles[j], q - i);
    }

    /** خط التطبيق لأسماء التبويبات. */
    void typeface(android.graphics.Typeface tf) {
        if (tf != null) label.setTypeface(android.graphics.Typeface.create(tf, android.graphics.Typeface.BOLD));
    }

    /** مركز دائرة التبويب i (بإحداثيات الشريط) لتنطلق منه قائمة «+». */
    float[] circleCenter(int i) { return new float[]{cx(i), barTop() + 2 * dp}; }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), top = barTop(), bot = barBottom(), s = side(), rad = radius();
        float x = cx(pos);
        // الشريط: مستطيل مستدير ناقص التجويف (قرب الأطراف يصنع «أذنًا» ناعمة كما في الفيديو)
        round.reset();
        if (st.barFloat) {
            r.set(s, top, w - s, bot);
            float cornerR = barH() / 2f - 2 * dp;
            round.addRoundRect(r, cornerR, cornerR, Path.Direction.CW);
        } else {
            r.set(s - 2 * dp, top, w - s + 2 * dp, bot + 30 * dp);
            float cornerR = 28 * dp * z();
            round.addRoundRect(r, new float[]{cornerR, cornerR, cornerR, cornerR, 0, 0, 0, 0}, Path.Direction.CW);
        }
        float L = Math.max(0f, lift), Lc = Math.min(1f, L);
        float hw = (rad + 20 * dp * z()) * (0.35f + 0.65f * Lc), depth = (rad + 7 * dp * z()) * L;
        cut.reset();
        cut.moveTo(x - hw, top - 60 * dp);
        cut.lineTo(x - hw, top - 0.5f * dp);
        cut.cubicTo(x - hw * 0.45f, top - 0.5f * dp, x - rad * 1.12f, top + depth, x, top + depth);
        cut.cubicTo(x + rad * 1.12f, top + depth, x + hw * 0.45f, top - 0.5f * dp, x + hw, top - 0.5f * dp);
        cut.lineTo(x + hw, top - 60 * dp);
        cut.close();
        barPath.reset();
        if (depth > 0.5f * dp) barPath.op(round, cut, Path.Op.DIFFERENCE);
        else barPath.set(round);

        c.save();
        c.translate(0, 4 * dp);
        c.drawPath(barPath, shadow);
        c.restore();
        // زجاج داكن: أغمق في الأعلى وأفتح (بني رمادي) في الأسفل
        barFill.setShader(new LinearGradient(0, top, 0, bot, barGrad,
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawPath(barPath, barFill);
        // لمعة زجاجية خفيفة في النصف العلوي
        sheen.setShader(new LinearGradient(0, top, 0, top + 30 * dp, Color.argb(46, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP));
        c.drawPath(barPath, sheen);
        c.drawPath(barPath, barStroke);

        // الأيقونات: تختفي أيقونة التبويب الذي تقف عليه الدائرة وتظهر داخلها
        int is = (int) (24 * dp * (0.5f + 0.5f * z()));
        float cyBar = top + barH() / 2f - 2 * dp * z();
        // 10.15: مع الأسماء ترتفع الأيقونات قليلًا وتحتها الاسم
        float iconY = st.labels ? top + barH() * 0.40f : cyBar;
        int ink = iconColor;
        for (int i = 0; i < icons.size(); i++) {
            float d = Math.abs(pos - i);
            int alpha = d > 0.5f ? 255 : (int) (255 * Math.min(1f, Math.max(0f, 1f - Lc * 1.6f)));
            if (alpha <= 0) continue;
            Drawable ic = icons.get(i);
            float ix = cx(i);
            ic.setBounds((int) (ix - is / 2f), (int) (iconY - is / 2f), (int) (ix + is / 2f), (int) (iconY + is / 2f));
            ic.setTint(ink);
            ic.setAlpha(alpha);
            ic.draw(c);
            if (st.labels) {
                label.setColor(ink);
                label.setAlpha((int) (alpha * 0.92f));
                c.drawText(names.get(i), ix, top + barH() * 0.80f, label);
            }
        }
        // الدائرة الخضراء البارزة مع توهج
        if (L < 0.05f) return;
        float cy = cyBar + (top + 2 * dp - cyBar) * L;
        int cc = circleAt(pos);
        glow.setColor(withAlpha(NavPrefs.mix(cc, 0xFFFFFFFF, 0.3f), 150));
        ring.setColor(withAlpha(NavPrefs.mix(cc, 0xFFFFFFFF, 0.55f), 110));
        float cr = rad * (0.55f + 0.45f * Lc);
        c.save();
        // الدائرة وهي داخل الشريط تُقصّ عند حافته السفلية فتبدو صاعدة من داخله
        c.clipRect(0, 0, w, bot - 1 * dp);
        glow.setAlpha((int) (150 * Lc));
        c.drawCircle(x, cy + 3 * dp, cr * 0.95f, glow);
        circle.setShader(new RadialGradient(x - rad * 0.3f, cy - rad * 0.45f, rad * 1.6f, new int[]{NavPrefs.mix(cc, 0xFFFFFFFF, 0.25f), cc, NavPrefs.mix(cc, 0xFF000000, 0.4f)},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(x, cy, cr, circle);
        c.drawCircle(x, cy, cr - 0.5f * dp, ring);
        int sel = Math.round(Math.max(0, Math.min(icons.size() - 1, pos)));
        Drawable ic = icons.get(sel);
        int cs = (int) (25 * dp * z() * (0.6f + 0.4f * Lc));
        ic.setBounds((int) (x - cs / 2f), (int) (cy - cs / 2f), (int) (x + cs / 2f), (int) (cy + cs / 2f));
        ic.setTint(NavPrefs.light(cc) ? 0xFF16325C : 0xFFFFFFFF);
        ic.setAlpha((int) (255 * Lc));
        ic.draw(c);
        ic.setAlpha(255);
        c.restore();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getY() < barTop() - radius() - 6 * dp) return false;
        if (e.getAction() == MotionEvent.ACTION_UP) {
            float x = e.getX();
            int best = 0;
            float bd = Float.MAX_VALUE;
            for (int i = 0; i < icons.size(); i++) {
                float d = Math.abs(cx(i) - x);
                if (d < bd) {
                    bd = d;
                    best = i;
                }
            }
            if (getContext() instanceof Ui) ((Ui) getContext()).tick(this);
            // الشاشة الجديدة تبني شريطها وتحرّك الدائرة من التبويب السابق (lastPos)
            onTab.picked(best);
            return true;
        }
        return true;
    }

    int target() { return target; }

    static int withAlpha(int c, int a) { return (Math.max(0, Math.min(255, a)) << 24) | (c & 0xFFFFFF); }

    /** لون شريط النظام تحت الشريط: لون أسفل الزجاج فوق خلفية الشاشة، فيبدوان قطعة واحدة. */
    static int navColor(int bg, NavPrefs st, int base) {
        if (st.barFloat) return bg;
        int al = NavPrefs.ALPHA[st.barAlpha];
        int botC = NavPrefs.light(base) ? base : NavPrefs.mix(base, 0xFFFFFFFF, 0.35f);
        return NavPrefs.mix(bg, botC, al / 255f);
    }
}
