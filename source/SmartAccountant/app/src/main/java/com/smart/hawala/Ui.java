package com.smart.hawala;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * أدوات الواجهة المشتركة لكل الشاشات: الألوان (فاتح/ليلي)، الخط، الشريط العلوي، شريط التبويبات السفلي،
 * الشريط الثابت أسفل النماذج، العناصر، الأزرار المجزأة، العمل في الخلفية، والمشاركة.
 */
public abstract class Ui extends Activity {

    Db db;
    LinearLayout body;
    /** ألوان الهوية (قابلة للتخصيص) + ألوان دلالية ثابتة. dark = لون النص الأساسي (يبقى الاسم للتوافق). */
    int primary, accent, header, bg, red, dark, green;
    int surface, line, ink, muted, creditBg, debitBg, warnBg, warnInk, primarySoft, primaryInk, goldSoft, goldInk, headerInk;
    boolean night;
    /** 10.9: خلفية أزرار الاختيار المجزأة، وحدّ البطاقات (لا حدّ في الليل). */
    int segTrack, cardStroke;
    /** تحت اختبارات Robolectric: بلا حركات (اللقطات تُرسم فورًا). */
    static final boolean NO_MOTION = "robolectric".equals(android.os.Build.FINGERPRINT);
    /** 11.0: الشاشات نفسها تعمل على الماك والويندوز (طبقة أندرويد فوق Swing). */
    static final boolean DESKTOP = android.os.Build.FINGERPRINT.startsWith("desktop");
    /** 11.0: قسم «نافذة الكمبيوتر» في شاشة المظهر؛ يضيفه برنامج الكمبيوتر وحده (لا شيء على الهاتف). */
    static java.util.function.Consumer<MainActivity> deskDesign;
    public static void deskDesignHook(java.util.function.Consumer<MainActivity> c) { deskDesign = c; }
    /** الشريط العلوي بلون الخلفية (الافتراضي) أو ملوّن إذا اختار المستخدم «لون الشريط العلوي». */
    boolean flatHeader = true;
    boolean onHome;
    Runnable back;
    boolean ownActivity;
    /** 11.5: متى فُتحت شاشة من التطبيق نفسه (منتقي ملفات، مشاركة…). لا يُعفى من القفل إلا توقف يأتي بعدها مباشرة. */
    long ownAt;
    boolean ownPause;

    void markOwn() {
        ownActivity = true;
        ownAt = android.os.SystemClock.elapsedRealtime();
    }
    boolean busyRunning;

    FrameLayout frame;
    LinearLayout column, bar, barActions, footerBox;
    ScrollView scroll;
    View tabs;
    TextView screenTitle;

    interface Work { Object run(BackupManager.Progress p) throws Exception; }

    interface Done { void ok(Object result); }

    // ===================================================================================
    // الألوان والسمة
    // ===================================================================================

    static int parseColor(String s, int def) {
        try {
            if (s == null || s.trim().isEmpty()) return def;
            return Color.parseColor(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    static int darker(int c, float f) {
        return Color.rgb((int) (Color.red(c) * f), (int) (Color.green(c) * f), (int) (Color.blue(c) * f));
    }

    static int mix(int a, int b, float t) {
        return Color.rgb((int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t + .5f),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t + .5f),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t + .5f));
    }

    static double lum(int c) {
        return (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0;
    }

    static boolean isLight(int c) { return lum(c) > 0.62; }

    static int onColor(int c) { return isLight(c) ? Color.rgb(29, 42, 38) : Color.WHITE; }

    static boolean isGray(int c) {
        int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
        return Math.abs(r - g) < 14 && Math.abs(g - b) < 14 && r >= 80 && r <= 175;
    }

    static String argb(int c) { return String.format(Locale.US, "FF%02X%02X%02X", Color.red(c), Color.green(c), Color.blue(c)); }

    /** وضع السمة المحفوظ: light / dark / auto. */
    static boolean wantsNight(Db db, Configuration cfg) {
        String m = db == null ? "light" : db.setting("theme_mode", "light");
        if ("dark".equals(m)) return true;
        if ("auto".equals(m)) return (cfg.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        return false;
    }

    static int fontStyle(Db db) {
        String f = db == null ? "plex" : db.setting("app_font", "plex");
        switch (f) {
            case "cairo": return R.style.FontCairo;
            case "kufi": return R.style.FontKufi;
            case "system": return R.style.FontSystem;
            default: return R.style.FontPlex;
        }
    }

    /** تُستدعى مرة عند إنشاء الشاشة، قبل أي عنصر: تختار السمة الفاتحة أو الليلية وتطبّق الخط المختار. */
    /** 10.12: بصمة السمة المطبّقة (الوضع والخط) — إن اختلفت لمستخدم دخل للتو تُعاد الشاشة بسمته. */
    String appliedTheme = "";

    String themeSig() {
        return db == null ? "" : db.setting("theme_mode", "light") + "|" + db.setting("app_font", "plex");
    }

    void applyTheme() {
        appliedTheme = themeSig();
        night = wantsNight(db, getResources().getConfiguration());
        setTheme(night ? R.style.AppThemeDark : R.style.AppTheme);
        getTheme().applyStyle(fontStyle(db), true);
    }

    void applyColors() {
        int defPrimary = Color.rgb(11, 107, 83);
        int userPrimary = parseColor(db == null ? "" : db.setting("primary_color", "#0B6B53"), defPrimary);
        accent = parseColor(db == null ? "" : db.setting("accent_color", "#C7922F"), Color.rgb(199, 146, 47));
        String hc = db == null ? "" : db.setting("header_color", "").trim();
        // 10.9: سمة عصرية (نهاري وليلي) — أسطح بلا حدود ثقيلة، وألوان أنقى، ونعناعي للأخضر في الليل
        if (!night) {
            primary = userPrimary;
            bg = parseColor(db == null ? "" : db.setting("bg_color", "#F3F5F4"), Color.rgb(243, 245, 244));
            surface = Color.WHITE;
            line = Color.rgb(226, 232, 229);
            ink = Color.rgb(15, 26, 22);
            muted = Color.rgb(94, 109, 103);
            red = Color.rgb(200, 52, 60);
            green = Color.rgb(22, 128, 82);
            creditBg = Color.rgb(228, 244, 236);
            debitBg = Color.rgb(252, 232, 233);
            warnBg = Color.rgb(255, 242, 214);
            warnInk = Color.rgb(110, 67, 0);
            primarySoft = mix(Color.WHITE, primary, 0.09f);
            primaryInk = darker(primary, 0.76f);
            goldSoft = mix(Color.WHITE, accent, 0.16f);
            goldInk = darker(accent, 0.58f);
        } else {
            // الوضع الليلي: الخلفية والأسطح ثابتة، والأخضر الأساسي يبقى للأزرار مع نسخة فاتحة للنصوص
            primary = userPrimary == defPrimary ? Color.rgb(61, 220, 151) : userPrimary;
            // 11.3: خلفية الليل قابلة للاختيار؛ الافتراضي: المخضرّة الأصلية مع اللون الأخضر الافتراضي، ورمادية محايدة مع أي لون آخر
            String nb = db == null ? "" : db.setting("night_bg", "").trim();
            boolean classicNight = nb.isEmpty() && userPrimary == defPrimary;
            int nbg = classicNight ? Color.rgb(11, 20, 17) : parseColor(nb, Color.rgb(17, 19, 22));
            bg = nbg;
            surface = classicNight ? Color.rgb(19, 32, 27) : mix(nbg, Color.WHITE, 0.05f);
            line = classicNight ? Color.rgb(36, 55, 47) : mix(nbg, Color.WHITE, 0.12f);
            ink = classicNight ? Color.rgb(236, 243, 239) : Color.rgb(236, 239, 242);
            muted = classicNight ? Color.rgb(141, 163, 154) : mix(nbg, Color.WHITE, 0.58f);
            red = Color.rgb(255, 120, 120);
            green = Color.rgb(61, 220, 151);
            creditBg = Color.rgb(20, 52, 40);
            debitBg = Color.rgb(58, 28, 31);
            warnBg = Color.rgb(58, 46, 18);
            warnInk = Color.rgb(243, 208, 138);
            // 11.3: لون النصوص الأساسي من اللون المختار (كان نعناعيًا أخضر مع أي لون)
            primarySoft = classicNight ? Color.rgb(22, 52, 41) : mix(surface, userPrimary, 0.28f);
            primaryInk = userPrimary == defPrimary ? Color.rgb(61, 220, 151) : readableOnDark(userPrimary);
            goldSoft = Color.rgb(58, 46, 18);
            goldInk = Color.rgb(233, 196, 120);
        }
        dark = ink;
        segTrack = mix(surface, line, night ? 0.55f : 0.6f);
        // 10.13: «إطارات الأزرار والبطاقات» خيار لكل مستخدم (الافتراضي: ظاهرة)
        frames = db == null || !"off".equals(db.setting("btn_frames", "on"));
        cardStroke = frames ? (night ? mix(surface, line, 0.9f) : line) : (night ? surface : mix(surface, line, 0.75f));
        flatHeader = hc.isEmpty();
        header = flatHeader ? bg : parseColor(hc, darker(primary, 0.8f));
        headerInk = flatHeader ? ink : onColor(header);
        systemBars(header, surface);
    }

    /** لون شريط الحالة وشريط التنقل، مع أيقونات داكنة فوق الألوان الفاتحة. */
    void systemBars(int status, int nav) {
        try {
            Window w = getWindow();
            w.setStatusBarColor(status);
            w.setNavigationBarColor(nav);
            View d = w.getDecorView();
            int f = d.getSystemUiVisibility();
            f = isLight(status) ? f | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : f & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            f = isLight(nav) ? f | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : f & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            d.setSystemUiVisibility(f);
        } catch (Throwable ignored) {
            // غير مهم
        }
    }

    /** في الوضع الليلي: يفتّح ألوان النصوص الداكنة حتى تُقرأ فوق الخلفية الداكنة. */
    int readable(int c) {
        if (!night || Color.alpha(c) < 255) return c;
        double l = lum(c);
        if (l >= 0.55) return c;
        return mix(c, Color.WHITE, (float) Math.min(0.75, 0.62 - l));
    }

    /** في الوضع الليلي: الخلفيات الفاتحة تتحول إلى درجة داكنة من اللون نفسه (أحمر فاتح ← أحمر داكن...). */
    int fillFor(int c) {
        if (!night || Color.alpha(c) < 255 || lum(c) < 0.7) return c;
        int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
        int m = Math.min(r, Math.min(g, b));
        float k = 1.7f;
        return Color.rgb(Math.min(255, Color.red(surface) + (int) ((r - m) * k)),
                Math.min(255, Color.green(surface) + (int) ((g - m) * k)),
                Math.min(255, Color.blue(surface) + (int) ((b - m) * k)));
    }

    int strokeFor(int c) {
        if (!night || Color.alpha(c) < 255) return c;
        return lum(c) > 0.55 ? (lum(c) > 0.8 ? line : mix(line, c, 0.35f)) : c;
    }

    // ===================================================================================
    // البنية: شريط علوي + محتوى قابل للتمرير + شريط ثابت (اختياري) + تبويبات (اختياري)
    // ===================================================================================

    // ===================================================================================
    // 11.5: سجل التنقل: الرجوع إلى الصفحة السابقة خطوة خطوة حتى الرئيسية (زر الرجوع، رجوع النظام، Esc، السحب)
    // ===================================================================================

    /** الصفحات السابقة (كيف تُفتح كل واحدة من جديد)، الأحدث في الآخر. */
    final ArrayList<Object[]> history = new ArrayList<>();
    /** كيف تُفتح الصفحة الحالية من جديد (null = لا تُحفظ في السجل: نماذج الإدخال والقفل). */
    private Runnable here;
    private String hereTitle;
    private Runnable pendingHere;
    /** الصفحة الجاري فتحها رجوعًا (فلا تُضاف الحالية إلى السجل). */
    private boolean navBack;

    /** تسجّل الصفحة التي ستُرسم الآن كيف تُفتح من جديد (يُستدعى قبل base/tabbedScreen). */
    void here(Runnable reopen) { pendingHere = reopen; }

    /** يرجع إلى الصفحة السابقة في السجل؛ false إن لم يبق شيء. */
    boolean historyBack() {
        if (!showHomeButton()) {
            history.clear();
            return false;
        }
        while (!history.isEmpty()) {
            Object[] e = history.remove(history.size() - 1);
            Runnable r = (Runnable) e[1];
            if (r == null) continue;
            // يبقى العلم حتى تُرسم الصفحة فعلًا (بعض الصفحات تُبنى بعد انتظار، مثل التقارير)
            navBack = true;
            returning = true;
            r.run();
            return true;
        }
        return false;
    }

    /** يشغّل رجوعًا محددًا دون أن تُضاف الصفحة الحالية إلى السجل. */
    void runAsBack(Runnable r) {
        navBack = true;
        r.run();
    }

    /** هل للرجوع وجهة (سجل أو رجوع محدد)؟ */
    boolean canGoBack() { return !history.isEmpty() || back != null; }

    private void trackHistory(String title, String tab, Runnable backAction) {
        Runnable next = pendingHere;
        pendingHere = null;
        boolean same = title.equals(hereTitle);
        if (!showHomeButton()) {
            // 11.5: شاشات القفل والدخول: لا سجل رجوع (لا سهم ولا سحب يعيد صفحة من خلف القفل)
            history.clear();
            here = null;
            hereTitle = title;
            navBack = false;
            return;
        }
        if (tab != null && backAction == null) {
            // صفحة رئيسية من التبويبات: بداية جديدة للسجل
            history.clear();
        } else if (!navBack && !same && here != null) {
            history.add(new Object[]{hereTitle, here});
            if (history.size() > 40) history.remove(0);
        }
        if (same && next == null) next = here;   // إعادة رسم الصفحة نفسها
        here = next;
        hereTitle = title;
        navBack = false;
    }

    /** يرسم شاشة جديدة بشريط عنوان. backAction: ما يفعله زر الرجوع (null = الرئيسية). */
    void base(String title, Runnable backAction) { build(title, backAction, null); }

    /** شاشة رئيسية من شاشات التبويبات (الرئيسية، المكاتب، الحركات، المزيد): بلا رجوع ومع شريط التبويبات. */
    void rootScreen(String title, String tab) { build(title, null, tab); }

    /** شاشة فرعية داخل تبويب (صفحة المكتب مثلًا): رجوع + شريط التبويبات. */
    void tabbedScreen(String title, Runnable backAction, String tab) { build(title, backAction, tab); }

    /** 9.0: شاشات لها ألوانها الخاصة (صندوق المكتب): تضبط ألوانها وتطلب الإبقاء عليها؛ أي شاشة أخرى تعيد ألوان التطبيق. */
    String themeOwner;
    boolean themeKeep;

    /** 9.0.1: يُستدعى عند مغادرة شاشات لها ألوانها الخاصة (يُقفل الصندوق مثلًا). */
    void themeLeft(String owner) {}

    /**
     * 10.5 (§6): موضع التمرير لكل شاشة (بعنوانها)، يُستعاد عند الرجوع إليها (زر الرجوع، أو إعادة رسمها بعد عملية، أو العودة
     * من تفاصيل). البحث والفلاتر والتبويب محفوظة في حقول كل شاشة أصلًا. المواضع تنتهي بعد 20 دقيقة.
     */
    private final java.util.Map<String, long[]> scrollMemory = new java.util.HashMap<>();
    /** الشاشة التالية تُفتح رجوعًا (فيُستعاد موضعها). */
    boolean returning;

    private void rememberScroll() {
        if (screenTitle == null || scroll == null) return;
        scrollMemory.put(screenTitle.getText().toString(), new long[]{scroll.getScrollY(), Clock.now()});
    }

    private void restoreScroll(final String title, boolean sameScreen) {
        final long[] m = scrollMemory.get(title);
        if (m == null || m[0] <= 0 || Clock.now() - m[1] > 20 * 60_000L) return;
        if (!sameScreen && !returning) return;
        final ScrollView sv = scroll;
        Runnable go = () -> { if (sv == scroll) sv.scrollTo(0, (int) m[0]); };
        sv.post(go);
        sv.postDelayed(go, 150);
    }

    private void build(String title, Runnable backAction, String tab) {
        String prevTitle = screenTitle == null ? null : screenTitle.getText().toString();
        rememberScroll();
        if (themeOwner != null && !themeKeep) {
            String left = themeOwner;
            themeOwner = null;
            applyColors();
            themeLeft(left);
        }
        themeKeep = false;
        beforeScreenChange();
        trackHistory(title, tab, backAction);
        onHome = false;
        back = backAction;
        frame = new FrameLayout(this);
        frame.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        frame.setBackgroundColor(bg);
        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        // القص مفعّل هنا عمدًا: بدونه يُرسم محتوى التمرير فوق الشريط العلوي
        frame.addView(column, new FrameLayout.LayoutParams(-1, -1));

        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(header);
        bar.setPadding(dp(8), 0, dp(8), 0);
        // 11.5: سهم رجوع صغير بجانب العنوان في كل صفحة لها ما قبلها (السجل أو رجوع محدد)
        if (backAction != null || !history.isEmpty()) {
            View bk = iconButton(R.drawable.ic_back, headerInk, "رجوع", v -> onBackPressed());
            bk.setPadding(dp(8), dp(8), dp(8), dp(8));
            bar.addView(bk, new LinearLayout.LayoutParams(dp(40), dp(40)));
        } else bar.addView(new View(this), new LinearLayout.LayoutParams(dp(8), 1));
        boolean big = tab != null && backAction == null;
        screenTitle = text(title, big ? 22 : 19, headerInk, true);
        screenTitle.setSingleLine(true);
        screenTitle.setEllipsize(TextUtils.TruncateAt.END);
        screenTitle.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        bar.addView(screenTitle, new LinearLayout.LayoutParams(0, -1, 1));
        barActions = row();
        bar.addView(barActions, new LinearLayout.LayoutParams(-2, -1));
        if (!title.equals(prevTitle)) headerOpen = false;
        // 10.12: ثلاث أيقونات أو أكثر تُطوى خلف أيقونة واحدة (بعد أن تضيف الشاشة أيقوناتها)
        final LinearLayout acts = barActions;
        acts.post(() -> foldActions(acts));
        rootTab = tab != null && backAction == null ? tab : null;
        globalActions();
        if (tab == null && showHomeButton()) {
            bar.addView(iconButton(R.drawable.ic_home, headerInk, "الرئيسية", v -> goHome()), new LinearLayout.LayoutParams(dp(44), dp(44)));
        }
        column.addView(bar, new LinearLayout.LayoutParams(-1, dp(big ? 64 : 58)));

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        // 10.13: على التابلت والوضع العرضي: المحتوى بعرض مريح في الوسط لا ممدودًا على الشاشة كلها
        int side = dp(16);
        int sw = getResources().getConfiguration().screenWidthDp;
        if (sw >= 720) side = Math.max(dp(24), dp((sw - Math.min(sw - 48, 1180)) / 2));
        body.setPadding(side, dp(4), side, dp(tab != null ? 48 : 40));
        scroll.addView(body);
        column.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        footerBox = new LinearLayout(this);
        footerBox.setOrientation(LinearLayout.VERTICAL);
        footerBox.setVisibility(View.GONE);
        column.addView(footerBox, new LinearLayout.LayoutParams(-1, -2));

        tabs = null;
        tabCover = 0;
        hideWithKeyboard = null;
        if (tab != null) tabBar(tab);
        systemBars(header, navBarColor());
        setContentView(frame);
        watchKeyboard();
        restoreScroll(title, title.equals(prevTitle));
        // 10.10: عناصر الشاشة الجديدة تصعد واحدًا بعد الآخر (لا عند إعادة رسم الشاشة نفسها)
        String pm = pageMotion();
        if (!NO_MOTION && !title.equals(prevTitle) && !returning && "whole".equals(pm)) {
            // 10.13: الشاشة كلها دفعة واحدة (تظهر وتصعد قليلًا معًا)
            body.setAlpha(0f);
            body.setTranslationY(dp(14));
            body.animate().alpha(1f).translationY(0).setDuration(380).setInterpolator(new android.view.animation.DecelerateInterpolator(2f)).start();
        } else if (!NO_MOTION && !title.equals(prevTitle) && !returning && !"none".equals(pm)) {
            final LinearLayout bd = body;
            bd.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                @Override public boolean onPreDraw() {
                    bd.getViewTreeObserver().removeOnPreDrawListener(this);
                    int n = bd.getChildCount();
                    for (int i = 0; i < n; i++) {
                        View c = bd.getChildAt(i);
                        c.setAlpha(0f);
                        c.setTranslationY(dp(26));
                        c.animate().alpha(1f).translationY(0).setStartDelay(Math.min(i, 10) * 70L).setDuration(560)
                                .setInterpolator(new android.view.animation.DecelerateInterpolator(2.2f)).start();
                    }
                    return true;
                }
            });
        }
        returning = false;
    }

    /** 10.13: شاشة عريضة (تابلت، أو هاتف بالعرض): النصفان متقابلان بدل التمرير. */
    boolean wide() { return getResources().getConfiguration().screenWidthDp >= 720; }

    /**
     * 10.13: على الشاشة العريضة يصير محتوى الشاشة عمودين متقابلين: ما قبل at في العمود الأول (يمين) وما بعده في الثاني.
     * على الهاتف بالطول لا يتغير شيء. ما يُضاف لاحقًا في أعلى الشاشة (رأس واتساب مثلًا) يبقى بعرضها كله.
     */
    void splitBody(View at) {
        if (!wide() || at == null) return;
        int k = body.indexOfChild(at);
        int n = body.getChildCount();
        if (k <= 0 || k >= n) return;
        List<View> all = new ArrayList<>();
        for (int i = 0; i < n; i++) all.add(body.getChildAt(i));
        body.removeAllViews();
        LinearLayout row = row();
        row.setBaselineAligned(false);
        row.setGravity(Gravity.TOP);
        LinearLayout c1 = new LinearLayout(this), c2 = new LinearLayout(this);
        c1.setOrientation(LinearLayout.VERTICAL);
        c2.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < n; i++) {
            View v = all.get(i);
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            (i < k ? c1 : c2).addView(v, lp != null ? lp : new LinearLayout.LayoutParams(-1, -2));
        }
        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(0, -2, 1), p2 = new LinearLayout.LayoutParams(0, -2, 1);
        p1.setMarginEnd(dp(10));
        p2.setMarginStart(dp(10));
        row.addView(c1, p1);
        row.addView(c2, p2);
        body.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    /** 10.13: يقسم الشاشة العريضة عمودين عند منتصف عناصرها (للقوائم والبطاقات). */
    void splitBodyHalf(int skipTop) {
        int n = body.getChildCount();
        if (!wide() || n - skipTop < 4) return;
        splitBody(body.getChildAt(skipTop + (n - skipTop + 1) / 2));
    }

    /** 10.1: تبويب الشاشة الجذرية الحالية (null لشاشة فرعية): يُعاد رسمها عند وصول تحديثات. */
    String rootTab;

    /** 10.1: أيقونات تظهر في كل شاشة (زر المزامنة السريع). */
    void globalActions() {}

    /** يُستدعى قبل تغيير الشاشة (حفظ المسودة مثلًا). */
    void beforeScreenChange() {}

    boolean showHomeButton() { return true; }

    abstract void goHome();

    /** فتح تبويب من الشريط السفلي: home / offices / tx / log / more. */
    void openTab(String key) {}

    /** 10.12: هل أيقونات الشريط العلوي مفتوحة (تبقى كما هي عند إعادة رسم الشاشة نفسها). */
    static boolean headerOpen;

    /**
     * 10.12: «أكورديون» الشريط العلوي: إن كانت في الشريط ثلاث أيقونات أو أكثر (الوضع الليلي، المستخدم، المزامنة، الإخفاء…)
     * تبقى أيقونة واحدة، والضغط عليها يُظهر الباقي على يمينها واحدةً بعد الأخرى، والضغط ثانيةً يطويها.
     */
    void foldActions(final LinearLayout acts) {
        if (acts != barActions || acts.getChildCount() < 3) return;
        final List<View> items = new ArrayList<>();
        for (int i = 0; i < acts.getChildCount(); i++) items.add(acts.getChildAt(i));
        acts.removeAllViews();
        // 10.13: كبسولة ناعمة تحمل الأيقونات، وزر صغير أنيق (32dp) بلون التطبيق الخفيف
        final LinearLayout tray = row();
        tray.setClipChildren(false);
        tray.setGravity(Gravity.CENTER_VERTICAL);
        tray.setPadding(dp(2), 0, dp(2), 0);
        tray.setBackground(round(mix(header, headerInk, night ? 0.08f : 0.05f), mix(header, headerInk, night ? 0.08f : 0.05f), 22));
        for (View v : items) tray.addView(v, new LinearLayout.LayoutParams(dp(38), dp(38)));
        tray.setVisibility(headerOpen ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, dp(42));
        tp.gravity = Gravity.CENTER_VERTICAL;
        tp.setMargins(0, 0, dp(4), 0);
        acts.setGravity(Gravity.CENTER_VERTICAL);
        acts.addView(tray, tp);
        final FrameLayout toggle = new FrameLayout(this);
        toggle.addView(icon(R.drawable.ic_dots, primaryInk, 18), new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER));
        toggle.setContentDescription("أدوات الشاشة");
        springy(toggle, 0.85f);
        toggle.setRotation(headerOpen ? 90f : 0f);
        GradientDrawable ring = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{mix(primarySoft, Color.WHITE, night ? 0f : 0.35f), primarySoft});
        ring.setShape(GradientDrawable.OVAL);
        ring.setStroke(dp(1), mix(primarySoft, primaryInk, 0.25f));
        toggle.setBackground(ripple(ring, 16));
        toggle.setOnClickListener(v -> {
            tick(v);
            headerOpen = !headerOpen;
            toggle.animate().rotation(headerOpen ? 90f : 0f).setDuration(NO_MOTION ? 0 : 380)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(1.4f)).start();
            int n = tray.getChildCount();
            if (headerOpen) {
                tray.setVisibility(View.VISIBLE);
                for (int i = 0; i < n; i++) {
                    View c = tray.getChildAt(i);
                    if (NO_MOTION) continue;
                    // من جهة الأيقونة إلى مكانها: أقربها إليها أولًا
                    int k = n - 1 - i;
                    c.setAlpha(0f);
                    c.setTranslationX(-dp(18));
                    c.setScaleX(0.6f);
                    c.setScaleY(0.6f);
                    c.animate().alpha(1f).translationX(0).scaleX(1f).scaleY(1f).setStartDelay(k * 60L).setDuration(380)
                            .setInterpolator(new android.view.animation.OvershootInterpolator(1.3f)).start();
                }
            } else if (NO_MOTION) {
                tray.setVisibility(View.GONE);
            } else {
                for (int i = 0; i < n; i++) {
                    View c = tray.getChildAt(i);
                    c.animate().alpha(0f).translationX(-dp(18)).scaleX(0.6f).scaleY(0.6f).setStartDelay(i * 40L).setDuration(240).start();
                }
                tray.postDelayed(() -> {
                    if (!headerOpen) tray.setVisibility(View.GONE);
                    for (int i = 0; i < tray.getChildCount(); i++) {
                        View c = tray.getChildAt(i);
                        c.setAlpha(1f);
                        c.setTranslationX(0);
                        c.setScaleX(1f);
                        c.setScaleY(1f);
                    }
                }, 240 + n * 40L);
            }
        });
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(32), dp(32));
        gp.gravity = Gravity.CENTER_VERTICAL;
        gp.setMargins(dp(2), 0, dp(4), 0);
        acts.addView(toggle, gp);
    }

    /** أيقونة إضافية في الشريط العلوي (بحث، إضافة...). */
    void headerAction(int icon, String desc, View.OnClickListener l) {
        barActions.addView(iconButton(icon, headerInk, desc, l), new LinearLayout.LayoutParams(dp(44), dp(44)));
    }

    /** شريط ثابت أسفل الشاشة فوق لوحة المفاتيح (ملخص الحركة وزر التسجيل مثلًا). */
    void footer(View v) {
        footerBox.removeAllViews();
        footerBox.addView(v, new LinearLayout.LayoutParams(-1, -2));
        footerBox.setBackground(topLine(surface));
        footerBox.setPadding(dp(16), dp(8), dp(16), dp(10) + (tabs != null && tabs.getVisibility() == View.VISIBLE ? tabCover : 0));
        footerBox.setVisibility(View.VISIBLE);
        body.setPadding(body.getPaddingLeft(), body.getPaddingTop(), body.getPaddingRight(), dp(24));
        systemBars(header, surface);
    }

    /** خلفية بسطر رفيع في أعلاها (للشريط الثابت والتبويبات). */
    Drawable topLine(int fill) {
        GradientDrawable l = new GradientDrawable();
        l.setColor(line);
        GradientDrawable f = new GradientDrawable();
        f.setColor(fill);
        LayerDrawable ld = new LayerDrawable(new Drawable[]{l, f});
        ld.setLayerInset(1, 0, dp(1), 0, 0);
        return ld;
    }

    // ===================================================================================
    // شريط التبويبات السفلي
    // ===================================================================================

    // 10.2: الشريط السفلي قابل للتخصيص (ماذا يظهر وبأي ترتيب). الافتراضي: الصندوق في أقصى اليسار بلون مميز.
    static final String[] TAB_KEYS = {"home", "offices", "tx", "log", "more", "cash"};
    /** كل ما يمكن وضعه في الشريط: [المفتاح، الاسم]. */
    static final String[][] TAB_ALL = {{"home", "الرئيسية"}, {"offices", "المكاتب"}, {"tx", "تسجيل"}, {"log", "الحركات"}, {"cash", "الصندوق"},
            {"clients", "العملاء"}, {"mine", "مكاتبي"}, {"reports", "التقارير"}, {"balances", "الأرصدة"}, {"daily", "اليومي"}, {"more", "المزيد"}};

    static String tabName(String k) {
        for (String[] t : TAB_ALL) if (t[0].equals(k)) return t[1];
        return k;
    }

    static int tabIcon(String k) {
        switch (k) {
            case "home": return R.drawable.ic_home;
            case "offices": return R.drawable.ic_offices;
            case "tx": return R.drawable.ic_plus;
            case "log": return R.drawable.ic_list;
            case "cash": return R.drawable.ic_cash;
            case "clients": return R.drawable.ic_users;
            case "mine": return R.drawable.ic_check2;
            case "reports": return R.drawable.ic_report;
            case "balances": return R.drawable.ic_scale;
            case "daily": return R.drawable.ic_calendar;
            default: return R.drawable.ic_more;
        }
    }

    /** التبويبات الظاهرة لهذا المستخدم (يحددها MainActivity: الترتيب المحفوظ مع إخفاء ما لا يملك صلاحيته). */
    java.util.List<String> tabKeys() { return java.util.Arrays.asList(TAB_KEYS); }

    /** 10.10: آخر تبويب كان مختارًا: إطار الشريط ينزلق منه إلى التبويب الجديد. */
    static String lastTab;
    /** ارتفاع إطار الشريط (الجزيرة مع الزر البارز فوقها). */
    static final int TAB_FRAME_DP = 80;

    /** 11.3: نسخة مقروءة من اللون فوق خلفية داكنة (يُفتَّح الداكن فقط بقدر ما يلزم). */
    static int readableOnDark(int c) {
        float l = (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f;
        if (l >= 0.55f) return c;
        return mix(c, Color.WHITE, Math.min(0.7f, (0.55f - l) * 1.25f + 0.1f));
    }

    /** لون كل تبويب (النهاري، والليلي أفتح). 11.3: الافتراضي = اللون الأساسي لكل التبويبات، و«لون لكل تبويب» خيار. */
    int tabColor(String k) {
        if (db == null || !"multi".equals(db.setting("tab_tint", "primary"))) return night ? primaryInk : primary;
        int c;
        switch (k) {
            case "home": c = night ? Color.rgb(96, 165, 250) : Color.rgb(37, 99, 235); break;
            case "offices": c = night ? Color.rgb(45, 212, 191) : Color.rgb(13, 148, 136); break;
            case "log": c = night ? Color.rgb(167, 139, 250) : Color.rgb(124, 58, 237); break;
            case "more": c = night ? Color.rgb(251, 146, 60) : Color.rgb(234, 88, 12); break;
            case "cash": c = night ? Color.rgb(233, 196, 106) : Color.rgb(176, 124, 16); break;
            case "clients": c = night ? Color.rgb(244, 114, 182) : Color.rgb(219, 39, 119); break;
            case "reports": c = night ? Color.rgb(129, 140, 248) : Color.rgb(79, 70, 229); break;
            case "balances": c = night ? Color.rgb(34, 211, 238) : Color.rgb(8, 145, 178); break;
            case "daily": c = night ? Color.rgb(251, 191, 36) : Color.rgb(217, 119, 6); break;
            case "mine": c = night ? Color.rgb(61, 220, 151) : Color.rgb(22, 128, 82); break;
            default: c = primary;
        }
        return c;
    }

    /**
     * 10.10: الشريط السفلي «جزيرة» رمادية عائمة بحواف مستديرة. التبويب الحالي داخل إطار بلونه، وكلمته ملوّنة؛
     * عند الانتقال ينزلق الإطار من التبويب السابق إلى الجديد ويتبدل لونه. «تسجيل» زر بارز في الوسط.
     */
    void tabBar(String active) {
        // 10.15: الشريط الزجاجي بتجويف (من «أموالي») هو الافتراضي؛ والجزيرة والدائرة باقيتان خيارين
        if (NavPrefs.STYLE_NOTCH.equals(tabStyle())) {
            notchBar(active);
            return;
        }
        final FrameLayout tf = new FrameLayout(this);
        tf.setClipChildren(false);
        tf.setClipToPadding(false);
        // 10.13: الجزيرة وإطارها وأزرارها في مجموعة واحدة، لتطوى معًا في نمط «الدائرة»
        final FrameLayout grp = new FrameLayout(this);
        grp.setClipChildren(false);
        grp.setClipToPadding(false);
        tf.addView(grp, new FrameLayout.LayoutParams(-1, -1));
        // 10.12: أنحف وأنعم — كبسولة كاملة الاستدارة بتدرّج رمادي خفيف، وظل ناعم
        int swDp = getResources().getConfiguration().screenWidthDp;
        // 10.13: على الشاشة العريضة الجزيرة بعرض مريح في الوسط
        final int islandH = dp(56), gapB = dp(10), side = swDp >= 720 ? dp(Math.max(16, (swDp - 620) / 2)) : dp(16);
        int islandBg = night ? Color.rgb(28, 40, 35) : Color.rgb(232, 236, 234);
        int islandTop = night ? Color.rgb(36, 50, 44) : Color.rgb(242, 245, 243);
        int islandLine = night ? Color.rgb(46, 64, 56) : Color.rgb(214, 221, 218);
        View island = new View(this);
        GradientDrawable ig = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{islandTop, islandBg});
        ig.setCornerRadius(islandH / 2f);
        ig.setStroke(dp(1), islandLine);
        island.setBackground(ig);
        island.setElevation(dp(8));
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(-1, islandH, Gravity.BOTTOM);
        ip.setMargins(side, 0, side, gapB);
        grp.addView(island, ip);
        // الإطار المنزلق تحت التبويب الحالي
        final View frameInd = new View(this);
        frameInd.setElevation(dp(8));
        frameInd.setVisibility(View.INVISIBLE);
        grp.addView(frameInd, new FrameLayout.LayoutParams(0, 0, Gravity.TOP | Gravity.LEFT));
        final LinearLayout items = row();
        items.setClipChildren(false);
        items.setGravity(Gravity.BOTTOM);
        items.setPadding(side + dp(4), 0, side + dp(4), gapB);
        items.setElevation(dp(9));
        grp.addView(items, new FrameLayout.LayoutParams(-1, dp(TAB_FRAME_DP), Gravity.BOTTOM));
        final java.util.List<String> keys = tabKeys();
        final java.util.Map<String, View> cells = new java.util.HashMap<>();
        final java.util.Map<String, TextView> labels = new java.util.HashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            final String k = keys.get(i);
            boolean on = k.equals(active);
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setClipChildren(false);
            cell.setContentDescription(tabName(k));
            if (k.equals("tx")) {
                cell.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
                cell.setPadding(0, 0, 0, dp(5));
                FrameLayout fab = new FrameLayout(this);
                GradientDrawable g = new GradientDrawable();
                g.setShape(GradientDrawable.OVAL);
                g.setColors(new int[]{mix(primary, Color.WHITE, 0.18f), primary});
                g.setStroke(dp(3), islandTop);
                fab.setBackground(ripple(g, 30));
                fab.setElevation(dp(6));
                springy(fab, 0.88f);
                fab.addView(icon(R.drawable.ic_plus, onColor(primary), 24), new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
                cell.addView(fab, new LinearLayout.LayoutParams(dp(50), dp(50)));
                fab.setOnClickListener(v -> {
                    tick(v);
                    bubbleOpen = false;
                    openTab(k);
                });
                TextView t = text(tabName(k), 10, ink, true);
                t.setGravity(Gravity.CENTER);
                t.setPadding(0, dp(1), 0, 0);
                cell.addView(t, new LinearLayout.LayoutParams(-2, -2));
                items.addView(cell, new LinearLayout.LayoutParams(0, -1, 1.15f));
                continue;
            }
            cell.setGravity(Gravity.CENTER);
            if (k.equals("cash")) {
                // 10.13: الصندوق خزنة ملوّنة أكبر قليلًا (صورة بلا تلوين)
                ImageView safe = new ImageView(this);
                safe.setImageResource(R.drawable.ic_safe_color);
                safe.setScaleType(ImageView.ScaleType.FIT_CENTER);
                cell.addView(safe, new LinearLayout.LayoutParams(dp(27), dp(27)));
            } else {
                ImageView ic = icon(tabIcon(k), on ? tabColor(k) : muted, 20);
                cell.addView(ic, new LinearLayout.LayoutParams(dp(20), dp(20)));
            }
            TextView t = text(tabName(k), 10, on ? tabColor(k) : muted, on);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(2), 0, 0);
            cell.addView(t, new LinearLayout.LayoutParams(-2, -2));
            cell.setBackground(ripple(null, 18));
            springy(cell, 0.9f);
            cell.setOnClickListener(v -> {
                if (!k.equals(active) || !onHome) {
                    tick(v);
                    bubbleOpen = false;
                    openTab(k);
                }
            });
            items.addView(cell, new LinearLayout.LayoutParams(0, islandH, 1f));
            cells.put(k, cell);
            labels.put(k, t);
        }
        final String from = lastTab;
        lastTab = active;
        // يُرسم الإطار بعد أول تخطيط: من التبويب السابق (إن وُجد) إلى الحالي بحركة مرنة وتبدّل لون
        items.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            boolean done;

            @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                View to = cells.get(active);
                if (to == null || to.getWidth() == 0) {
                    frameInd.setVisibility(View.INVISIBLE);
                    return;
                }
                int w = to.getWidth() - dp(6), h = islandH - dp(12);
                ViewGroup.LayoutParams lp = frameInd.getLayoutParams();
                if (lp.width != w || lp.height != h) {
                    lp.width = w;
                    lp.height = h;
                    frameInd.setLayoutParams(lp);
                }
                float tx = items.getLeft() + to.getLeft() + dp(3), ty = items.getTop() + to.getTop() + dp(6);
                final int cTo = tabColor(active);
                if (done) {
                    frameInd.setTranslationX(tx);
                    frameInd.setTranslationY(ty);
                    return;
                }
                done = true;
                View prev = from == null ? null : cells.get(from);
                frameInd.setVisibility(View.VISIBLE);
                if (prev == null || prev == to || NO_MOTION) {
                    frameInd.setBackground(frameDrawable(cTo, islandBg));
                    frameInd.setTranslationX(tx);
                    frameInd.setTranslationY(ty);
                    if (prev == null && !NO_MOTION) {
                        frameInd.setScaleX(0.7f);
                        frameInd.setScaleY(0.7f);
                        frameInd.setAlpha(0f);
                        frameInd.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(420).setInterpolator(new android.view.animation.OvershootInterpolator(1.6f)).start();
                    }
                    return;
                }
                final int cFrom = tabColor(from);
                frameInd.setTranslationX(items.getLeft() + prev.getLeft() + dp(3));
                frameInd.setTranslationY(ty);
                frameInd.setBackground(frameDrawable(cFrom, islandBg));
                frameInd.animate().translationX(tx).setDuration(600).setInterpolator(new android.view.animation.OvershootInterpolator(1.15f)).start();
                android.animation.ValueAnimator col = android.animation.ValueAnimator.ofObject(new android.animation.ArgbEvaluator(), cFrom, cTo);
                col.setDuration(600);
                col.addUpdateListener(x -> frameInd.setBackground(frameDrawable((int) x.getAnimatedValue(), islandBg)));
                col.start();
                // كلمة التبويب الجديد تتلوّن تدريجيًا، والسابق يعود رماديًا
                final TextView nl = labels.get(active), ol2 = labels.get(from);
                android.animation.ValueAnimator txt = android.animation.ValueAnimator.ofFloat(0f, 1f);
                txt.setDuration(600);
                txt.addUpdateListener(x -> {
                    float f = (float) x.getAnimatedValue();
                    if (nl != null) nl.setTextColor(mix(muted, cTo, f));
                    if (ol2 != null) ol2.setTextColor(mix(cFrom, muted, f));
                });
                if (nl != null) nl.setTextColor(muted);
                txt.start();
            }
        });
        if ("bubble".equals(tabStyle())) bubble(tf, grp, items, active, gapB);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(TAB_FRAME_DP));
        p.topMargin = -dp(24);
        column.addView(tf, p);
        tabs = tf;
    }

    // ===================================================================================
    // 10.15: الشريط الزجاجي بتجويف وقائمة «+» (من «أموالي» 1.6)
    // ===================================================================================

    private String navJson;
    private NavPrefs navCache;

    /** إعدادات الشريط والقائمة لهذا المستخدم (nav_prefs شخصي). */
    NavPrefs nav() {
        String j = db == null ? "" : db.setting("nav_prefs", "");
        if (navCache == null || !j.equals(navJson)) {
            navCache = NavPrefs.from(j);
            navJson = j;
        }
        return navCache;
    }

    void saveNav(NavPrefs p) {
        if (db != null) db.setSettingQuiet("nav_prefs", p.toJson());
        navCache = null;
    }

    /** الجزء الذي يغطيه الشريط الطافي من أسفل المحتوى (بكسل)؛ 0 لغير الشريط الزجاجي. */
    int tabCover;

    /** زر «+» في الشريط الزجاجي (MainActivity يفتح القائمة السريعة). الافتراضي: صفحة التسجيل. */
    void plusPressed(NotchBar nb, int index, int activeIndex) { openTab("tx"); }

    /** لون شريط النظام تحت الشريط الزجاجي الموصول بالحواف (فيبدوان قطعة واحدة). */
    int navBarColor() {
        if (tabs instanceof NotchBar) return NotchBar.navColor(bg, nav(), nav().bar(header));
        return surface;
    }

    /**
     * الشريط الزجاجي يطفو فوق المحتوى (في frame لا في العمود)، فيظهر ما تحته عبر زجاجه، والمحتوى يُمرَّر تحته
     * وله هامش سفلي حتى لا يختفي آخره خلفه. التبويب الحالي دائرة بارزة بلونه، وتنزلق إلى التبويب الجديد.
     */
    private void notchBar(String active) {
        final java.util.List<String> keys = tabKeys();
        final NavPrefs st = nav();
        final java.util.List<Drawable> icons = new java.util.ArrayList<>();
        final java.util.List<String> names = new java.util.ArrayList<>();
        int[] circles = new int[keys.size()];
        for (int i = 0; i < keys.size(); i++) {
            String k = keys.get(i);
            icons.add(getDrawable("tx".equals(k) ? R.drawable.ic_plus_circle : tabIcon(k)));
            names.add(tabName(k));
            circles[i] = "tx".equals(k) ? st.circle(primary) : st.circle(tabColor(k));
        }
        final int idx = Math.max(0, keys.indexOf(active));
        final NotchBar[] nb = new NotchBar[1];
        nb[0] = new NotchBar(this, icons, names, idx, st, st.bar(header), circles, st.icons(header), i -> {
            String k = keys.get(i);
            if ("tx".equals(k)) plusPressed(nb[0], i, idx);
            else if (!k.equals(active) || !onHome) openTab(k);
        });
        nb[0].typeface(text("ا", 10, ink, true).getTypeface());
        frame.addView(nb[0], new FrameLayout.LayoutParams(-1, dp(NotchBar.heightDp(st)), Gravity.BOTTOM));
        tabs = nb[0];
        tabCover = dp(NotchBar.coverDp(st));
        body.setPadding(body.getPaddingLeft(), body.getPaddingTop(), body.getPaddingRight(), tabCover + dp(48));
        footerBox.setPadding(footerBox.getPaddingLeft(), footerBox.getPaddingTop(), footerBox.getPaddingRight(), tabCover + dp(2));
    }

    /** 10.13: نمط «الدائرة»: هل الشريط ممدود الآن (يُطوى بعد الانتقال إلى تبويب). */
    static boolean bubbleOpen;

    /**
     * 10.13: الشريط السفلي دائرة واحدة بلون التبويب الحالي وأيقونته؛ الضغط عليها يمدّ الجزيرة يمينًا وشمالًا من الوسط،
     * وتظهر الأزرار من القريب للبعيد، و«تسجيل» في الوسط. الضغط على التبويب الحالي يطويها من جديد.
     */
    private void bubble(final FrameLayout tf, final FrameLayout grp, final LinearLayout items, final String active, int gapB) {
        final FrameLayout b = new FrameLayout(this);
        int c = tabColor(active);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{mix(c, Color.WHITE, 0.2f), c});
        g.setShape(GradientDrawable.OVAL);
        g.setStroke(dp(3), night ? Color.rgb(36, 50, 44) : Color.rgb(242, 245, 243));
        b.setBackground(ripple(g, 30));
        b.setElevation(dp(10));
        b.addView(icon(tabIcon(active), onColor(c), 24), new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        b.setContentDescription("القائمة — " + tabName(active));
        springy(b, 0.88f);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(dp(58), dp(58), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        bp.bottomMargin = gapB;
        tf.addView(b, bp);
        final Runnable open = () -> {
            bubbleOpen = true;
            b.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f).setDuration(NO_MOTION ? 0 : 200).withEndAction(() -> b.setVisibility(View.GONE)).start();
            grp.setVisibility(View.VISIBLE);
            if (NO_MOTION) return;
            grp.setPivotX(grp.getWidth() / 2f);
            grp.setScaleX(0.16f);
            grp.setAlpha(0.4f);
            grp.animate().scaleX(1f).alpha(1f).setDuration(460).setInterpolator(new android.view.animation.OvershootInterpolator(1.1f)).start();
            int n = items.getChildCount();
            for (int i = 0; i < n; i++) {
                View ch = items.getChildAt(i);
                float dist = Math.abs(i - (n - 1) / 2f);
                ch.setAlpha(0f);
                ch.setScaleX(0.5f);
                ch.setScaleY(0.5f);
                ch.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(120 + (long) (dist * 70)).setDuration(320)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(1.4f)).start();
            }
        };
        if (bubbleOpen) b.setVisibility(View.GONE);
        else grp.setVisibility(View.INVISIBLE);
        b.setOnClickListener(v -> {
            tick(v);
            open.run();
        });
        // التبويب الحالي يطوي الشريط من جديد
        for (int i = 0; i < items.getChildCount(); i++) {
            final View ch = items.getChildAt(i);
            if (!tabName(active).equals(String.valueOf(ch.getContentDescription()))) continue;
            ch.setOnClickListener(v -> {
                tick(v);
                bubbleOpen = false;
                grp.animate().scaleX(0.16f).alpha(0f).setDuration(NO_MOTION ? 0 : 260).withEndAction(() -> {
                    grp.setVisibility(View.INVISIBLE);
                    grp.setScaleX(1f);
                    grp.setAlpha(1f);
                }).start();
                b.setVisibility(View.VISIBLE);
                b.setAlpha(0f);
                b.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(NO_MOTION ? 0 : 260).start();
            });
        }
    }

    /** إطار التبويب الحالي: حافة بلونه وتعبئة خفيفة منه. */
    private Drawable frameDrawable(int c, int islandBg) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(mix(islandBg, c, night ? 0.2f : 0.13f));
        g.setCornerRadius(dp(22));
        g.setStroke(dp(2), c);
        return g;
    }

    // ===================================================================================
    // 10.7: نوع الحركة أعلى صفحة «تسجيل حركة» (عادية، قص عملات، بين مكتبين) — بدل القائمة نصف الدائرية
    // ===================================================================================

    /** الخيارات المتاحة لهذا المستخدم (يحددها MainActivity بالصلاحيات). */
    java.util.List<RecordMenu.Item> recordItems() { return java.util.Arrays.asList(RecordMenu.ITEMS); }

    /** فتح نوع آخر من الصفحة نفسها، مع المكتب المختار إن وُجد. */
    void openRecord(String key, Model.Office office) {}

    /** أزرار نوع الحركة أعلى الصفحة (فوق المكتب). المكتب المختار ينتقل معك إلى النوع الآخر. */
    void recordKinds(final String current, final java.util.function.Supplier<Model.Office> office) {
        final java.util.List<RecordMenu.Item> items = recordItems();
        if (items.size() < 2) return;
        String[] labels = new String[items.size()];
        int sel = 0;
        for (int i = 0; i < labels.length; i++) {
            labels[i] = items.get(i).label;
            if (items.get(i).key.equals(current)) sel = i;
        }
        Seg s = new Seg(labels, null, sel, false);
        for (Button b : s.buttons) b.setTextSize(14);
        s.view.setContentDescription("نوع الحركة");
        s.listener = i -> {
            String k = items.get(i).key;
            if (!k.equals(current)) openRecord(k, office == null ? null : office.get());
        };
        body.addView(s.view, 0, lp(-1, -2, 4));
    }

    private boolean kbWatching;
    /** عنصر يُخفى أثناء الكتابة (سطر الملخص في الشريط الثابت مثلًا) لإفساح المجال للحقول. */
    View hideWithKeyboard;

    /** يخفي التبويبات عند ظهور لوحة المفاتيح حتى لا تأخذ مساحة الكتابة (مستمع واحد للنافذة كلها). */
    private void watchKeyboard() {
        if (kbWatching) return;
        kbWatching = true;
        final View decor = getWindow().getDecorView();
        decor.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            View root = frame;
            if (root == null || !root.isAttachedToWindow()) return;
            int h = decor.getHeight();
            boolean kb = h > 0 && root.getHeight() < h * 0.72;
            int want = kb ? View.GONE : View.VISIBLE;
            for (View t : new View[]{tabs, hideWithKeyboard}) if (t != null && t.getVisibility() != want) t.setVisibility(want);
            // 10.15: الشريط الزجاجي يطفو فوق أسفل الشاشة؛ مع لوحة المفاتيح يختفي فيُسحب الشريط الثابت إلى أسفل
            if (tabCover > 0 && footerBox != null && footerBox.getVisibility() == View.VISIBLE) {
                int pb = dp(10) + (kb ? 0 : tabCover);
                if (footerBox.getPaddingBottom() != pb) footerBox.setPadding(footerBox.getPaddingLeft(), footerBox.getPaddingTop(), footerBox.getPaddingRight(), pb);
            }
        });
    }

    // ===================================================================================
    // العناصر
    // ===================================================================================

    TextView text(String s, int size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(size);
        v.setTextColor(readable(color));
        v.setGravity(Gravity.RIGHT);
        v.setPadding(dp(6), dp(4), dp(6), dp(4));
        v.setLineSpacing(0, 1.08f);
        if (bold) bold(v);
        return v;
    }

    /** خط عريض من الخط نفسه المختار للتطبيق (لا يعود لخط الهاتف). */
    static void bold(TextView v) { v.setTypeface(Typeface.create(v.getTypeface(), Typeface.BOLD)); }

    TextView add(TextView v) {
        body.addView(v);
        return v;
    }

    TextView label(String s) { return text(s, 14, ink, true); }

    TextView title(String s) {
        TextView t = text(s, 17, primaryInk, true);
        t.setPadding(dp(4), dp(12), dp(4), dp(4));
        return add(t);
    }

    TextView hint(String s) { return add(text(s, 12, muted, false)); }

    EditText field(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextSize(16);
        e.setSingleLine(true);
        e.setTextColor(ink);
        e.setHintTextColor(mix(muted, surface, 0.15f));
        e.setPadding(dp(14), 0, dp(14), 0);
        StateListDrawable s = new StateListDrawable();
        GradientDrawable focus = round(surface, primary, 14);
        focus.setStroke(dp(2), night ? green : primary);
        s.addState(new int[]{android.R.attr.state_focused}, focus);
        s.addState(new int[]{}, round(surface, line, 14));
        e.setBackground(s);
        e.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        e.setTextDirection(View.TEXT_DIRECTION_ANY_RTL);
        return e;
    }

    EditText input(String hint) {
        EditText e = field(hint);
        body.addView(e, capWide(lp(-1, 52, 4)));
        return e;
    }

    /**
     * 11.6: في الشاشة العريضة (الكمبيوتر أو اللوحي) لا يتمدد الزر أو الخانة بعرض الشاشة كلها:
     * عرض أقصى 560 نقطة في الوسط، والصفحة نفسها تبقى بعرض النافذة.
     */
    LinearLayout.LayoutParams capWide(LinearLayout.LayoutParams p) {
        if (p.width == -1 && getResources().getConfiguration().screenWidthDp > 640) {
            p.width = dp(560);
            p.gravity = Gravity.CENTER_HORIZONTAL;
        }
        return p;
    }

    EditText amountInput(String hint) {
        EditText e = input(hint);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        return e;
    }

    /**
     * حقل سري (كلمة مرور أو رمز): سطر واحد أولًا ثم نوع الإدخال ثم الإخفاء صراحة.
     * setSingleLine بعد setInputType كان يستبدل طريقة الإخفاء فتظهر الكلمة.
     */
    static void secret(EditText e, boolean digits) {
        e.setSingleLine(true);
        e.setInputType(digits ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
        revealToggle(e);
    }

    /**
     * 10.5: زر «العين» في طرف كل خانة سر: يُظهر ما كتبه المستخدم أو يخفيه، دون تغيير نوع لوحة المفاتيح ولا موضع المؤشر.
     * يُرسم داخل الخانة نفسها (رسم في طرفها) فيعمل في كل نافذة وتخطيط دون لفّ الخانة بعنصر آخر.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    static void revealToggle(final EditText e) {
        final android.content.Context c = e.getContext();
        final android.graphics.drawable.Drawable show = c.getDrawable(R.drawable.ic_eye), hide = c.getDrawable(R.drawable.ic_eye_off);
        if (show == null || hide == null) return;
        final int size = Math.round(22 * c.getResources().getDisplayMetrics().density);
        final int tint = (e.getCurrentTextColor() & 0x00FFFFFF) | 0x99000000;
        for (android.graphics.drawable.Drawable d : new android.graphics.drawable.Drawable[]{show, hide}) {
            d.setBounds(0, 0, size, size);
            d.setTint(tint);
        }
        final boolean[] visible = {false};
        e.setCompoundDrawablePadding(Math.round(6 * c.getResources().getDisplayMetrics().density));
        e.setCompoundDrawablesRelative(null, null, show, null);
        e.setContentDescription("خانة سرية. المس رمز العين في طرفها لإظهار ما كتبته أو إخفائه");
        e.setOnTouchListener((v, ev) -> {
            if (ev.getAction() != android.view.MotionEvent.ACTION_UP) return false;
            boolean rtl = e.getLayoutDirection() == android.view.View.LAYOUT_DIRECTION_RTL;
            int area = size + e.getCompoundDrawablePadding() + Math.round(8 * c.getResources().getDisplayMetrics().density);
            float x = ev.getX();
            boolean onEye = rtl ? x <= e.getPaddingLeft() + area : x >= e.getWidth() - e.getPaddingRight() - area;
            if (!onEye) return false;
            visible[0] = !visible[0];
            int at = e.getSelectionEnd();
            e.setTransformationMethod(visible[0] ? android.text.method.HideReturnsTransformationMethod.getInstance()
                    : android.text.method.PasswordTransformationMethod.getInstance());
            e.setCompoundDrawablesRelative(null, null, visible[0] ? hide : show, null);
            e.setSelection(Math.max(0, Math.min(at < 0 ? e.length() : at, e.length())));
            v.performClick();
            return true;
        });
    }

    static void numeric(EditText e) { e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL); }

    // ===================================================================================
    // 10.9: الحركة — ضغطة مرنة لكل زر، وزر «اضغط مطوّلًا» يفتح التأكيد نفسه
    // ===================================================================================

    private static android.animation.Animator scaleTo(View v, float to, long ms, android.animation.TimeInterpolator in) {
        android.animation.AnimatorSet a = new android.animation.AnimatorSet();
        a.playTogether(android.animation.ObjectAnimator.ofFloat(v, "scaleX", to), android.animation.ObjectAnimator.ofFloat(v, "scaleY", to));
        a.setDuration(ms);
        a.setInterpolator(in);
        return a;
    }

    /** ينضغط العنصر قليلًا عند اللمس ويعود بارتداد خفيف. */
    static void springy(View v) { springy(v, 0.95f); }

    static void springy(View v, float to) {
        android.animation.StateListAnimator s = new android.animation.StateListAnimator();
        s.addState(new int[]{android.R.attr.state_pressed}, scaleTo(v, to, 150, new android.view.animation.DecelerateInterpolator()));
        s.addState(new int[]{}, scaleTo(v, 1f, 480, new android.view.animation.OvershootInterpolator(2.6f)));
        v.setStateListAnimator(s);
    }

    /**
     * زر «اضغط مطوّلًا»: يمتلئ تحت الإصبع خلال ثلثي ثانية ثم يضغط الزر نفسه (فتظهر نافذة التأكيد كما هي). رفع الإصبع قبل الامتلاء
     * يلغيه دون أي أثر، واللمسة القصيرة تذكّر بالطريقة. قارئ الشاشة يضغطه مباشرة (performClick) فيصل إلى التأكيد نفسه.
     */
    void holdToConfirm(final Button b) {
        if (!holdOn()) return;
        final Drawable base = b.getBackground();
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(night ? mix(primary, Color.WHITE, 0.35f) : mix(primary, Color.WHITE, 0.28f));
        fill.setCornerRadius(dp(16));
        final android.graphics.drawable.ClipDrawable clip = new android.graphics.drawable.ClipDrawable(fill, Gravity.RIGHT, android.graphics.drawable.ClipDrawable.HORIZONTAL);
        b.setBackground(new LayerDrawable(new Drawable[]{base, clip}));
        b.setContentDescription(b.getText() + " — اضغط مطوّلًا حتى يمتلئ الزر لفتح التأكيد");
        final android.animation.ValueAnimator[] run = {null};
        final boolean[] fired = {false};
        final Runnable back = () -> {
            if (run[0] != null) run[0].cancel();
            b.setPressed(false);
            android.animation.ValueAnimator r = android.animation.ValueAnimator.ofInt(clip.getLevel(), 0);
            r.setDuration(200);
            r.addUpdateListener(x -> clip.setLevel((int) x.getAnimatedValue()));
            r.start();
        };
        b.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    if (!v.isEnabled()) return false;
                    fired[0] = false;
                    v.setPressed(true);
                    if (run[0] != null) run[0].cancel();
                    android.animation.ValueAnimator an = android.animation.ValueAnimator.ofInt(clip.getLevel(), 10000);
                    an.setDuration(Math.max(80, (long) (850 * (1 - clip.getLevel() / 10000f))));
                    an.setInterpolator(new android.view.animation.LinearInterpolator());
                    an.addUpdateListener(x -> clip.setLevel((int) x.getAnimatedValue()));
                    an.addListener(new android.animation.AnimatorListenerAdapter() {
                        boolean cancelled;

                        @Override public void onAnimationCancel(android.animation.Animator a) { cancelled = true; }

                        @Override public void onAnimationEnd(android.animation.Animator a) {
                            if (cancelled) return;
                            fired[0] = true;
                            thud(v);
                            v.setPressed(false);
                            v.performClick();
                            v.postDelayed(() -> clip.setLevel(0), 300);
                        }
                    });
                    run[0] = an;
                    an.start();
                    return true;
                case android.view.MotionEvent.ACTION_MOVE:
                    if (!fired[0] && (e.getX() < 0 || e.getY() < 0 || e.getX() > v.getWidth() || e.getY() > v.getHeight())) back.run();
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                    if (!fired[0]) {
                        boolean quick = e.getEventTime() - e.getDownTime() < 350;
                        back.run();
                        if (quick) {
                            toast("اضغط مطوّلًا على الزر حتى يمتلئ، ثم أكّد");
                            v.animate().translationX(dp(6)).setDuration(60).withEndAction(() -> v.animate().translationX(-dp(5)).setDuration(60)
                                    .withEndAction(() -> v.animate().translationX(0).setDuration(80).start()).start()).start();
                        }
                    }
                    return true;
                case android.view.MotionEvent.ACTION_CANCEL:
                    if (!fired[0]) back.run();
                    return true;
                default:
                    return false;
            }
        });
    }

    /** يلوّن الزر حسب دوره: الأساسي ممتلئ، الذهبي هادئ، الرمادي بإطار، وغيرها ممتلئ بلونه. */
    private void styleButton(Button b, int color, int radius) {
        int fill, stroke, txt;
        if (color == accent) {
            fill = goldSoft;
            stroke = frames ? mix(goldSoft, goldInk, 0.45f) : goldSoft;
            txt = goldInk;
        } else if (isGray(color)) {
            fill = surface;
            stroke = frames ? mix(line, ink, 0.18f) : line;
            txt = ink;
        } else {
            fill = color;
            stroke = frames ? mix(color, night ? Color.WHITE : Color.BLACK, 0.22f) : color;
            txt = onColor(color);
        }
        b.setTextColor(txt);
        b.setBackground(ripple(round(fill, stroke, radius), radius));
        b.setElevation(0);
        springy(b);
    }

    /** 10.13: إطارات الأزرار والبطاقات ظاهرة (خيار في «المظهر»). */
    boolean frames = true;

    /** 10.13: حركة الصفحات: «متتابعة» (العناصر تصعد واحدًا بعد الآخر) أو «دفعة واحدة» أو «بلا حركة». */
    String pageMotion() { return db == null ? "stagger" : db.setting("page_motion", "stagger"); }

    /** 10.13: «اضغط مطوّلًا» للتسجيل خيار لا إجبار (الافتراضي: ضغطة عادية ثم نافذة التأكيد). */
    boolean holdOn() { return db != null && "on".equals(db.setting("hold_confirm", "off")); }

    /** 10.13: شكل الشريط السفلي: «جزيرة» أو «دائرة تتمدد». */
    /** 10.15: الافتراضي الشريط الزجاجي بتجويف؛ من اختار «جزيرة» أو «دائرة» سابقًا يبقى على اختياره. */
    String tabStyle() { return db == null ? NavPrefs.STYLE_ISLAND : NavPrefs.style(db.setting("tab_style", NavPrefs.STYLE_NOTCH)); }

    /** 10.12: اهتزاز خفيف للأزرار المهمة — يُفعَّل ويُوقف من «الإعدادات والتصميم» (لكل مستخدم). */
    boolean hapticsOn() {
        return db == null || !"off".equals(db.setting("haptics", "on"));
    }

    void tick(View v) {
        if (v != null && hapticsOn()) v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
    }

    void thud(View v) {
        if (v != null && hapticsOn()) v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
    }

    Button makeButton(String label, int color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(16);
        b.setAllCaps(false);
        bold(b);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(12), 0, dp(12), 0);
        styleButton(b, color, 16);
        b.setOnClickListener(l == null ? null : v -> {
            tick(v);
            l.onClick(v);
        });
        return b;
    }

    Button button(String label, int color, View.OnClickListener l) {
        Button b = makeButton(label, color, l);
        body.addView(b, capWide(lp(-1, 54, 4)));
        return b;
    }

    Button smallButton(String s, int col, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setPadding(dp(10), 0, dp(10), 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        styleButton(b, col, 12);
        b.setOnClickListener(l);
        return b;
    }

    LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        c.setBackground(round(surface, cardStroke, 20));
        body.addView(c, lp(-1, -2, 5));
        return c;
    }

    LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    GradientDrawable round(int fill, int stroke, int r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fillFor(fill));
        g.setCornerRadius(dp(r));
        g.setStroke(dp(1), fill == stroke ? fillFor(fill) : strokeFor(stroke));
        return g;
    }

    /** أثر اللمس فوق أي خلفية (أو بدون خلفية). */
    Drawable ripple(Drawable content, int r) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(r));
        int rc = night ? Color.argb(46, 255, 255, 255) : Color.argb(28, 11, 60, 45);
        return new RippleDrawable(ColorStateList.valueOf(rc), content, mask);
    }

    LinearLayout.LayoutParams lp(int w, int h, int m) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h < 0 ? h : dp(h));
        p.setMargins(0, dp(m), 0, dp(m));
        return p;
    }

    LinearLayout.LayoutParams weight(int h, float w, int side) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, h < 0 ? h : dp(h), w);
        p.setMargins(dp(side), dp(3), dp(side), dp(3));
        return p;
    }

    int dp(int x) { return (int) (x * getResources().getDisplayMetrics().density + .5f); }

    Spinner spinner(String[] a) {
        Spinner s = new Spinner(this);
        s.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, a));
        return s;
    }

    void setItems(Spinner s, List<String> a) {
        s.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, a));
    }

    static void select(Spinner s, String value) {
        if (value == null || s.getAdapter() == null) return;
        for (int i = 0; i < s.getAdapter().getCount(); i++) {
            if (value.equals(String.valueOf(s.getAdapter().getItem(i)))) {
                s.setSelection(i);
                return;
            }
        }
    }

    static TextWatcher watcher(final Runnable r) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}

            @Override public void afterTextChanged(Editable e) { r.run(); }
        };
    }

    void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_LONG).show(); }

    View swatch(int color, int sizeDp) {
        View v = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        g.setStroke(dp(2), night ? line : Color.rgb(200, 200, 200));
        v.setBackground(g);
        v.setMinimumHeight(dp(sizeDp));
        return v;
    }

    // ===================================================================================
    // عناصر التصميم الجديد: أيقونات، رقائق، قوائم مجمّعة، عناوين أقسام، إشعار مع تراجع
    // ===================================================================================

    ImageView icon(int res, int color, int sizeDp) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(res);
        iv.setImageTintList(ColorStateList.valueOf(color));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setMinimumWidth(dp(sizeDp));
        iv.setMinimumHeight(dp(sizeDp));
        return iv;
    }

    /**
     * 12.3: زر أداة صغير بأيقونة خطية ونص (لصق، التقاط، تجميع، قص، مسح): شكل حبة مستديرة متناسقة على كل المنصات بدل الرموز التعبيرية.
     * style: 0 أساسي ممتلئ، 1 ناعم بلون التطبيق، 2 محايد (للمسح).
     */
    LinearLayout toolChip(int res, String label, int style, View.OnClickListener l) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER);
        c.setPadding(dp(12), 0, dp(14), 0);
        c.setMinimumHeight(dp(38));
        int fill = style == 0 ? primary : style == 1 ? primarySoft : surface;
        int stroke = style == 0 ? primary : style == 1 ? mix(primarySoft, primaryInk, 0.22f) : line;
        int ink = style == 0 ? Color.WHITE : style == 1 ? primaryInk : muted;
        c.setBackground(ripple(round(fill, stroke, 19), 19));
        ImageView iv = icon(res, ink, 18);
        c.addView(iv, new LinearLayout.LayoutParams(dp(18), dp(18)));
        TextView t = text(label, 13, ink, true);
        t.setSingleLine(true);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.setMarginStart(dp(6));
        c.addView(t, tp);
        c.setContentDescription(label);
        c.setClickable(true);
        c.setOnClickListener(l);
        springy(c, 0.94f);
        return c;
    }

    FrameLayout iconButton(int res, int color, String desc, View.OnClickListener l) {
        FrameLayout f = new FrameLayout(this);
        f.addView(icon(res, color, 22), new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        f.setBackground(ripple(null, 14));
        f.setContentDescription(desc);
        f.setOnClickListener(l);
        springy(f, 0.88f);
        return f;
    }

    /**
     * 10.13: ترتيب بالسحب والإفلات لصفوف عمودية متتالية داخل box: المس المقبض «≡» واسحب الصف إلى مكانه؛ تتنحّى الصفوف الأخرى
     * بحركة ناعمة، وعند الإفلات يُعاد ترتيب الصفوف ويُستدعى done بالمفاتيح بالترتيب الجديد. الأسهم (إن وُجدت) تبقى بديلًا.
     */
    final class DragSort {
        final LinearLayout box;
        final java.util.function.Consumer<List<Object>> done;

        DragSort(LinearLayout box, java.util.function.Consumer<List<Object>> done) {
            this.box = box;
            this.done = done;
        }

        /** مقبض جاهز للصف (يُضاف في بداية الصف). */
        TextView handle() {
            TextView h = text("≡", 24, muted, true);
            h.setGravity(Gravity.CENTER);
            h.setContentDescription("اسحب لتغيير الترتيب");
            return h;
        }

        /** يربط صفًا (ابنًا مباشرًا لـ box) بمقبضه ومفتاحه. */
        void bind(final View row, View handle, Object key) {
            row.setTag(R.id.drag_key, key);
            handle.setOnTouchListener(new View.OnTouchListener() {
                float startY;
                int from, to;
                int[] tops, hs;
                int dragH;

                @Override public boolean onTouch(View v, android.view.MotionEvent e) {
                    switch (e.getActionMasked()) {
                        case android.view.MotionEvent.ACTION_DOWN: {
                            box.requestDisallowInterceptTouchEvent(true);
                            int n = box.getChildCount();
                            from = box.indexOfChild(row);
                            if (from < 0) return false;
                            to = from;
                            tops = new int[n];
                            hs = new int[n];
                            for (int i = 0; i < n; i++) {
                                tops[i] = box.getChildAt(i).getTop();
                                hs[i] = box.getChildAt(i).getHeight();
                            }
                            dragH = from + 1 < n ? tops[from + 1] - tops[from] : hs[from];
                            startY = e.getRawY();
                            row.setElevation(dp(10));
                            row.animate().scaleX(1.03f).scaleY(1.03f).setDuration(120).start();
                            tick(row);
                            return true;
                        }
                        case android.view.MotionEvent.ACTION_MOVE: {
                            if (tops == null) return false;
                            float dy = e.getRawY() - startY;
                            row.setTranslationY(dy);
                            float center = tops[from] + hs[from] / 2f + dy;
                            int t = from;
                            for (int i = from + 1; i < tops.length; i++) if (center > tops[i] + hs[i] / 2f) t = i;
                            for (int i = from - 1; i >= 0; i--) if (center < tops[i] + hs[i] / 2f) t = i;
                            if (t != to) {
                                to = t;
                                for (int i = 0; i < tops.length; i++) {
                                    if (i == from) continue;
                                    float want = (from < i && i <= to) ? -dragH : (to <= i && i < from) ? dragH : 0;
                                    View c = box.getChildAt(i);
                                    if (c.getTranslationY() != want) c.animate().translationY(want).setDuration(NO_MOTION ? 0 : 160).start();
                                }
                            }
                            return true;
                        }
                        case android.view.MotionEvent.ACTION_UP:
                        case android.view.MotionEvent.ACTION_CANCEL: {
                            if (tops == null) return false;
                            box.requestDisallowInterceptTouchEvent(false);
                            for (int i = 0; i < box.getChildCount(); i++) {
                                View c = box.getChildAt(i);
                                c.animate().cancel();
                                c.setTranslationY(0);
                            }
                            row.setElevation(0);
                            row.setScaleX(1f);
                            row.setScaleY(1f);
                            tops = null;
                            if (to != from) {
                                box.removeView(row);
                                box.addView(row, to);
                                tick(row);
                                List<Object> keys = new ArrayList<>();
                                for (int i = 0; i < box.getChildCount(); i++) {
                                    Object k = box.getChildAt(i).getTag(R.id.drag_key);
                                    if (k != null) keys.add(k);
                                }
                                if (done != null) done.accept(keys);
                            }
                            return true;
                        }
                        default:
                            return false;
                    }
                }
            });
        }
    }

    /** رقاقة صغيرة مستديرة (لنا / علينا / موقوف ...). */
    TextView chip(String s, int fg, int fill) {
        TextView t = text(s, 12, fg, true);
        t.setPadding(dp(10), dp(2), dp(10), dp(3));
        t.setGravity(Gravity.CENTER);
        t.setBackground(round(fill, fill, 50));
        return t;
    }

    /** رقاقة قابلة للضغط (مرشحات، آخر المكاتب). */
    TextView pill(String s, boolean on, View.OnClickListener l) {
        TextView t = text(s, 14, on ? Color.WHITE : ink, on);
        t.setPadding(dp(14), dp(7), dp(14), dp(8));
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setBackground(ripple(on ? round(primary, primary, 50) : round(surface, cardStroke, 50), 50));
        if (on) t.setTextColor(onColor(primary));
        t.setOnClickListener(l);
        springy(t, 0.93f);
        return t;
    }

    HorizontalScrollView strip(LinearLayout inner) {
        HorizontalScrollView h = new HorizontalScrollView(this);
        h.setHorizontalScrollBarEnabled(false);
        h.setClipToPadding(false);
        h.addView(inner);
        return h;
    }

    LinearLayout.LayoutParams gap(int w, int h, int side) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w < 0 ? w : dp(w), h < 0 ? h : dp(h));
        p.setMargins(dp(side), 0, dp(side), 0);
        return p;
    }

    /** عنوان قسم مع رابط اختياري في الطرف الآخر. */
    LinearLayout section(String title, String link, View.OnClickListener l) {
        LinearLayout r = row();
        r.setPadding(dp(2), dp(14), dp(2), dp(4));
        r.addView(text(title, 15, ink, true), new LinearLayout.LayoutParams(0, -2, 1));
        if (link != null) {
            TextView t = text(link, 13, primaryInk, true);
            t.setBackground(ripple(null, 10));
            t.setOnClickListener(l);
            r.addView(t, new LinearLayout.LayoutParams(-2, -2));
        }
        body.addView(r, new LinearLayout.LayoutParams(-1, -2));
        return r;
    }

    /** بطاقة قائمة بلا حشو داخلي، تُضاف إليها أسطر بـ groupRow. */
    LinearLayout group() {
        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.VERTICAL);
        g.setBackground(round(surface, cardStroke, 20));
        g.setClipToOutline(true);
        body.addView(g, lp(-1, -2, 5));
        return g;
    }

    /** سطر في قائمة مجمّعة: أيقونة، عنوان، وصف اختياري، ونص أو سهم في الطرف. */
    LinearLayout groupRow(LinearLayout g, int iconRes, int iconColor, String title, String sub, String trail, View.OnClickListener l) {
        if (g.getChildCount() > 0) {
            View d = new View(this);
            d.setBackgroundColor(line);
            LinearLayout.LayoutParams dp1 = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1) / 2 + 0));
            dp1.setMargins(dp(14), 0, dp(14), 0);
            g.addView(d, dp1);
        }
        LinearLayout r = row();
        r.setPadding(dp(14), dp(11), dp(10), dp(11));
        r.setMinimumHeight(dp(52));
        if (l != null) springy(r, 0.98f);
        if (iconRes != 0) {
            FrameLayout ib = new FrameLayout(this);
            ib.setBackground(round(primarySoft, primarySoft, 11));
            ib.addView(icon(iconRes, iconColor, 20), new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(36), dp(36));
            ip.setMargins(0, 0, 0, 0);
            r.addView(ib, ip);
        }
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(8), 0, dp(4), 0);
        TextView tt = text(title, 15, ink, false);
        tt.setPadding(dp(2), 0, dp(2), 0);
        col.addView(tt);
        if (sub != null && !sub.isEmpty()) {
            TextView st = text(sub, 12, muted, false);
            st.setPadding(dp(2), 0, dp(2), 0);
            col.addView(st);
        }
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        if (trail != null && !trail.isEmpty()) {
            TextView tr = text(trail, 13, primaryInk, true);
            r.addView(tr, new LinearLayout.LayoutParams(-2, -2));
        } else if (l != null) {
            r.addView(icon(R.drawable.ic_chevron, mix(muted, surface, 0.25f), 18), new LinearLayout.LayoutParams(dp(18), dp(18)));
        }
        if (l != null) {
            r.setBackground(ripple(null, 0));
            r.setOnClickListener(l);
        }
        g.addView(r, new LinearLayout.LayoutParams(-1, -2));
        return r;
    }

    /** شريط تنبيه (أصفر/أحمر) بنص وإجراء. */
    LinearLayout notice(int iconRes, String msg, boolean danger, String action, View.OnClickListener l) {
        int fill = danger ? debitBg : warnBg;
        int fg = danger ? red : warnInk;
        LinearLayout r = row();
        r.setPadding(dp(12), dp(10), dp(12), dp(10));
        r.setBackground(round(fill, fill, 14));
        r.addView(icon(iconRes, fg, 20), new LinearLayout.LayoutParams(dp(20), dp(20)));
        r.addView(text(msg, 13, fg, false), new LinearLayout.LayoutParams(0, -2, 1));
        if (action != null) {
            TextView t = text(action, 13, fg, true);
            r.addView(t, new LinearLayout.LayoutParams(-2, -2));
        }
        if (l != null) {
            r.setBackground(ripple(round(fill, fill, 14), 14));
            r.setOnClickListener(l);
        }
        body.addView(r, lp(-1, -2, 5));
        return r;
    }

    /** إشعار عائم أسفل الشاشة مع زر (مثل «تراجع») يختفي بعد ثوانٍ. */
    void snack(String msg, String action, final Runnable act) {
        if (frame == null) return;
        final FrameLayout host = frame;
        final LinearLayout s = row();
        s.setPadding(dp(16), dp(6), dp(8), dp(6));
        s.setBackground(round(night ? Color.rgb(232, 238, 235) : Color.rgb(29, 42, 38), night ? Color.rgb(232, 238, 235) : Color.rgb(29, 42, 38), 14));
        s.setElevation(dp(6));
        int fg = night ? Color.rgb(15, 26, 23) : Color.WHITE;
        TextView m = new TextView(this);
        m.setText(msg);
        m.setTextSize(14);
        m.setTextColor(fg);
        m.setGravity(Gravity.RIGHT);
        s.addView(m, new LinearLayout.LayoutParams(0, -2, 1));
        if (action != null) {
            TextView b = new TextView(this);
            b.setText(action);
            b.setTextSize(15);
            b.setTextColor(night ? Color.rgb(11, 107, 83) : Color.rgb(233, 196, 120));
            bold(b);
            b.setPadding(dp(14), dp(10), dp(14), dp(10));
            b.setBackground(ripple(null, 10));
            b.setOnClickListener(v -> {
                host.removeView(s);
                act.run();
            });
            s.addView(b, new LinearLayout.LayoutParams(-2, -2));
        }
        final FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        p.setMargins(dp(12), 0, dp(12), dp(12));
        host.addView(s, p);
        s.post(() -> {
            int extra = 0;
            if (footerBox != null && footerBox.getVisibility() == View.VISIBLE) extra += footerBox.getHeight();
            if (tabs != null && tabs.getVisibility() == View.VISIBLE) extra += tabCover > 0 ? tabCover : dp(70);
            p.bottomMargin = dp(12) + extra;
            s.setLayoutParams(p);
        });
        s.postDelayed(() -> host.removeView(s), 9000);
    }

    // ===================================================================================
    // أزرار مجزأة (اختيار واحد من عدة خيارات بضغطة)
    // ===================================================================================

    interface OnPick { void picked(int index); }

    /**
     * 10.9: أزرار مجزأة عصرية: للأزرار المتساوية مسار هادئ يتحرك فوقه مؤشر ملوّن بمرونة إلى الخيار المختار؛
     * وللقوائم الممتدة (العملات) رقائق مستديرة يرتفع المختار منها. الاستعمال كما كان تمامًا.
     */
    final class Seg {
        final ViewGroup view;
        final List<Button> buttons = new ArrayList<>();
        final int[] colors;
        final boolean scroll;
        int selected;
        OnPick listener;
        private View ind;
        private Drawable track;
        private boolean placed;

        Seg(String[] labels, int[] colors, int initial, boolean scroll) {
            this.colors = colors;
            this.scroll = scroll;
            LinearLayout r = row();
            for (int i = 0; i < labels.length; i++) {
                final int idx = i;
                Button b = new Button(Ui.this);
                b.setText(labels[i]);
                b.setAllCaps(false);
                b.setTextSize(15);
                bold(b);
                b.setStateListAnimator(null);
                b.setOnClickListener(v -> {
                    if (idx != selected) tick(v);
                    set(idx, true);
                });
                buttons.add(b);
                if (scroll) {
                    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, dp(44));
                    p.setMargins(dp(3), dp(4), dp(3), dp(4));
                    b.setMinWidth(dp(72));
                    b.setPadding(dp(16), 0, dp(16), 0);
                    springy(b, 0.92f);
                    r.addView(b, p);
                } else {
                    b.setMinHeight(0);
                    b.setMinimumHeight(0);
                    b.setPadding(dp(6), 0, dp(6), 0);
                    springy(b, 0.94f);
                    r.addView(b, new LinearLayout.LayoutParams(0, dp(44), 1));
                }
            }
            if (scroll) {
                HorizontalScrollView h = new HorizontalScrollView(Ui.this);
                h.setHorizontalScrollBarEnabled(false);
                h.setClipToPadding(false);
                h.addView(r);
                LinearLayout wrap = new LinearLayout(Ui.this);
                wrap.addView(h, new LinearLayout.LayoutParams(-1, -2));
                view = wrap;
            } else {
                FrameLayout f = new FrameLayout(Ui.this);
                track = round(segTrack, segTrack, 16);
                f.setBackground(track);
                f.setPadding(dp(4), dp(4), dp(4), dp(4));
                ind = new View(Ui.this);
                ind.setVisibility(View.INVISIBLE);
                f.addView(ind, new FrameLayout.LayoutParams(0, 0, Gravity.TOP | Gravity.LEFT));
                f.addView(r, new FrameLayout.LayoutParams(-1, -2));
                r.addOnLayoutChangeListener((v, l1, t1, r1, b1, l0, t0, r0, b0) -> place(false));
                view = f;
            }
            set(initial, false);
        }

        private int colorOf(int i) { return colors == null ? primary : colors[Math.min(i, colors.length - 1)]; }

        /** يضع المؤشر تحت الخيار المختار (بحركة مرنة بعد أول رسم). */
        private void place(boolean animate) {
            if (ind == null) return;
            if (selected < 0 || selected >= buttons.size()) {
                ind.setVisibility(View.INVISIBLE);
                return;
            }
            Button b = buttons.get(selected);
            if (b.getWidth() == 0) return;
            ViewGroup.LayoutParams lp = ind.getLayoutParams();
            if (lp.width != b.getWidth() || lp.height != b.getHeight()) {
                lp.width = b.getWidth();
                lp.height = b.getHeight();
                ind.setLayoutParams(lp);
            }
            int c = colorOf(selected);
            ind.setBackground(round(c, c, 12));
            boolean wasHidden = ind.getVisibility() != View.VISIBLE;
            ind.setVisibility(View.VISIBLE);
            if (animate && placed && !wasHidden && !NO_MOTION) {
                ind.animate().translationX(b.getLeft()).translationY(b.getTop()).setDuration(580)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(1.25f)).start();
            } else {
                ind.animate().cancel();
                ind.setTranslationX(b.getLeft());
                ind.setTranslationY(b.getTop());
                if (wasHidden && placed && !NO_MOTION) {
                    ind.setScaleX(0.85f);
                    ind.setScaleY(0.85f);
                    ind.setAlpha(0f);
                    ind.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(420).setInterpolator(new android.view.animation.OvershootInterpolator(1.6f)).start();
                }
            }
            placed = true;
        }

        /** idx = -1: لا شيء محدد (يُلزم المستخدم بالاختيار). */
        void set(int idx, boolean fire) {
            if (idx >= buttons.size()) idx = 0;
            if (idx < 0) idx = -1;
            int prev = selected;
            selected = idx;
            for (int i = 0; i < buttons.size(); i++) {
                int c = colorOf(i);
                Button b = buttons.get(i);
                if (scroll) {
                    if (i == idx) {
                        b.setBackground(ripple(round(c, c, 50), 50));
                        b.setTextColor(onColor(c));
                        b.setElevation(dp(2));
                        if (fire && prev != idx && !NO_MOTION) {
                            b.setScaleX(0.9f);
                            b.setScaleY(0.9f);
                            b.animate().scaleX(1f).scaleY(1f).setDuration(480).setInterpolator(new android.view.animation.OvershootInterpolator(2.6f)).start();
                        }
                    } else {
                        b.setBackground(ripple(round(surface, colors == null ? cardStroke : mix(line, c, 0.35f), 50), 50));
                        b.setTextColor(colors == null ? ink : readable(c));
                        b.setElevation(0);
                    }
                } else {
                    b.setBackground(ripple(null, 12));
                    b.setTextColor(i == idx ? onColor(c) : colors == null ? muted : readable(c));
                }
            }
            if (!scroll) {
                if (view != null) {
                    final boolean anim = fire;
                    view.post(() -> place(anim));
                    place(fire);
                }
            }
            if (fire && listener != null && idx >= 0) listener.picked(idx);
        }

        boolean none() { return selected < 0 || buttons.isEmpty(); }

        String label() { return none() ? null : buttons.get(selected).getText().toString(); }

        /** إطار أحمر حول الأزرار عند نسيان الاختيار. */
        void flagMissing(boolean missing) {
            if (missing) {
                GradientDrawable g = round(debitBg, red, 16);
                g.setStroke(dp(2), red, dp(6), dp(4));
                view.setBackground(g);
                if (scroll) view.setPadding(dp(3), dp(3), dp(3), dp(3));
            } else {
                view.setBackground(track);
                if (scroll) view.setPadding(0, 0, 0, 0);
            }
        }
    }

    Seg seg(String[] labels, int initial, OnPick l) {
        Seg s = new Seg(labels, null, initial, false);
        s.listener = l;
        body.addView(s.view, lp(-1, -2, 2));
        return s;
    }

    // ===================================================================================
    // العمل في الخلفية والأخطاء
    // ===================================================================================

    void busy(String msg, final Work w, final Done done) {
        if (busyRunning) {
            toast("هناك عملية جارية، انتظر حتى تنتهي.");
            return;
        }
        busyRunning = true;
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setPadding(dp(20), dp(20), dp(20), dp(20));
        l.addView(new ProgressBar(this));
        final TextView tv = text(msg, 16, dark, false);
        l.addView(tv, new LinearLayout.LayoutParams(0, -2, 1));
        final AlertDialog dlg = new AlertDialog.Builder(this).setView(l).setCancelable(false).create();
        dlg.show();
        final BackupManager.Progress progress = m -> runOnUiThread(() -> tv.setText(m));
        new Thread(() -> {
            Object res = null;
            Throwable err = null;
            try {
                res = w.run(progress);
            } catch (Throwable t) {
                err = t;
            }
            final Object fr = res;
            final Throwable fe = err;
            runOnUiThread(() -> {
                busyRunning = false;
                if (isFinishing() || isDestroyed()) return;
                try {
                    dlg.dismiss();
                } catch (Throwable ignored) {
                    // تجاهل
                }
                if (fe != null) {
                    try {
                        db.audit("OPERATION_FAILED", "busy", 0, fe.getClass().getSimpleName());
                    } catch (Throwable ignored) {
                        // تجاهل
                    }
                    error(fe);
                } else {
                    try {
                        done.ok(fr);
                    } catch (Throwable t) {
                        error(t);
                    }
                }
            });
        }, "smart-work").start();
    }

    void error(Throwable e) {
        if (isFinishing() || isDestroyed()) return;
        if (e instanceof Entitlements.LimitReached) {
            new AlertDialog.Builder(this).setTitle("حدود النسخة المجانية").setMessage(e.getMessage())
                    .setPositiveButton("الترخيص", (d, w) -> openLicense()).setNegativeButton("حسنًا", null).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("تعذر إكمال العملية").setMessage(friendly(e)).setPositiveButton("حسنًا", null).show();
    }

    /** شاشة الترخيص (تُفتح من رسالة بلوغ حدود المجاني). */
    void openLicense() {}

    static String friendly(Throwable e) {
        if (e instanceof BackupManager.BackupException) return ((BackupManager.BackupException) e).userMessage();
        if (e instanceof NumberFormatException) return "قيمة رقمية غير صحيحة. اكتب الأرقام فقط، مثال: 1500 أو 1500.50";
        if (e instanceof ArithmeticException) return "الرقم غير مقبول: عدد المنازل العشرية أكبر من المسموح لهذه العملة أو الرقم كبير جدًا.";
        if (e instanceof OutOfMemoryError) return "الذاكرة لا تكفي لهذه العملية. جرّب عددًا أقل من الحركات أو الصور.";
        if (e instanceof android.database.sqlite.SQLiteConstraintException) return "تعذر الحفظ لأن البيانات مرتبطة ببيانات أخرى أو مكررة.";
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof java.net.UnknownHostException) return netFriendly("unknownhost \"" + c.getMessage() + "\"");
            if (c instanceof java.net.ConnectException || c instanceof java.net.NoRouteToHostException) return netFriendly("failed to connect " + c.getMessage());
            if (c instanceof java.net.SocketTimeoutException) return netFriendly("timed out");
            if (c instanceof javax.net.ssl.SSLException) return netFriendly("handshake " + c.getMessage());
        }
        String m = e.getMessage();
        return m == null ? e.toString() : netFriendly(m);
    }

    /**
     * 11.2: أخطاء الشبكة بالعربية بدل رسائل النظام الإنجليزية (مثل «Unable to resolve host … No address associated with hostname»).
     * أي رسالة أخرى تعود كما هي.
     */
    static String netFriendly(String m) {
        if (m == null) return "";
        String l = m.toLowerCase(java.util.Locale.ROOT);
        String host = "";
        java.util.regex.Matcher h = java.util.regex.Pattern.compile("\"([a-z0-9.-]+\\.[a-z]{2,})\"").matcher(l);
        if (h.find()) host = h.group(1);
        else {
            java.util.regex.Matcher h2 = java.util.regex.Pattern.compile("([a-z0-9-]+(?:\\.[a-z0-9-]+)+\\.[a-z]{2,})").matcher(l);
            if (h2.find()) host = h2.group(1);
        }
        String where = host.isEmpty() ? "الخادم" : "الخادم (" + host + ")";
        if (l.contains("unable to resolve host") || l.contains("no address associated") || l.contains("nodename nor servname") || l.contains("name or service not known")
                || l.contains("unknownhost"))
            return "لا يوجد اتصال بالإنترنت، أو تعذّر الوصول إلى " + where + ". تحقّق من الشبكة (أو أوقف VPN/المتصفح الآمن إن وُجد) ثم أعد المحاولة. لم يُفقد شيء: ما لم يُرسل يُرسل في المرة القادمة.";
        if (l.contains("network is unreachable") || l.contains("failed to connect") || l.contains("connection refused") || l.contains("connect timed out"))
            return "تعذّر الاتصال بـ" + where + ". تحقّق من الإنترنت ثم أعد المحاولة. لم يُفقد شيء.";
        if (l.contains("handshake") || l.contains("ssl") || l.contains("certificate") || l.contains("pkix"))
            return "انقطع الاتصال الآمن مع " + where + " قبل اكتماله. غالبًا الشبكة تحجب الاتصال أو تحتاج VPN/وكيلًا: "
                    + "جرّب شبكة أخرى، أو شغّل VPN ثم أعد المحاولة. لم يُفقد شيء.";
        if (l.contains("timeout") || l.contains("timed out"))
            return "الاتصال بطيء جدًا وانتهت المهلة. أعد المحاولة بعد قليل. لم يُفقد شيء.";
        return m;
    }

    void confirm(String title, String msg, String yes, final Runnable onYes) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton(yes, (d, w) -> onYes.run()).setNegativeButton("إلغاء", null).show();
    }

    /** 10.5 (§11): يرسم سطور تأكيد الحركة: المكتب بالأحمر مع كلمة «المكتب:»، والرصيد بعد الحركة بخط عريض. */
    void fillConfirm(LinearLayout box, TxConfirm.View v) {
        box.removeAllViews();
        for (TxConfirm.Row r : v.rows) {
            TextView t;
            if (r.kind == TxConfirm.OFFICE) {
                android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(r.label + ": ");
                int s0 = sb.length();
                sb.append(r.value);
                sb.setSpan(new android.text.style.ForegroundColorSpan(red), s0, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                t = text("", 17, ink, true);
                t.setText(sb);
                t.setPadding(0, dp(8), 0, dp(2));
            } else if (r.kind == TxConfirm.AFTER) t = text("   " + r, 15, primaryInk, true);
            else if (r.kind == TxConfirm.BALANCE) t = text("   " + r, 14, ink, false);
            else if (r.kind == TxConfirm.NOTE) t = text(r.toString(), 13, goldInk, false);
            else t = text(r.toString(), 14, ink, false);
            box.addView(t);
        }
    }

    /**
     * 10.5 (§11): نافذة تأكيد الحركة بالأرصدة من طبقة البيانات. تُحدَّث كل ثانيتين وهي مفتوحة؛ إن تغيّر الرصيد يظهر تنبيه
     * بالأرقام الجديدة، وعند الضغط يُتحقق مرة أخيرة (إن تغيّر لا يُحفظ حتى يراجع المستخدم ويضغط من جديد). الضغطة الثانية لا تحفظ مرتين.
     */
    void confirmTx(String title, String prefix, final java.util.function.Supplier<TxConfirm.View> build, String yes, final Runnable onYes) {
        final TxConfirm.View[] shown = {build.get()};
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(20), dp(8), dp(20), dp(4));
        if (prefix != null && !prefix.trim().isEmpty()) l.addView(text(prefix.trim(), 14, red, true));
        final TextView changed = text("", 14, red, true);
        l.addView(changed);
        final LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        l.addView(rows);
        fillConfirm(rows, shown[0]);
        ScrollView sv = new ScrollView(this);
        sv.addView(l);
        final AlertDialog dlg = new AlertDialog.Builder(this).setTitle(title).setView(sv).setPositiveButton(yes, null).setNegativeButton("رجوع", null).create();
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        final boolean[] done = {false};
        final Runnable tick = new Runnable() {
            @Override public void run() {
                if (done[0] || !dlg.isShowing()) return;
                try {
                    TxConfirm.View now = build.get();
                    if (!now.fingerprint.equals(shown[0].fingerprint)) {
                        shown[0] = now;
                        fillConfirm(rows, now);
                        changed.setText("⚠ تغيّر الرصيد أثناء العرض (وصلت حركة أخرى). راجع الأرقام الجديدة.");
                    }
                } catch (Exception ignored) {
                    // يبقى العرض السابق
                }
                h.postDelayed(this, 2000);
            }
        };
        dlg.setOnShowListener(d -> {
            h.postDelayed(tick, 2000);
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (done[0]) return;
                TxConfirm.View now = build.get();
                if (!now.fingerprint.equals(shown[0].fingerprint)) {
                    shown[0] = now;
                    fillConfirm(rows, now);
                    changed.setText("⚠ تغيّر الرصيد لحظة الضغط. راجع الأرقام الجديدة ثم اضغط «" + yes + "» من جديد.");
                    return;
                }
                done[0] = true;
                v.setEnabled(false);
                dlg.dismiss();
                onYes.run();
            });
        });
        dlg.setOnDismissListener(d -> done[0] = true);
        dlg.show();
    }

    // ===================================================================================
    // فتح شاشات أخرى ومشاركة الملفات
    // ===================================================================================

    void startForResult(Intent i, int code) {
        try {
            markOwn();
            startActivityForResult(i, code);
        } catch (ActivityNotFoundException e) {
            ownActivity = false;
            error(new Exception("لا يوجد تطبيق على الهاتف يدعم هذه العملية."));
        }
    }

    boolean startOwn(Intent i) {
        try {
            markOwn();
            startActivity(i);
            return true;
        } catch (ActivityNotFoundException e) {
            ownActivity = false;
            return false;
        }
    }

    File sharedDir() {
        File d = new File(getCacheDir(), "shared");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static String safeName(String s) {
        String n = (s == null ? "" : s).replaceAll("[^\\p{L}\\p{N}._-]", "_");
        return n.length() > 60 ? n.substring(0, 60) : n;
    }

    Intent shareIntent(List<File> files, String mime, String subject) {
        ArrayList<Uri> uris = new ArrayList<>();
        for (File f : files) uris.add(EvidenceProvider.uriFor(this, f));
        Intent i;
        if (uris.size() == 1) {
            i = new Intent(Intent.ACTION_SEND);
            i.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        } else {
            i = new Intent(Intent.ACTION_SEND_MULTIPLE);
            i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        }
        i.setType(mime);
        i.putExtra(Intent.EXTRA_SUBJECT, subject);
        ClipData clip = ClipData.newRawUri(files.get(0).getName(), uris.get(0));
        for (int k = 1; k < uris.size(); k++) clip.addItem(new ClipData.Item(uris.get(k)));
        i.setClipData(clip);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return i;
    }

    void shareFiles(List<File> files, String mime, String subject) {
        if (files.isEmpty()) return;
        if (!startOwn(Intent.createChooser(shareIntent(files, mime, subject), "مشاركة أو حفظ"))) error(new Exception("لا يوجد تطبيق للمشاركة."));
    }

    // ===================================================================================
    // صورة الواجهة (شعار قابل للتغيير)
    // ===================================================================================

    private static String logoKey;
    private static Bitmap logoCache;

    Bitmap homeLogo() {
        String mode = db.setting("home_logo_mode", "default");
        if ("none".equals(mode)) return null;
        if ("custom".equals(mode)) {
            try {
                String raw = db.setting("home_logo", "");
                if (!raw.isEmpty() && raw.equals(logoKey) && logoCache != null && !logoCache.isRecycled()) return logoCache;
                byte[] b = android.util.Base64.decode(raw, android.util.Base64.NO_WRAP);
                Bitmap bm = BitmapFactory.decodeByteArray(b, 0, b.length);
                if (bm != null) {
                    logoKey = raw;
                    logoCache = bm;
                    return bm;
                }
            } catch (Exception ignored) {
                // نرجع للافتراضي
            }
        }
        return drawableBitmap(R.drawable.ic_logo, 320);
    }

    Bitmap drawableBitmap(int res, int size) {
        Drawable d = getDrawable(res);
        if (d == null) return null;
        Bitmap bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        d.setBounds(0, 0, size, size);
        d.draw(c);
        return bm;
    }

    static String date(long t) { return Model.date(t); }

    static String day(long t) { return Model.day(t); }

    String bal(long v, int dec) { return Model.bal(v, dec); }

    /** حاوية تلتف عناصرها على أسطر (للرقائق)، من اليمين لليسار. */
    static final class Flow extends ViewGroup {
        final int gapH, gapV;

        Flow(android.content.Context c, int gapH, int gapV) {
            super(c);
            this.gapH = gapH;
            this.gapV = gapV;
        }

        @Override protected void onMeasure(int ws, int hs) {
            int max = MeasureSpec.getSize(ws) - getPaddingLeft() - getPaddingRight();
            if (MeasureSpec.getMode(ws) == MeasureSpec.UNSPECIFIED) max = Integer.MAX_VALUE;
            int x = 0, y = 0, lineH = 0, widest = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                if (c.getVisibility() == GONE) continue;
                c.measure(MeasureSpec.makeMeasureSpec(Math.max(0, max), MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                int w = c.getMeasuredWidth(), h = c.getMeasuredHeight();
                if (x > 0 && x + w > max) {
                    x = 0;
                    y += lineH + gapV;
                    lineH = 0;
                }
                x += w + gapH;
                widest = Math.max(widest, x - gapH);
                lineH = Math.max(lineH, h);
            }
            int width = MeasureSpec.getMode(ws) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(ws) : widest + getPaddingLeft() + getPaddingRight();
            setMeasuredDimension(width, y + lineH + getPaddingTop() + getPaddingBottom());
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int max = r - l - getPaddingLeft() - getPaddingRight();
            boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
            int x = 0, y = getPaddingTop(), lineH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                if (c.getVisibility() == GONE) continue;
                int w = c.getMeasuredWidth(), h = c.getMeasuredHeight();
                if (x > 0 && x + w > max) {
                    x = 0;
                    y += lineH + gapV;
                    lineH = 0;
                }
                int left = rtl ? (r - l - getPaddingRight() - x - w) : getPaddingLeft() + x;
                c.layout(left, y, left + w, y + h);
                x += w + gapH;
                lineH = Math.max(lineH, h);
            }
        }
    }

    Flow flow() {
        Flow f = new Flow(this, dp(6), dp(6));
        f.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        return f;
    }

    /** إزالة عنصر من أبيه إن وُجد (لإعادة ترتيب العناصر). */
    static void detachView(View v) {
        if (v != null && v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
    }
}
