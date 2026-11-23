package com.smart.hawala;

import android.widget.LinearLayout;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 11.5: سجل الاستخدام: متى فتح كل مستخدم التطبيق وكم بقي عليه، على كل الأجهزة.
 * كل جلسة سطر «LOGIN_SESSION» في سجل العمليات (مدتها بالثواني في رقم السطر) فينتقل بالمزامنة ولا يُعدَّل،
 * والعرض لآخر سنة. الجلسة تبدأ عند فتح التطبيق أو الدخول، وتنتهي عند تركه (الخروج منه أو تصغير النافذة) أو قفله أو الخروج من الحساب.
 */
final class UsageScreen {
    static final String ACTION = "LOGIN_SESSION";
    static final long YEAR = 365L * 86_400_000L;
    private static final String[] PERIODS = {"اليوم", "7 أيام", "30 يومًا", "سنة"};
    private static final long[] SPANS = {0, 7L * 86_400_000L, 30L * 86_400_000L, YEAR};

    private final MainActivity a;
    private int period = 1;

    UsageScreen(MainActivity a) { this.a = a; }

    /** 11.6: للمدير وحده (أو بلا نظام مستخدمين). */
    static boolean allowed(MainActivity a) {
        return !a.db.usersEnabled() || (SmartApp.currentUser != null && Auth.isAdmin(SmartApp.currentUser));
    }

    static String describe(long start, long end) {
        return "من " + hm(start) + " إلى " + hm(end) + (Model.day(start).equals(Model.day(end)) ? "" : " (" + Model.day(end) + ")") + " — " + dur((end - start) / 1000);
    }

    static String hm(long t) { return new SimpleDateFormat("HH:mm", Locale.US).format(new Date(t)); }

    static String dur(long sec) {
        if (sec < 60) return "أقل من دقيقة";
        long m = sec / 60, h = m / 60;
        m %= 60;
        if (h == 0) return m + " دقيقة";
        return h + " س" + (m > 0 ? " و" + m + " د" : "");
    }

    private long since() {
        if (period == 0) {
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.setTimeInMillis(Clock.now());
            c.set(java.util.Calendar.HOUR_OF_DAY, 0);
            c.set(java.util.Calendar.MINUTE, 0);
            c.set(java.util.Calendar.SECOND, 0);
            c.set(java.util.Calendar.MILLISECOND, 0);
            return c.getTimeInMillis();
        }
        return Clock.now() - SPANS[period];
    }

    void show() {
        if (!allowed(a)) {
            a.toast("سجل الاستخدام للمدير وحده");
            return;
        }
        a.here(this::show);
        a.tabbedScreen("سجل الاستخدام", a.moreScreen::show, "more");
        a.seg(PERIODS, period, i -> {
            period = i;
            show();
        });
        // 12.7: مدة الخمول التي تُنهي الجلسة (تصل لكل الأجهزة بالمزامنة)
        long idleNow = a.idleMinutes();
        LinearLayout ig = a.group();
        a.groupRow(ig, R.drawable.ic_clock, a.primaryInk, "تتوقف الجلسة بعد الخمول", idleNow == 0 ? "بلا حد" : idleNow + " دقائق بلا لمس ولا كتابة", "تغيير", v -> {
            final long[] opts = {3, 5, 10, 15, 30, 0};
            String[] names = new String[opts.length];
            for (int i = 0; i < opts.length; i++) names[i] = opts[i] == 0 ? "بلا حد (حتى ترك التطبيق)" : opts[i] + " دقائق";
            new android.app.AlertDialog.Builder(a).setTitle("مدة الخمول").setItems(names, (d, w) -> {
                a.db.setSetting("usage_idle_min", "" + opts[w]);
                show();
            }).show();
        });
        final long since = since();
        a.busy("جارٍ جمع الجلسات…", p -> new Object[]{a.db.usageSessions(since), a.db.txCountsByUser(since)}, res -> {
            Object[] r = (Object[]) res;
            counts = castCounts(r[1]);
            render(cast(r[0]));
        });
    }

    @SuppressWarnings("unchecked")
    private static List<Object[]> cast(Object o) { return (List<Object[]>) o; }

    @SuppressWarnings("unchecked")
    private static Map<String, Long> castCounts(Object o) { return (Map<String, Long>) o; }

    /** 12.7: حركات كل مستخدم في الفترة (بالاسم المعروض في السجل). */
    private Map<String, Long> counts = new LinkedHashMap<>();

    private long txOf(String shown) {
        long n = 0;
        for (Map.Entry<String, Long> e : counts.entrySet()) if (userName(e.getKey()).equals(shown)) n += e.getValue();
        return n;
    }

    private void render(List<Object[]> rows) {
        // لكل مستخدم: المجموع، وعدد الجلسات، وآخر نشاط وجهازه
        Map<String, long[]> tot = new LinkedHashMap<>();
        Map<String, String> lastDev = new LinkedHashMap<>();
        for (Object[] r : rows) {
            String u = userName((String) r[2]);
            long[] t = tot.get(u);
            if (t == null) {
                t = new long[3];
                tot.put(u, t);
                lastDev.put(u, (String) r[3]);
            }
            t[0] += (Long) r[1];
            t[1]++;
            t[2] = Math.max(t[2], (Long) r[0]);
        }
        if (rows.isEmpty()) {
            a.hint("لا توجد جلسات في هذه الفترة. تُسجَّل الجلسات من الإصدار 11.5 فصاعدًا.");
        } else {
            LinearLayout g = a.group();
            for (Map.Entry<String, long[]> e : tot.entrySet()) {
                final String u = e.getKey();
                long[] t = e.getValue();
                a.groupRow(g, R.drawable.ic_clock, a.primaryInk, u,
                        "المجموع " + dur(t[0]) + " في " + t[1] + " جلسة • سجّل " + txOf(u) + " حركة\nآخر نشاط: " + Model.date(t[2]) + " • " + lastDev.get(u), "الجلسات", v -> user(u, rows));
            }
        }
        a.hint("الجلسة تبدأ عند فتح التطبيق أو الدخول، وتنتهي عند تركه أو قفله أو الخروج من الحساب، أو بعد " + a.idleMinutes()
                + " دقائق بلا لمس ولا كتابة (تُحسب حتى آخر نشاط)، وعلى الكمبيوتر حين تصير النافذة خلف برامج أخرى. "
                + "الجلسة الجارية تُحفظ كل دقيقة فلا تضيع إن أُغلق التطبيق غصبًا. "
                + "لا تُسجَّل الجلسات الأقصر من 10 ثوانٍ. يُعرض آخر سنة" + (SyncEngine.enabled(a.db) ? "، من كل أجهزة المزامنة." : "."));
    }

    static String userName(String u) { return u == null || u.isEmpty() || "المستخدم المحلي".equals(u) ? "المستخدم (بلا نظام مستخدمين)" : u; }

    /** جلسات مستخدم واحد مجمّعة باليوم. */
    private void user(final String u, final List<Object[]> rows) {
        a.here(() -> user(u, rows));
        a.tabbedScreen(u, this::show, "more");
        Map<String, List<Object[]>> byDay = new LinkedHashMap<>();
        for (Object[] r : rows) {
            if (!u.equals(userName((String) r[2]))) continue;
            String day = Model.day((Long) r[0] - (Long) r[1] * 1000);
            byDay.computeIfAbsent(day, k -> new ArrayList<>()).add(r);
        }
        int shownDays = 0;
        for (Map.Entry<String, List<Object[]>> e : byDay.entrySet()) {
            if (++shownDays > 120) {
                a.hint("تظهر آخر 120 يومًا؛ اختر فترة أقصر للتفاصيل.");
                break;
            }
            long sum = 0;
            for (Object[] r : e.getValue()) sum += (Long) r[1];
            a.section(e.getKey() + " — " + dur(sum), null, null);
            LinearLayout g = a.group();
            for (Object[] r : e.getValue()) {
                long end = (Long) r[0], start = end - (Long) r[1] * 1000;
                a.groupRow(g, R.drawable.ic_clock, a.muted, hm(start) + " ← " + hm(end) + " • " + dur((Long) r[1]), (String) r[3], null, null);
            }
        }
    }
}
