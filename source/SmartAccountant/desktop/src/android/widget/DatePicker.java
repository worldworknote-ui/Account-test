package android.widget;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import java.util.Calendar;

/** تقويم Material: رأس بالتاريخ المختار، والتنقل بين الأشهر، وشبكة الأيام. */
public class DatePicker extends FrameLayout {
    public interface OnDateChangedListener { void onDateChanged(DatePicker v, int y, int m, int d); }
    private int year, month, day, viewY, viewM;
    private long min = Long.MIN_VALUE, max = Long.MAX_VALUE;
    private OnDateChangedListener listener;
    private final LinearLayout box;
    private final float dp;
    private final int accent, fg, muted, bg;
    private int firstDay = Calendar.SATURDAY;
    private static final String[] MONTHS = {"كانون الثاني", "شباط", "آذار", "نيسان", "أيار", "حزيران", "تموز", "آب", "أيلول", "تشرين الأول", "تشرين الثاني", "كانون الأول"};
    private static final String[] DAYS = {"ح", "ن", "ث", "ر", "خ", "ج", "س"};

    public DatePicker(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        android.app.Activity a = c instanceof android.app.Activity ? (android.app.Activity) c : null;
        accent = a == null ? 0xFF0B6B53 : a.themeAccent();
        fg = a == null ? 0xDE000000 : a.themeTextColor();
        muted = a == null ? 0x8A000000 : a.themeSecondaryText();
        bg = a == null ? 0xFFFFFFFF : a.themeDialogBg();
        box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        addView(box, new FrameLayout.LayoutParams(-1, -2));
        Calendar k = Calendar.getInstance();
        init(k.get(Calendar.YEAR), k.get(Calendar.MONTH), k.get(Calendar.DAY_OF_MONTH), null);
    }

    public void init(int y, int m, int d, OnDateChangedListener l) { year = y; month = m; day = d; viewY = y; viewM = m; listener = l; rebuild(); }
    public void updateDate(int y, int m, int d) { year = y; month = m; day = d; viewY = y; viewM = m; rebuild(); }
    public int getYear() { return year; }
    public int getMonth() { return month; }
    public int getDayOfMonth() { return day; }
    public void setMinDate(long t) { min = t; rebuild(); }
    public void setMaxDate(long t) { max = t; rebuild(); }
    public long getMinDate() { return min; }
    public long getMaxDate() { return max; }
    public void setFirstDayOfWeek(int f) { firstDay = f; rebuild(); }
    public void setOnDateChangedListener(OnDateChangedListener l) { listener = l; }
    public void setCalendarViewShown(boolean s) {}
    public void setSpinnersShown(boolean s) {}

    private int px(float v) { return Math.round(v * dp); }
    private TextView tv(String s, float size, int color, boolean bold) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        return t;
    }
    private boolean allowed(int y, int m, int d) {
        Calendar k = Calendar.getInstance();
        k.clear();
        k.set(y, m, d, 12, 0);
        long t = k.getTimeInMillis();
        Calendar a = Calendar.getInstance(); a.setTimeInMillis(min == Long.MIN_VALUE ? Long.MIN_VALUE / 2 : min);
        Calendar b = Calendar.getInstance(); b.setTimeInMillis(max == Long.MAX_VALUE ? Long.MAX_VALUE / 2 : max);
        a.set(Calendar.HOUR_OF_DAY, 0); a.set(Calendar.MINUTE, 0);
        b.set(Calendar.HOUR_OF_DAY, 23); b.set(Calendar.MINUTE, 59);
        return (min == Long.MIN_VALUE || t >= a.getTimeInMillis()) && (max == Long.MAX_VALUE || t <= b.getTimeInMillis());
    }

    private void rebuild() {
        box.removeAllViews();
        // الرأس
        LinearLayout head = new LinearLayout(getContext());
        head.setOrientation(LinearLayout.VERTICAL);
        head.setBackgroundColor(accent);
        head.setPadding(px(24), px(16), px(24), px(16));
        TextView hy = tv(String.valueOf(year), 16, 0xB3FFFFFF, false);
        hy.setGravity(Gravity.START);
        head.addView(hy, new LinearLayout.LayoutParams(-1, -2));
        Calendar sel = Calendar.getInstance();
        sel.clear();
        sel.set(year, month, day);
        String[] wd = {"الأحد", "الاثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت"};
        TextView hd = tv(wd[sel.get(Calendar.DAY_OF_WEEK) - 1] + "، " + day + " " + MONTHS[month], 26, 0xFFFFFFFF, true);
        hd.setGravity(Gravity.START);
        head.addView(hd, new LinearLayout.LayoutParams(-1, -2));
        box.addView(head, new LinearLayout.LayoutParams(-1, -2));
        // التنقل
        LinearLayout nav = new LinearLayout(getContext());
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.setPadding(px(8), px(8), px(8), 0);
        TextView prev = tv("‹", 26, fg, true), next = tv("›", 26, fg, true);
        TextView mt = tv(MONTHS[viewM] + " " + viewY, 15, fg, true);
        for (TextView b : new TextView[]{prev, next}) {
            b.setClickable(true);
            GradientDrawable g = new GradientDrawable(); g.setShape(GradientDrawable.OVAL); g.setColor(0xFFFFFFFF);
            b.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22000000), null, null));
        }
        // في الواجهة العربية: السابق على اليمين
        prev.setOnClickListener(v -> { viewM--; if (viewM < 0) { viewM = 11; viewY--; } rebuild(); });
        next.setOnClickListener(v -> { viewM++; if (viewM > 11) { viewM = 0; viewY++; } rebuild(); });
        mt.setClickable(true);
        mt.setOnClickListener(v -> pickYear());
        nav.addView(prev, new LinearLayout.LayoutParams(px(44), px(44)));
        nav.addView(mt, new LinearLayout.LayoutParams(0, -2, 1));
        nav.addView(next, new LinearLayout.LayoutParams(px(44), px(44)));
        box.addView(nav, new LinearLayout.LayoutParams(-1, -2));
        // أيام الأسبوع
        LinearLayout wk = new LinearLayout(getContext());
        wk.setPadding(px(8), 0, px(8), 0);
        for (int i = 0; i < 7; i++) {
            int dow = (firstDay - 1 + i) % 7;
            wk.addView(tv(DAYS[dow], 12, muted, false), new LinearLayout.LayoutParams(0, px(32), 1));
        }
        box.addView(wk, new LinearLayout.LayoutParams(-1, -2));
        Calendar k = Calendar.getInstance();
        k.clear();
        k.set(viewY, viewM, 1);
        int offset = (k.get(Calendar.DAY_OF_WEEK) - firstDay + 7) % 7;
        int days = k.getActualMaximum(Calendar.DAY_OF_MONTH);
        Calendar today = Calendar.getInstance();
        int cell = 0;
        LinearLayout row = null;
        for (int i = 0; i < 42; i++) {
            if (i % 7 == 0) {
                if (i >= offset + days) break;
                row = new LinearLayout(getContext());
                row.setPadding(px(8), 0, px(8), 0);
                box.addView(row, new LinearLayout.LayoutParams(-1, px(40)));
            }
            int dnum = i - offset + 1;
            if (dnum < 1 || dnum > days) { row.addView(new View(getContext()), new LinearLayout.LayoutParams(0, -1, 1)); continue; }
            boolean isSel = viewY == year && viewM == month && dnum == day;
            boolean isToday = viewY == today.get(Calendar.YEAR) && viewM == today.get(Calendar.MONTH) && dnum == today.get(Calendar.DAY_OF_MONTH);
            boolean ok = allowed(viewY, viewM, dnum);
            TextView t = tv(String.valueOf(dnum), 14, isSel ? 0xFFFFFFFF : !ok ? (muted & 0x00FFFFFF) | 0x50000000 : isToday ? accent : fg, isSel || isToday);
            if (isSel) {
                GradientDrawable g = new GradientDrawable();
                g.setShape(GradientDrawable.OVAL);
                g.setColor(accent);
                t.setBackground(new android.graphics.drawable.InsetDrawable(g, px(2)));
            } else if (ok) {
                t.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22000000), null, null));
            }
            if (ok) {
                final int dd = dnum;
                t.setClickable(true);
                t.setOnClickListener(v -> {
                    year = viewY; month = viewM; day = dd;
                    if (listener != null) listener.onDateChanged(this, year, month, day);
                    rebuild();
                });
            }
            FrameLayout c = new FrameLayout(getContext());
            c.addView(t, new FrameLayout.LayoutParams(px(38), px(38), Gravity.CENTER));
            row.addView(c, new LinearLayout.LayoutParams(0, -1, 1));
            cell++;
        }
        box.addView(new View(getContext()), new LinearLayout.LayoutParams(-1, px(8)));
    }

    /** اختيار السنة (نقرة على اسم الشهر). */
    private void pickYear() {
        int now = Calendar.getInstance().get(Calendar.YEAR);
        final String[] ys = new String[30];
        for (int i = 0; i < 30; i++) ys[i] = String.valueOf(now + 2 - i);
        new android.app.AlertDialog.Builder(getContext()).setTitle("السنة").setItems(ys, (d, w) -> { viewY = Integer.parseInt(ys[w]); rebuild(); }).show();
    }
}
