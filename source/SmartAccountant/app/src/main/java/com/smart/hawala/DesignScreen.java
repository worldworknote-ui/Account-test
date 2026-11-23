package com.smart.hawala;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * المظهر: الوضع والخط والألوان، شكل الصفحة الرئيسية (حديثة أو كلاسيكية) وعناصرها، الاسم والرسالة والشعار، والاختصارات.
 * كل تغيير يُحفظ فورًا ويظهر أثره مباشرة. «الافتراضي» يعيد شكل الإصدار 7.
 */
final class DesignScreen {
    static final String[][] PALETTE = {
            {"#0B6B53", "زمردي"}, {"#07513F", "أخضر داكن"}, {"#2E7D32", "أخضر"}, {"#00838F", "فيروزي"},
            {"#1565C0", "أزرق"}, {"#0D47A1", "كحلي"}, {"#283593", "نيلي"}, {"#6A1B9A", "بنفسجي"},
            {"#AD1457", "عنابي"}, {"#C62828", "أحمر"}, {"#E65100", "برتقالي"}, {"#C7922F", "ذهبي"},
            {"#8D6E63", "بني فاتح"}, {"#5D4037", "بني"}, {"#455A64", "رمادي أزرق"}, {"#212121", "أسود"}
    };
    static final String[][] BG_PALETTE = {
            {"#F7F4ED", "عاجي"}, {"#FFFFFF", "أبيض"}, {"#F1F5F9", "رمادي فاتح"}, {"#EEF7F2", "أخضر فاتح"},
            {"#FFF8E7", "كريمي"}, {"#EEF3FB", "أزرق فاتح"}, {"#F7EEF3", "وردي فاتح"}, {"#ECEFF1", "فضي"}
    };
    /** 11.3: خلفيات الوضع الليلي (الأولى = تلقائي). */
    static final String[][] NIGHT_PALETTE = {
            {"#111316", "رمادي محايد"}, {"#0B1411", "أخضر داكن (الأصلي)"}, {"#0F141B", "كحلي داكن"}, {"#141414", "فحمي"},
            {"#17141A", "بنفسجي داكن"}, {"#18130F", "بني داكن"}, {"#000000", "أسود"}
    };
    /** 11.3: ثيمات جاهزة مجرّبة في الوضعين: الاسم، الأساسي، الثانوي، الشريط العلوي، خلفية النهار، خلفية الليل. */
    static final String[][] PRESETS = {
            {"زمردي (الأصلي)", "#0B6B53", "#C7922F", "", "#F7F4ED", ""},
            {"كحلي مصرفي", "#1F3A5F", "#C9A227", "#1F3A5F", "#F2F4F8", "#0F141B"},
            {"فحمي نحاسي", "#2F3136", "#B87333", "#2F3136", "#F4F2EF", "#141414"},
            {"عنابي ذهبي", "#7A1F3D", "#C7922F", "", "#FAF6F2", "#17141A"}
    };
    static final String[] FONT_KEYS = {"plex", "cairo", "kufi", "system"};
    static final String[] FONT_NAMES = {"IBM Plex", "Cairo", "Noto Kufi", "خط الهاتف"};
    static final String[] THEME_KEYS = {"light", "dark", "auto"};

    /** قيم شكل الإصدار 7 الافتراضية (الاسم والرسالة لا تتغير عند الاستعادة). */
    static final String[][] DEFAULTS = {
            {"primary_color", "#0B6B53"}, {"accent_color", "#C7922F"}, {"header_color", ""}, {"bg_color", "#F7F4ED"},
            {"home_order", Db.DEFAULT_ORDER}, {"home_hidden", ""}, {"btn_colors", ""}, {"home_layout", "list"}, {"btn_size", "normal"},
            {"home_logo_mode", "default"}, {"home_logo_size", "medium"}, {"home_style", "modern"}, {"app_font", "plex"}, {"theme_mode", "light"}
    };
    static final String[] RESET_KEYS = {"home_widgets", "hw_net_curs", "hw_rates_curs", "hw_offices_mode", "hw_offices_n", "hw_tx_n",
            "hold_confirm", "page_motion", "btn_frames", "tab_style", "nav_prefs", "night_bg", "tab_tint"};

    /** 11.3: كل ما يُحفظ في «نمط» (الشكل كله، لا الاسم ولا الرسالة ولا صورة الشعار). */
    static List<String> styleKeys() {
        List<String> k = new ArrayList<>();
        for (String[] kv : DEFAULTS) k.add(kv[0]);
        for (String r : RESET_KEYS) if (!k.contains(r)) k.add(r);
        k.add("haptics");
        return k;
    }

    interface ColorCb { void on(String hex); }

    private final MainActivity a;
    /** صورة اختيرت وتنتظر الحفظ بعد العودة من معرض الصور. */
    private boolean pickingLogo;

    DesignScreen(MainActivity a) { this.a = a; }

    private String get(String k, String def) { return a.db.setting(k, def); }

    private void put(String k, String v) {
        a.db.setSettingQuiet(k, v);
    }

    void show() {
        // 10.12: المظهر لكل مستخدم وحده؛ الأجزاء المشتركة (الاسم والرسالة والشعار) لصاحب صلاحية التصميم فقط
        render();
    }

    private boolean shared() {
        return a.can(Auth.Perm.DESIGN);
    }

    private boolean perUser() {
        return a.db.usersEnabled() && SmartApp.currentUser != null;
    }

    // ===================================================================================
    // منتقي الألوان: لوحة متدرجة + ألوان جاهزة
    // ===================================================================================

    void paletteDialog(String title, String[][] palette, boolean allowDefault, final ColorCb cb) {
        paletteDialog(title, palette, allowDefault, null, cb);
    }

    void paletteDialog(String title, String[][] palette, boolean allowDefault, String current, final ColorCb cb) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(a.dp(16), a.dp(8), a.dp(16), a.dp(4));
        int init = Ui.parseColor(current, Ui.parseColor(palette[0][0], a.primary));
        final ColorPicker picker = new ColorPicker(a, init);
        // معاينة: اللون الحالي ← اللون الجديد، ورمزه
        LinearLayout pr = a.row();
        final View oldSw = new View(a);
        GradientDrawable og = new GradientDrawable();
        og.setColor(init);
        og.setCornerRadii(new float[]{0, 0, a.dp(12), a.dp(12), a.dp(12), a.dp(12), 0, 0});
        oldSw.setBackground(og);
        final View newSw = new View(a);
        final GradientDrawable ng = new GradientDrawable();
        ng.setColor(init);
        ng.setCornerRadii(new float[]{a.dp(12), a.dp(12), 0, 0, 0, 0, a.dp(12), a.dp(12)});
        newSw.setBackground(ng);
        pr.addView(newSw, new LinearLayout.LayoutParams(0, a.dp(44), 1));
        pr.addView(oldSw, new LinearLayout.LayoutParams(0, a.dp(44), 1));
        // 11.3: رمز اللون يُكتب أو يُلصق (#1F3A5F أو 1F3A5F)
        final EditText hex = a.field("#RRGGBB");
        hex.setText(hexOf(init));
        hex.setTextSize(14);
        hex.setGravity(Gravity.CENTER);
        hex.setTextDirection(View.TEXT_DIRECTION_LTR);
        hex.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        hex.setSingleLine(true);
        hex.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        pr.addView(hex, new LinearLayout.LayoutParams(a.dp(110), a.dp(44)));
        l.addView(pr, a.lp(-1, -2, 4));
        l.addView(a.text("حرّك إصبعك على المربع لاختيار درجة اللون، وعلى الشريط لاختيار اللون نفسه.", 12, a.muted, false));
        l.addView(picker, new LinearLayout.LayoutParams(-1, -2));
        final int[] chosen = {init};
        final boolean[] typing = {false};
        picker.listener = c -> {
            chosen[0] = c;
            ng.setColor(c);
            newSw.invalidate();
            if (!typing[0]) hex.setText(hexOf(c));
        };
        hex.addTextChangedListener(Ui.watcher(() -> {
            String t = hex.getText().toString().trim().replace("#", "").replace(" ", "");
            if (!t.matches("[0-9A-Fa-f]{6}")) return;
            int c = Color.parseColor("#" + t);
            if (c == chosen[0]) return;
            typing[0] = true;
            picker.setColor(c);
            picker.listener.changed(c);
            typing[0] = false;
        }));
        l.addView(a.text("ألوان جاهزة", 13, a.ink, true));
        Ui.Flow fl = a.flow();
        for (final String[] p : palette) {
            final int col = Color.parseColor(p[0]);
            View sw = a.swatch(col, 34);
            sw.setContentDescription(p[1]);
            sw.setOnClickListener(v -> {
                picker.setColor(col);
                picker.listener.changed(col);
            });
            fl.addView(sw, new android.view.ViewGroup.LayoutParams(a.dp(34), a.dp(34)));
        }
        l.addView(fl, a.lp(-1, -2, 4));
        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        sv.addView(l);
        AlertDialog.Builder b = new AlertDialog.Builder(a).setTitle(title).setView(sv)
                .setPositiveButton("اختيار هذا اللون", (d, w) -> cb.on(hexOf(chosen[0])))
                .setNegativeButton("إلغاء", null);
        if (allowDefault) b.setNeutralButton("الافتراضي", (d, w) -> cb.on(""));
        b.show();
    }

    static String hexOf(int c) { return String.format(Locale.US, "#%02X%02X%02X", Color.red(c), Color.green(c), Color.blue(c)); }

    // ===================================================================================
    // الشاشة
    // ===================================================================================

    private void render() {
        a.here(this::render);
        a.tabbedScreen("المظهر والصفحة الرئيسية", a.moreScreen::show, "more");
        a.hint("كل تغيير يُحفظ فورًا. «استعادة الافتراضي» في الأسفل تعيد الشكل الأصلي.");
        if (perUser()) a.hint("الوضع والخط والألوان والاهتزاز والصفحة الرئيسية والشريط السفلي خاصة بك وحدك: لا تتغيّر عند غيرك من المستخدمين.");

        // ---------------------------------------------------------------- 11.3: ثيمات جاهزة وأنماطي
        a.title("ثيمات جاهزة");
        Ui.Flow pf = a.flow();
        for (final String[] pz : PRESETS) pf.addView(presetChip(pz), new android.view.ViewGroup.LayoutParams(-2, -2));
        a.body.addView(pf, a.lp(-1, -2, 2));
        a.hint("ألوان مجرّبة في الفاتح والليلي. الشكل الحالي يُحفظ تلقائيًا في «أنماطي» قبل التطبيق، فتعود إليه متى شئت.");
        myStyles();

        // ---------------------------------------------------------------- الوضع والخط
        a.title("الوضع");
        final String theme0 = get("theme_mode", "light");
        a.seg(new String[]{"فاتح", "ليلي", "حسب الهاتف"}, indexOf(THEME_KEYS, theme0), i -> {
            if (THEME_KEYS[i].equals(get("theme_mode", "light"))) return;
            put("theme_mode", THEME_KEYS[i]);
            a.restyle("design");
        });
        a.hint(Ui.DESKTOP ? "«حسب الهاتف» يتبع الوضع الليلي في إعدادات الجهاز تلقائيًا." : "«حسب الهاتف» يتبع الوضع الليلي في إعدادات الهاتف تلقائيًا.");

        // 11.0: على الماك والويندوز فقط — شكل البرنامج وحجم النافذة (لهذا الجهاز)
        if (Ui.DESKTOP && Ui.deskDesign != null) Ui.deskDesign.accept(a);

        // 10.12: اهتزاز خفيف عند الضغط على الأزرار المهمة (التسجيل، الحفظ، الشريط السفلي، الاختيارات)
        a.title("الاهتزاز عند الضغط");
        a.seg(new String[]{"تشغيل", "إيقاف"}, a.hapticsOn() ? 0 : 1, i -> {
            put("haptics", i == 0 ? "on" : "off");
            if (i == 0) a.tick(a.body);
        });
        a.hint("رجّة صغيرة عند الضغط على الأزرار المهمة. تعمل إن كانت «الاهتزاز عند اللمس» مفعّلة في إعدادات الهاتف.");

        // 10.13: خيارات الشكل والسلوك — كلها لك وحدك
        a.title("زر التسجيل");
        a.seg(new String[]{"ضغطة عادية", "ضغط مطوّل"}, a.holdOn() ? 1 : 0, i -> put("hold_confirm", i == 1 ? "on" : "off"));
        a.hint("في الحالتين تظهر نافذة التأكيد قبل الحفظ. «ضغط مطوّل»: يمتلئ الزر تحت إصبعك أولًا، فلا تحفظ بلمسة خاطئة.");

        a.title("حركة الصفحات");
        String pm = a.pageMotion();
        a.seg(new String[]{"متتابعة", "دفعة واحدة", "بلا حركة"}, "whole".equals(pm) ? 1 : "none".equals(pm) ? 2 : 0,
                i -> put("page_motion", i == 1 ? "whole" : i == 2 ? "none" : "stagger"));
        a.hint("«متتابعة»: عناصر الصفحة تظهر واحدًا بعد الآخر. «دفعة واحدة»: الصفحة كلها معًا.");

        a.title("إطارات الأزرار والبطاقات");
        a.seg(new String[]{"ظاهرة", "بلا إطار"}, a.frames ? 0 : 1, i -> {
            put("btn_frames", i == 0 ? "on" : "off");
            a.applyColors();
            render();
        });

        // 10.15: كل إعدادات الشريط السفلي (النمط والألوان والترتيب وقائمة «+») في «المزيد ← الشريط السفلي»
        a.title("الشريط السفلي");
        a.button("الشريط السفلي: " + NavPrefs.styleName(a.tabStyle()) + " — تخصيص…", a.accent, v -> a.tabsScreen.show());

        a.title("الخط");
        final Ui.Seg font = a.new Seg(FONT_NAMES, null, indexOf(FONT_KEYS, get("app_font", "plex")), false);
        for (int i = 0; i < font.buttons.size(); i++) {
            font.buttons.get(i).setTypeface(typeface(FONT_KEYS[i]));
            font.buttons.get(i).setTextSize(13);
        }
        font.listener = i -> {
            if (FONT_KEYS[i].equals(get("app_font", "plex"))) return;
            put("app_font", FONT_KEYS[i]);
            a.restyle("design");
        };
        a.body.addView(font.view, a.lp(-1, -2, 2));
        TextView sample = a.text("المحاسب الذكي — لنا 1,250.00 USD — حمدي احمد محمد", 16, a.ink, false);
        sample.setBackground(a.round(a.surface, a.line, 12));
        sample.setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10));
        a.body.addView(sample, a.lp(-1, -2, 4));

        // ---------------------------------------------------------------- الألوان
        a.title("الألوان");
        colorRow("اللون الأساسي (الأزرار والعناوين)", "primary_color", "#0B6B53", PALETTE, false);
        colorRow("اللون الثانوي (الذهبي)", "accent_color", "#C7922F", PALETTE, false);
        colorRow("لون الشريط العلوي", "header_color", "", PALETTE, true);
        colorRow("لون الخلفية" + (a.night ? " (في الوضع الفاتح)" : ""), "bg_color", "#F7F4ED", BG_PALETTE, false);
        colorRow("خلفية الوضع الليلي", "night_bg", "", NIGHT_PALETTE, true);
        a.add(a.label("لون الزر المختار في الشريط السفلي"));
        a.seg(new String[]{"اللون الأساسي", "لون لكل تبويب"}, "multi".equals(get("tab_tint", "primary")) ? 1 : 0, i -> {
            put("tab_tint", i == 1 ? "multi" : "primary");
            a.restyle("design");
        });

        // ---------------------------------------------------------------- الصفحة الرئيسية
        a.title("الصفحة الرئيسية");
        final boolean classic = "classic".equals(get("home_style", "modern"));
        a.seg(new String[]{"حديثة", "كلاسيكية (أزرار كبيرة)"}, classic ? 1 : 0, i -> {
            put("home_style", i == 1 ? "classic" : "modern");
            render();
        });
        if (!classic) {
            a.hint("الحديثة: أنت تختار عناصرها وترتيبها (الأرصدة، اليوم، آخر الحركات، المكاتب، الاختصارات…).");
            a.button("اختيار عناصر الصفحة الرئيسية وترتيبها", a.primary, v -> a.homeScreen.editor());
        } else {
            a.hint("الكلاسيكية: الشعار والاسم والرسالة ثم أزرار كبيرة كما في الإصدارات السابقة، بترتيبك وألوانك أدناه.");
            a.add(a.label("شكل الأزرار"));
            a.seg(new String[]{"زر في كل سطر", "زران في كل سطر"}, "grid".equals(get("home_layout", "list")) ? 1 : 0, i -> put("home_layout", i == 1 ? "grid" : "list"));
            a.seg(new String[]{"حجم عادي", "حجم كبير"}, "big".equals(get("btn_size", "normal")) ? 1 : 0, i -> put("btn_size", i == 1 ? "big" : "normal"));
        }

        // ---------------------------------------------------------------- الاسم والرسالة (مشتركة: لصاحب صلاحية التصميم)
        final EditText title, office, welcome;
        if (shared()) {
        a.title("الاسم والرسالة");
        title = textSetting("اسم البرنامج (يظهر إن لم تكتب اسم مكتبك)", "app_title", "المحاسب الذكي لمكاتب الحوالات");
        office = textSetting("اسم مكتبك (في أعلى الرئيسية وفي الكشوفات)", "office_name", "");
        welcome = textSetting("الرسالة الترحيبية", "welcome", "");
        welcome.setSingleLine(false);
        welcome.setMinLines(2);
        welcome.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        welcome.setLayoutParams(a.lp(-1, -2, 4));
        welcome.setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(10));
        a.hint("اكتب {المكتب} داخل الرسالة ليُستبدل باسم مكتبك. مثال: أهلًا بكم في {المكتب}");

        // ---------------------------------------------------------------- الشعار
        a.title("الشعار");
        a.hint("يظهر في أعلى الصفحة الرئيسية وفي رأس الكشوفات.");
        String mode = get("home_logo_mode", "default");
        a.seg(new String[]{"الافتراضي", "صورتي", "بدون"}, "custom".equals(mode) ? 1 : "none".equals(mode) ? 2 : 0, i -> {
            if (i == 1) {
                if (get("home_logo", "").isEmpty()) {
                    pickLogo();
                    return;
                }
                put("home_logo_mode", "custom");
            } else put("home_logo_mode", i == 2 ? "none" : "default");
            render();
        });
        if ("custom".equals(mode)) a.button("تغيير الصورة…", a.accent, v -> pickLogo());
        if (!"none".equals(mode) && "classic".equals(get("home_style", "modern"))) {
            String size = get("home_logo_size", "medium");
            a.seg(new String[]{"صغير", "متوسط", "كبير"}, "small".equals(size) ? 0 : "large".equals(size) ? 2 : 1,
                    i -> put("home_logo_size", i == 0 ? "small" : i == 2 ? "large" : "medium"));
        }
        } else {
            title = office = welcome = null;
        }

        // ---------------------------------------------------------------- الاختصارات / الأزرار
        a.title(classic ? "أزرار الصفحة الرئيسية" : "الاختصارات");
        a.hint("اسحب «≡» للترتيب (أو ▲ و▼)، الدائرة للون، والمربع للإظهار أو الإخفاء. زر الإعدادات يبقى ظاهرًا دائمًا.");
        final LinearLayout orderBox = new LinearLayout(a);
        orderBox.setOrientation(LinearLayout.VERTICAL);
        a.body.addView(orderBox);
        final Runnable[] drawOrder = new Runnable[1];
        // 10.13: الترتيب بالسحب «≡» إلى جانب الأسهم
        final Ui.DragSort ds = a.new DragSort(orderBox, keys -> {
            List<String> ks = new ArrayList<>();
            for (Object o : keys) ks.add((String) o);
            put("home_order", BackupManager.join(ks, ","));
            drawOrder[0].run();
        });
        drawOrder[0] = () -> {
            orderBox.removeAllViews();
            final List<String> order = MainActivity.parseOrder(get("home_order", Db.DEFAULT_ORDER));
            final Set<String> hidden = MainActivity.parseSet(get("home_hidden", ""));
            final Map<String, String> cols = MainActivity.parseColors(get("btn_colors", ""));
            Map<String, String> names = MainActivity.homeNames();
            for (int i = 0; i < order.size(); i++) {
                final int idx = i;
                final String k = order.get(i);
                LinearLayout r = a.row();
                r.setBackground(a.round(a.surface, a.line, 12));
                r.setPadding(a.dp(4), a.dp(2), a.dp(4), a.dp(2));
                r.setGravity(Gravity.CENTER_VERTICAL);
                TextView hd = ds.handle();
                r.addView(hd, new LinearLayout.LayoutParams(a.dp(32), a.dp(42)));
                ds.bind(r, hd, k);
                r.addView(a.smallButton("▲", Color.rgb(120, 120, 120), v -> {
                    if (idx == 0) return;
                    order.remove(idx);
                    order.add(idx - 1, k);
                    put("home_order", BackupManager.join(order, ","));
                    drawOrder[0].run();
                }), new LinearLayout.LayoutParams(a.dp(42), a.dp(42)));
                r.addView(a.smallButton("▼", Color.rgb(120, 120, 120), v -> {
                    if (idx >= order.size() - 1) return;
                    order.remove(idx);
                    order.add(idx + 1, k);
                    put("home_order", BackupManager.join(order, ","));
                    drawOrder[0].run();
                }), new LinearLayout.LayoutParams(a.dp(42), a.dp(42)));
                r.addView(a.text(names.get(k), 14, a.ink, true), new LinearLayout.LayoutParams(0, -2, 1));
                int col = Ui.parseColor(cols.get(k), a.primary);
                View sw = a.swatch(col, 32);
                sw.setOnClickListener(v -> paletteDialog("لون: " + MainActivity.homeNames().get(k), PALETTE, true, cols.get(k), hex -> {
                    Map<String, String> m = MainActivity.parseColors(get("btn_colors", ""));
                    if (hex.isEmpty()) m.remove(k);
                    else m.put(k, hex);
                    put("btn_colors", MainActivity.joinColors(m));
                    drawOrder[0].run();
                }));
                LinearLayout.LayoutParams swp = new LinearLayout.LayoutParams(a.dp(32), a.dp(32));
                swp.setMargins(a.dp(6), 0, a.dp(6), 0);
                r.addView(sw, swp);
                CheckBox vis = new CheckBox(a);
                vis.setChecked(!hidden.contains(k));
                if (k.equals("settings")) vis.setEnabled(false);
                vis.setOnCheckedChangeListener((b, c) -> {
                    Set<String> h = MainActivity.parseSet(get("home_hidden", ""));
                    if (c) h.remove(k);
                    else h.add(k);
                    put("home_hidden", BackupManager.join(new ArrayList<>(h), ","));
                });
                r.addView(vis, new LinearLayout.LayoutParams(-2, -2));
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
                rp.setMargins(0, a.dp(3), 0, a.dp(3));
                orderBox.addView(r, rp);
            }
        };
        drawOrder[0].run();

        // ---------------------------------------------------------------- الشريط الثابت
        LinearLayout foot = a.row();
        foot.addView(a.makeButton("تم — عرض الرئيسية", a.primary, v -> {
            if (title != null) saveTexts(title, office, welcome);
            a.showHome();
        }), new LinearLayout.LayoutParams(0, a.dp(50), 1));
        android.widget.Button reset = a.makeButton("استعادة الافتراضي", Color.rgb(120, 120, 120), v -> new AlertDialog.Builder(a).setTitle("استعادة الشكل الافتراضي")
                .setMessage("تعود الألوان والخط والوضع والصفحة الرئيسية وعناصرها والاختصارات إلى الشكل الأصلي. اسم مكتبك ورسالتك لا يتغيران.\n\nالشكل الكلاسيكي يبقى متاحًا كخيار في «الصفحة الرئيسية».")
                .setPositiveButton("استعادة", (d, w) -> resetDefaults()).setNegativeButton("إلغاء", null).show());
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, a.dp(50));
        rp.setMargins(a.dp(8), 0, 0, 0);
        foot.addView(reset, rp);
        a.footer(foot);
    }

    private EditText textSetting(String hint, final String key, String def) {
        a.add(a.label(hint));
        final EditText e = a.input(hint);
        e.setText(get(key, def));
        e.addTextChangedListener(Ui.watcher(() -> {
            String v = e.getText().toString();
            if (key.equals("app_title") && v.trim().isEmpty()) return;
            put(key, v.trim());
        }));
        return e;
    }

    private void saveTexts(EditText title, EditText office, EditText welcome) {
        String t = title.getText().toString().trim();
        put("app_title", t.isEmpty() ? "المحاسب الذكي لمكاتب الحوالات" : t);
        put("office_name", office.getText().toString().trim());
        put("welcome", welcome.getText().toString().trim());
        a.db.audit("SETTING", "settings", 0, "design");
    }

    private void resetDefaults() {
        autoSave("قبل استعادة الافتراضي");
        String oldFont = get("app_font", "plex"), oldTheme = get("theme_mode", "light");
        // 10.12: مستخدم بلا صلاحية التصميم يعيد مظهره هو فقط (لا الشعار المشترك)
        for (String[] kv : DEFAULTS) if (shared() || Db.personal(kv[0])) put(kv[0], kv[1]);
        if (perUser()) a.db.deleteSetting("haptics");
        for (String k : RESET_KEYS) a.db.deleteSetting(k);
        a.db.audit("SETTING", "settings", 0, "design-reset");
        a.toast("عاد الشكل الافتراضي للإصدار 7");
        if (!"plex".equals(oldFont) || !"light".equals(oldTheme)) a.restyle("design");
        else {
            a.applyColors();
            render();
        }
    }

    // ===================================================================================
    // 11.3: الثيمات الجاهزة وأنماطي المحفوظة
    // ===================================================================================

    private View presetChip(final String[] pz) {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER);
        c.setPadding(a.dp(10), a.dp(8), a.dp(10), a.dp(8));
        c.setBackground(a.ripple(a.round(a.surface, a.line, 12), 12));
        LinearLayout sw = new LinearLayout(a);
        sw.setOrientation(LinearLayout.HORIZONTAL);
        sw.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        int[] cols = {Ui.parseColor(pz[1], a.primary), Ui.parseColor(pz[2], a.accent), Ui.parseColor(pz[4], a.bg),
                pz[5].isEmpty() ? Color.rgb(11, 20, 17) : Ui.parseColor(pz[5], Color.BLACK)};
        for (int col : cols) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(a.dp(18), a.dp(18));
            lp.setMargins(a.dp(1), 0, a.dp(1), 0);
            sw.addView(a.swatch(col, 18), lp);
        }
        c.addView(sw);
        c.addView(a.text(pz[0], 13, a.ink, true));
        c.setContentDescription("ثيم " + pz[0]);
        c.setOnClickListener(v -> new AlertDialog.Builder(a).setTitle("ثيم «" + pz[0] + "»")
                .setMessage("تتغيّر الألوان وخلفية الليل والزر المختار. شكلك الحالي يُحفظ أولًا في «أنماطي» لتعود إليه.")
                .setPositiveButton("تطبيق", (d, w) -> applyPreset(pz)).setNegativeButton("إلغاء", null).show());
        return c;
    }

    private void applyPreset(String[] pz) {
        autoSave("قبل ثيم «" + pz[0] + "»");
        put("primary_color", pz[1]);
        put("accent_color", pz[2]);
        put("header_color", pz[3]);
        put("bg_color", pz[4]);
        put("night_bg", pz[5]);
        put("tab_tint", "primary");
        a.db.audit("SETTING", "settings", 0, "design-preset");
        a.toast("طُبّق ثيم «" + pz[0] + "»");
        a.restyle("design");
    }

    private org.json.JSONArray styles() {
        try {
            return new org.json.JSONArray(get("design_styles", "[]"));
        } catch (Exception e) {
            return new org.json.JSONArray();
        }
    }

    private org.json.JSONObject snapshot() throws org.json.JSONException {
        org.json.JSONObject v = new org.json.JSONObject();
        for (String k : styleKeys()) {
            String x = a.db.setting(k, null);
            v.put(k, x == null ? org.json.JSONObject.NULL : x);
        }
        return v;
    }

    /** يحفظ الشكل الحالي باسم (نمط يدوي)، أو تلقائيًا قبل تغيير كبير (يبقى آخر 3 تلقائية فقط). */
    private void saveStyle(String name, boolean auto) {
        try {
            org.json.JSONArray old = styles(), out = new org.json.JSONArray();
            org.json.JSONObject st = new org.json.JSONObject().put("name", name).put("at", Clock.now()).put("auto", auto).put("v", snapshot());
            out.put(st);
            int autos = auto ? 1 : 0;
            for (int i = 0; i < old.length(); i++) {
                org.json.JSONObject o = old.getJSONObject(i);
                if (!auto && o.optString("name").equals(name) && !o.optBoolean("auto")) continue;
                if (o.optBoolean("auto") && ++autos > 3) continue;
                if (out.length() >= 20) break;
                out.put(o);
            }
            put("design_styles", out.toString());
        } catch (Exception e) {
            a.error(e);
        }
    }

    private void autoSave(String why) { saveStyle(why + " — " + Model.date(Clock.now()), true); }

    private void applyStyle(org.json.JSONObject st) {
        autoSave("قبل «" + st.optString("name") + "»");
        org.json.JSONObject v = st.optJSONObject("v");
        if (v == null) return;
        for (String k : styleKeys()) {
            if (!v.has(k)) continue;
            if (v.isNull(k)) a.db.deleteSetting(k);
            else put(k, v.optString(k));
        }
        a.db.audit("SETTING", "settings", 0, "design-style");
        a.toast("عاد نمط «" + st.optString("name") + "»");
        a.restyle("design");
    }

    private void myStyles() {
        a.title("أنماطي");
        final org.json.JSONArray list = styles();
        LinearLayout g = a.group();
        for (int i = 0; i < list.length(); i++) {
            final org.json.JSONObject st = list.optJSONObject(i);
            if (st == null) continue;
            final int idx = i;
            View row = a.groupRow(g, st.optBoolean("auto") ? R.drawable.ic_sync : R.drawable.ic_edit, a.primaryInk, st.optString("name"),
                    st.optBoolean("auto") ? "حُفظ تلقائيًا" : "حفظته في " + Model.date(st.optLong("at")), "تطبيق", v -> applyStyle(st));
            row.setOnLongClickListener(v -> {
                new AlertDialog.Builder(a).setTitle(st.optString("name"))
                        .setItems(new String[]{"تطبيق", "حذف"}, (d, w) -> {
                            if (w == 0) applyStyle(st);
                            else deleteStyle(idx);
                        }).show();
                return true;
            });
        }
        if (list.length() == 0) a.hint("لا أنماط بعد. احفظ شكلك الحالي لتعود إليه بعد أي تجربة.");
        else a.hint("اضغط مطولًا على نمط لحذفه.");
        a.button("حفظ الشكل الحالي كنمط…", a.accent, v -> {
            final EditText name = a.field("اسم النمط");
            name.setText("نمطي " + (list.length() + 1));
            new AlertDialog.Builder(a).setTitle("حفظ الشكل الحالي").setView(name)
                    .setPositiveButton("حفظ", (d, w) -> {
                        String n = name.getText().toString().trim();
                        saveStyle(n.isEmpty() ? "نمطي" : n, false);
                        a.toast("حُفظ «" + (n.isEmpty() ? "نمطي" : n) + "»");
                        render();
                    }).setNegativeButton("إلغاء", null).show();
        });
    }

    private void deleteStyle(int idx) {
        org.json.JSONArray l = styles(), out = new org.json.JSONArray();
        for (int i = 0; i < l.length(); i++) if (i != idx) out.put(l.opt(i));
        put("design_styles", out.toString());
        render();
    }

    private void colorRow(String label, final String key, final String def, final String[][] palette, final boolean allowDefault) {
        final LinearLayout r = a.row();
        r.setBackground(a.ripple(a.round(a.surface, a.line, 12), 12));
        r.setPadding(a.dp(12), a.dp(8), a.dp(10), a.dp(8));
        r.addView(a.text(label, 14, a.ink, true), new LinearLayout.LayoutParams(0, -2, 1));
        String v = get(key, def);
        boolean auto = v == null || v.trim().isEmpty();
        boolean nightKey = key.equals("night_bg");
        int col = auto ? (key.equals("header_color") ? a.bg : nightKey ? (a.night ? a.bg : Color.rgb(17, 19, 22)) : a.primary) : Ui.parseColor(v, a.primary);
        r.addView(a.text(auto ? (nightKey ? "تلقائي" : "بلون الخلفية") : v.toUpperCase(Locale.US), 12, a.muted, false), new LinearLayout.LayoutParams(-2, -2));
        r.addView(a.swatch(col, 34), new LinearLayout.LayoutParams(a.dp(34), a.dp(34)));
        r.setOnClickListener(x -> paletteDialog(label, palette, allowDefault, auto ? null : v, h -> {
            put(key, h);
            if (nightKey && a.night) a.restyle("design");
            else {
                a.applyColors();
                render();
            }
        }));
        a.body.addView(r, a.lp(-1, -2, 4));
    }

    static int indexOf(String[] keys, String v) {
        for (int i = 0; i < keys.length; i++) if (keys[i].equals(v)) return i;
        return 0;
    }

    android.graphics.Typeface typeface(String key) {
        try {
            switch (key) {
                case "cairo": return a.getResources().getFont(R.font.cairo);
                case "kufi": return a.getResources().getFont(R.font.kufi);
                case "system": return android.graphics.Typeface.DEFAULT;
                default: return a.getResources().getFont(R.font.plex);
            }
        } catch (Exception e) {
            return android.graphics.Typeface.DEFAULT;
        }
    }

    // ===================================================================================
    // الشعار
    // ===================================================================================

    private void pickLogo() {
        pickingLogo = true;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        a.startForResult(i, MainActivity.PICK_LOGO);
    }

    /** يصغّر الصورة إلى 512 بكسل كحد أقصى ويحفظها PNG إن كانت شفافة وإلا JPEG، كنص base64. */
    static String encodeLogo(byte[] raw) throws Exception {
        if (!ImageUtil.isImage(raw)) throw new Exception("الملف المختار ليس صورة صالحة.");
        Bitmap bm = ImageUtil.decodeSampled(raw, 1024);
        if (bm == null) throw new Exception("تعذر قراءة الصورة.");
        int max = 512;
        int w = bm.getWidth(), h = bm.getHeight();
        if (w > max || h > max) {
            float f = Math.min(max / (float) w, max / (float) h);
            Bitmap s = Bitmap.createScaledBitmap(bm, Math.max(1, Math.round(w * f)), Math.max(1, Math.round(h * f)), true);
            if (s != bm) bm.recycle();
            bm = s;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (bm.hasAlpha()) bm.compress(Bitmap.CompressFormat.PNG, 100, out);
        else bm.compress(Bitmap.CompressFormat.JPEG, 88, out);
        bm.recycle();
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    void onLogoResult(int c, Intent data) {
        if (!pickingLogo) return;
        pickingLogo = false;
        if (c != Activity.RESULT_OK || data == null || data.getData() == null) {
            render();
            return;
        }
        final Uri u = data.getData();
        a.busy("جارٍ تجهيز الصورة…", p -> {
            try (InputStream in = a.getContentResolver().openInputStream(u)) {
                if (in == null) throw new Exception("تعذر فتح الصورة.");
                return encodeLogo(Db.readAll(in));
            }
        }, res -> {
            put("home_logo", (String) res);
            put("home_logo_mode", "custom");
            a.toast("تم حفظ الصورة");
            render();
        });
    }
}
