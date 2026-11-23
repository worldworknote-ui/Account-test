package com.smart.hawala;

import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 10.2: تخصيص الشريط السفلي: ماذا يظهر فيه وبأي ترتيب (من اليمين إلى اليسار).
 * «المزيد» يبقى دائمًا (منه كل الإعدادات)، والحد 6 عناصر في الهاتف وحتى 10 في الشاشات العريضة (11.5). ما لا يملك المستخدم صلاحيته لا يظهر له مهما كان الترتيب.
 * 10.15: كل إعدادات الشريط هنا (من «المزيد ← الشريط السفلي»): النمط (الزجاجي بتجويف من «أموالي»، أو الجزيرة، أو الدائرة)،
 * وشكل الشريط الزجاجي وحجمه وألوانه وشفافيته ولون الدائرة والأيقونات والأسماء، وقائمة «+» (محتواها وترتيبها ولون كل زر
 * وشكلها وحجمها). كل تغيير يُحفظ فورًا لهذا المستخدم وحده ويظهر في الشريط أسفل الشاشة.
 */
final class TabsScreen {
    private final MainActivity a;

    TabsScreen(MainActivity a) { this.a = a; }

    /** 11.5: الحد الأدنى للحد (الهاتف)؛ الحد الفعلي من عرض الشاشة: {@link MainActivity#maxTabs()}. */
    static final int MIN_MAX = 6;

    private NavPrefs p() { return NavPrefs.from(a.nav().toJson()); }

    private void save(NavPrefs p) {
        a.saveNav(p);
        show();
    }

    void show() {
        a.here(this::show);
        a.tabbedScreen("الشريط السفلي", a.moreScreen::show, "more");
        final String style = a.tabStyle();
        final boolean notch = NavPrefs.STYLE_NOTCH.equals(style);
        final NavPrefs p = p();
        if (notch && p.plusMenu && a.fanKeys().size() >= 2 && a.savedTabs().contains("tx") && a.tabAllowed("tx"))
            a.headerAction(R.drawable.ic_eye, "معاينة قائمة «+»", v -> {
                if (a.tabs instanceof NotchBar) {
                    List<String> keys = a.tabKeys();
                    a.plusPressed((NotchBar) a.tabs, keys.indexOf("tx"), Math.max(0, keys.indexOf("more")));
                }
            });
        a.hint("كل تغيير يُحفظ فورًا ويظهر في الشريط أسفل الشاشة" + (a.db.usersEnabled() ? "، وهو لك وحدك لا يتغيّر عند غيرك من المستخدمين." : "."));

        // ===== النمط =====
        a.title("نمط الشريط");
        int si = Arrays.asList(NavPrefs.STYLES).indexOf(style);
        a.seg(NavPrefs.STYLE_NAMES, Math.max(0, si), i -> {
            a.db.setSettingQuiet("tab_style", NavPrefs.STYLES[i]);
            Ui.bubbleOpen = false;
            show();
        });
        a.hint(notch ? "«زجاجي بتجويف»: التبويب المفتوح دائرة بارزة بلونه تنزلق إلى التبويب الجديد، و«+» يفتح قائمة سريعة كالمروحة."
                : NavPrefs.STYLE_BUBBLE.equals(style) ? "«دائرة تتمدد»: الشريط دائرة واحدة، تضغطها فيتمدد يمينًا وشمالًا و«تسجيل» في الوسط."
                : "«جزيرة»: الشريط السابق — كبسولة عائمة، والتبويب الحالي في إطار بلونه، و«تسجيل» زر بارز في الوسط.");

        if (notch) {
            // ===== الشكل والحجم =====
            a.title("الشكل والحجم");
            LinearLayout c0 = a.card();
            c0.addView(a.text("الشكل", 14, a.ink, true));
            Ui.Seg shape = a.new Seg(NavPrefs.BAR_SHAPES, null, p.barFloat ? 1 : 0, false);
            shape.listener = i -> {
                p.barFloat = i == 1;
                save(p);
            };
            c0.addView(shape.view);
            c0.addView(a.text("الحجم", 14, a.ink, true), a.lp(-1, -2, 8));
            Ui.Seg bsz = a.new Seg(NavPrefs.SIZES, null, p.barSize, false);
            bsz.listener = i -> {
                p.barSize = i;
                save(p);
            };
            c0.addView(bsz.view);
            c0.addView(a.text("أسماء الأزرار تحت الأيقونات", 14, a.ink, true), a.lp(-1, -2, 8));
            Ui.Seg lb = a.new Seg(new String[]{"ظاهرة", "أيقونات فقط"}, null, p.labels ? 0 : 1, false);
            lb.listener = i -> {
                p.labels = i == 0;
                save(p);
            };
            c0.addView(lb.view);

            // ===== الألوان =====
            a.title("ألوان الشريط");
            LinearLayout c1 = a.card();
            c1.addView(a.text("لون الشريط", 14, a.ink, true));
            c1.addView(a.text("«تلقائي» = لون الشريط العلوي للتطبيق (يتبع ألوانك والوضع الليلي).", 12, a.muted, false));
            c1.addView(swatches(NavPrefs.BAR_COLORS, p.barColor, col -> {
                p.barColor = col;
                save(p);
            }));
            c1.addView(a.text("الشفافية", 14, a.ink, true), a.lp(-1, -2, 8));
            Ui.Seg al = a.new Seg(NavPrefs.ALPHA_NAMES, null, p.barAlpha, false);
            al.listener = i -> {
                p.barAlpha = i;
                save(p);
            };
            c1.addView(al.view);
            c1.addView(a.text("لون الدائرة (التبويب المفتوح)", 14, a.ink, true), a.lp(-1, -2, 8));
            c1.addView(a.text("«تلقائي» = لكل تبويب لونه (الرئيسية أزرق، المكاتب فيروزي، الصندوق ذهبي…).", 12, a.muted, false));
            c1.addView(swatches(NavPrefs.CIRCLE_COLORS, p.circleColor, col -> {
                p.circleColor = col;
                save(p);
            }));
            c1.addView(a.text("لون الأيقونات", 14, a.ink, true), a.lp(-1, -2, 8));
            c1.addView(swatches(NavPrefs.ICON_COLORS, p.iconColor, col -> {
                p.iconColor = col;
                save(p);
            }));
        }

        // ===== الأزرار وترتيبها =====
        a.title("أزرار الشريط وترتيبها");
        a.hint("اختر ما يظهر في الشريط ورتّبه بسحب «≡» (أو بالأسهم): الأول يظهر في أقصى اليمين والأخير في أقصى اليسار. حتى " + a.maxTabs() + " عناصر، و«المزيد» دائمًا."
                + (notch ? " ضع «تسجيل» في الوسط ليكون زر «+»." : ""));
        tabsList();

        if (notch) fanSection(p);

        a.button("الترتيب الافتراضي للأزرار (الصندوق في أقصى اليسار)", a.accent, v -> {
            a.db.deleteSetting("tabs");
            show();
        });
        a.body.addView(a.smallButton("استعادة إعدادات الشريط الافتراضية", Color.rgb(130, 130, 130), v -> a.confirm("استعادة الافتراضي؟",
                "يعود النمط إلى «زجاجي بتجويف» وتعود ألوانه وحجمه وقائمة «+» كما كانت. ترتيب الأزرار لا يتغيّر.", "استعادة", () -> {
                    a.db.deleteSetting("tab_style");
                    a.db.deleteSetting("nav_prefs");
                    Ui.bubbleOpen = false;
                    show();
                })), a.lp(-1, 44, 6));
    }

    // ===================================================================================
    // أزرار الشريط (10.2، والسحب من 10.13)
    // ===================================================================================

    private void tabsList() {
        final List<String> on = new ArrayList<>(a.savedTabs());
        final LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        a.body.addView(box);
        final Runnable[] draw = new Runnable[1];
        final Ui.DragSort ds = a.new DragSort(box, keys -> {
            on.clear();
            for (Object o : keys) on.add((String) o);
            saveTabs(on);
        });
        draw[0] = () -> {
            box.removeAllViews();
            List<String> order = new ArrayList<>(on);
            for (String[] t : Ui.TAB_ALL) if (!order.contains(t[0])) order.add(t[0]);
            for (final String k : order) {
                final boolean shown = on.contains(k);
                LinearLayout r = a.row();
                r.setPadding(a.dp(8), a.dp(6), a.dp(4), a.dp(6));
                r.setBackground(a.round(shown ? a.surface : Ui.mix(a.surface, a.bg, 0.6f), a.line, 14));
                r.setGravity(Gravity.CENTER_VERTICAL);
                if (shown) {
                    TextView h = ds.handle();
                    r.addView(h, new LinearLayout.LayoutParams(a.dp(32), a.dp(38)));
                    ds.bind(r, h, k);
                }
                r.addView(a.icon(Ui.tabIcon(k), k.equals("cash") ? a.goldInk : a.primaryInk, 22), new LinearLayout.LayoutParams(a.dp(22), a.dp(22)));
                TextView name = a.text("  " + Ui.tabName(k) + (shown ? "  (" + (on.indexOf(k) + 1) + ")" : "") + (a.tabAllowed(k) ? "" : " — بلا صلاحية"), 15,
                        shown ? a.ink : a.muted, shown);
                r.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
                if (shown) {
                    r.addView(a.smallButton("▲", Color.rgb(120, 120, 120), v -> {
                        int i = on.indexOf(k);
                        if (i > 0) {
                            on.remove(i);
                            on.add(i - 1, k);
                            saveTabs(on);
                        }
                    }), new LinearLayout.LayoutParams(a.dp(44), a.dp(38)));
                    r.addView(a.smallButton("▼", Color.rgb(120, 120, 120), v -> {
                        int i = on.indexOf(k);
                        if (i < on.size() - 1) {
                            on.remove(i);
                            on.add(i + 1, k);
                            saveTabs(on);
                        }
                    }), new LinearLayout.LayoutParams(a.dp(44), a.dp(38)));
                }
                Switch sw = new Switch(a);
                sw.setChecked(shown);
                sw.setEnabled(!k.equals("more"));
                sw.setOnCheckedChangeListener((b, c) -> {
                    if (c) {
                        if (on.size() >= a.maxTabs()) {
                            a.toast("الحد " + a.maxTabs() + " عناصر على هذه الشاشة. أخفِ عنصرًا أولًا" + (a.maxTabs() < 10 ? "، أو وسّع النافذة." : "."));
                            draw[0].run();
                            return;
                        }
                        on.add(on.indexOf("more") >= 0 ? on.indexOf("more") : on.size(), k);
                    } else {
                        if (on.size() <= 3) {
                            a.toast("ثلاثة عناصر على الأقل");
                            draw[0].run();
                            return;
                        }
                        on.remove(k);
                    }
                    saveTabs(on);
                });
                r.addView(sw, new LinearLayout.LayoutParams(-2, -2));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.bottomMargin = a.dp(6);
                box.addView(r, lp);
            }
        };
        draw[0].run();
    }

    private void saveTabs(List<String> on) {
        a.db.setSettingQuiet("tabs", String.join(",", on));
        // يُعاد رسم الشاشة نفسها ليظهر الشريط الجديد في الأسفل فورًا
        show();
    }

    static List<String> defaults() { return new ArrayList<>(Arrays.asList(Ui.TAB_KEYS)); }

    // ===================================================================================
    // 10.15: قائمة «+»
    // ===================================================================================

    private void fanSection(final NavPrefs p) {
        a.title("زر «+» (تسجيل)");
        Ui.Seg pm = a.new Seg(new String[]{"يفتح القائمة السريعة", "يفتح صفحة التسجيل"}, null, p.plusMenu ? 0 : 1, false);
        pm.listener = i -> {
            p.plusMenu = i == 0;
            save(p);
        };
        a.body.addView(pm.view, a.lp(-1, -2, 2));
        if (!a.savedTabs().contains("tx")) a.hint("«تسجيل» ليس في الشريط الآن: أظهره من القائمة أعلاه ليعمل زر «+».");
        if (!p.plusMenu) return;

        a.title("قائمة «+»");
        a.hint("اختر من " + NavPrefs.MIN_FAN + " إلى " + NavPrefs.MAX_FAN + " أزرار ورتّبها (الأول = أقصى اليمين). اضغط الدائرة الملوّنة لتغيير لون الزر أو إزالته. "
                + "ما لا يملك المستخدم صلاحيته لا يظهر له في القائمة.");
        LinearLayout f = a.group();
        for (int i = 0; i < p.fan.size(); i++) {
            final int k = i;
            final String key = p.fan.get(i);
            orderRow(f, MainActivity.fanIcon(key), p.itemColor(key), NavPrefs.itemName(key), a.fanAllowed(key) ? "في القائمة" : "في القائمة — بلا صلاحية فلا يظهر لك",
                    k > 0, k < p.fan.size() - 1, d -> {
                        NavPrefs.move(p.fan, k, d);
                        save(p);
                    }, v -> itemMenu(p, key));
        }
        LinearLayout more = a.card();
        more.addView(a.text("أزرار أخرى يمكن إضافتها", 14, a.ink, true));
        for (final String key : NavPrefs.ITEMS.keySet()) {
            if (p.fan.contains(key)) continue;
            CheckBox cb = new CheckBox(a);
            cb.setText(NavPrefs.itemName(key) + (a.fanAllowed(key) ? "" : " — بلا صلاحية"));
            cb.setTextColor(a.ink);
            cb.setOnCheckedChangeListener((x, on) -> {
                String err = p.toggleFan(key);
                if (err != null) {
                    a.toast(err);
                    x.setChecked(false);
                    return;
                }
                save(p);
            });
            more.addView(cb);
        }

        LinearLayout c = a.card();
        c.addView(a.text("شكل القائمة", 14, a.ink, true));
        Ui.Seg st = a.new Seg(NavPrefs.FAN_STYLES, null, p.fanStyle, false);
        st.listener = i -> {
            p.fanStyle = i;
            save(p);
        };
        c.addView(st.view);
        c.addView(a.text("حجم القائمة", 14, a.ink, true), a.lp(-1, -2, 8));
        Ui.Seg sz = a.new Seg(NavPrefs.SIZES, null, p.fanSize, false);
        sz.listener = i -> {
            p.fanSize = i;
            save(p);
        };
        c.addView(sz.view);
    }

    private void itemMenu(final NavPrefs p, final String key) {
        int[] cols = {(Integer) NavPrefs.ITEMS.get(key)[1], NavPrefs.rgb(31, 143, 90), NavPrefs.rgb(201, 63, 63), NavPrefs.rgb(30, 64, 110), NavPrefs.rgb(201, 162, 39),
                NavPrefs.rgb(13, 128, 128), NavPrefs.rgb(91, 70, 190), NavPrefs.rgb(190, 24, 93), NavPrefs.rgb(100, 116, 139), NavPrefs.rgb(24, 24, 27)};
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(a.dp(18), a.dp(8), a.dp(18), 0);
        final AlertDialog[] d = new AlertDialog[1];
        l.addView(a.text("لون الزر", 14, a.ink, true));
        l.addView(swatches(cols, p.itemColor(key), col -> {
            if (col == (int) (Integer) NavPrefs.ITEMS.get(key)[1]) p.itemColors.remove(key);
            else p.itemColors.put(key, col);
            d[0].dismiss();
            save(p);
        }));
        d[0] = new AlertDialog.Builder(a).setTitle(NavPrefs.itemName(key)).setView(l)
                .setNeutralButton("إزالة من القائمة", (x, w) -> {
                    String err = p.toggleFan(key);
                    if (err != null) a.toast(err);
                    else save(p);
                }).setNegativeButton("إغلاق", null).create();
        d[0].show();
    }

    // ===================================================================================
    // عناصر الشاشة
    // ===================================================================================

    interface OnColor { void picked(int c); }

    interface OnMove { void moved(int d); }

    /** صف من دوائر الألوان؛ المختار بحلقة. لون NavPrefs.AUTO يُرسم خانة «تلقائي». */
    private View swatches(int[] cols, int sel, final OnColor on) {
        Ui.Flow fl = a.flow();
        for (final int c : cols) {
            FrameLayout cell = new FrameLayout(a);
            View dot = new View(a);
            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.OVAL);
            boolean isAuto = c == NavPrefs.AUTO;
            gd.setColor(isAuto ? a.surface : c);
            gd.setStroke(a.dp(c == sel ? 3 : 1), c == sel ? a.primary : a.line);
            dot.setBackground(gd);
            cell.addView(dot, new FrameLayout.LayoutParams(a.dp(34), a.dp(34), Gravity.CENTER));
            if (isAuto) {
                TextView t = a.text("تلقائي", 9, a.muted, true);
                t.setGravity(Gravity.CENTER);
                cell.addView(t, new FrameLayout.LayoutParams(a.dp(34), a.dp(34), Gravity.CENTER));
            } else if (c == sel) {
                ImageView ok = a.icon(R.drawable.ic_check2, NavPrefs.light(c) ? a.primaryInk : Color.WHITE, 16);
                cell.addView(ok, new FrameLayout.LayoutParams(a.dp(16), a.dp(16), Gravity.CENTER));
            }
            cell.setContentDescription(isAuto ? "تلقائي" : "لون");
            cell.setOnClickListener(v -> on.picked(c));
            fl.addView(cell, new android.view.ViewGroup.LayoutParams(a.dp(42), a.dp(42)));
        }
        return fl;
    }

    /** صف بأيقونة ملوّنة واسم وسهمي رفع وخفض. */
    private void orderRow(LinearLayout g, int iconRes, int color, String title, String sub, boolean up, boolean down, final OnMove mv, View.OnClickListener tap) {
        LinearLayout r = a.row();
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(a.dp(12), a.dp(8), a.dp(8), a.dp(8));
        FrameLayout ic = new FrameLayout(a);
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(color);
        ic.setBackground(gd);
        ic.addView(a.icon(iconRes, Color.WHITE, 18), new FrameLayout.LayoutParams(a.dp(18), a.dp(18), Gravity.CENTER));
        if (tap != null) ic.setOnClickListener(tap);
        r.addView(ic, new LinearLayout.LayoutParams(a.dp(36), a.dp(36)));
        LinearLayout tx = new LinearLayout(a);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.setPadding(a.dp(10), 0, a.dp(10), 0);
        tx.addView(a.text(title, 15, a.ink, true));
        tx.addView(a.text(sub, 12, a.muted, false));
        if (tap != null) tx.setOnClickListener(tap);
        r.addView(tx, new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(arrow("↑", up, () -> mv.moved(-1)), new LinearLayout.LayoutParams(a.dp(44), a.dp(40)));
        r.addView(arrow("↓", down, () -> mv.moved(1)), new LinearLayout.LayoutParams(a.dp(44), a.dp(40)));
        g.addView(r);
    }

    private View arrow(String s, boolean on, final Runnable r) {
        TextView t = a.text(s, 20, on ? a.primaryInk : a.line, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(a.ripple(a.round(on ? a.primarySoft : a.surface, on ? a.primarySoft : a.line, 12), 12));
        t.setEnabled(on);
        t.setContentDescription(s.equals("↑") ? "تقديم" : "تأخير");
        if (on) t.setOnClickListener(v -> r.run());
        return t;
    }
}
