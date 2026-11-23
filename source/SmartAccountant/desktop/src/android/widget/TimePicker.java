package android.widget;

import android.content.Context;
import android.view.Gravity;
import android.view.View;

/** اختيار الوقت: الساعة والدقيقة بأزرار زيادة/نقصان (أو الكتابة). */
public class TimePicker extends LinearLayout {
    public interface OnTimeChangedListener { void onTimeChanged(TimePicker v, int h, int m); }
    private int hour, minute;
    private boolean is24 = true;
    private final EditText he, me;

    public TimePicker(Context c) {
        super(c);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER);
        setLayoutDirection(LAYOUT_DIRECTION_LTR);
        float d = c.getResources().getDisplayMetrics().density;
        setPadding(Math.round(24 * d), Math.round(16 * d), Math.round(24 * d), Math.round(8 * d));
        he = field(c);
        me = field(c);
        addView(column(c, he, 23), new LayoutParams(Math.round(90 * d), -2));
        TextView colon = new TextView(c);
        colon.setText(":");
        colon.setTextSize(34);
        colon.setGravity(Gravity.CENTER);
        addView(colon, new LayoutParams(-2, -2));
        addView(column(c, me, 59), new LayoutParams(Math.round(90 * d), -2));
        java.util.Calendar k = java.util.Calendar.getInstance();
        setHour(k.get(java.util.Calendar.HOUR_OF_DAY));
        setMinute(k.get(java.util.Calendar.MINUTE));
    }
    private EditText field(Context c) {
        EditText e = new EditText(c);
        e.setTextSize(34);
        e.setGravity(Gravity.CENTER);
        e.setSingleLine(true);
        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        e.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(2)});
        return e;
    }
    private View column(Context c, final EditText e, final int max) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView up = new TextView(c), down = new TextView(c);
        up.setText("▲"); down.setText("▼");
        for (TextView t : new TextView[]{up, down}) { t.setTextSize(16); t.setGravity(Gravity.CENTER); t.setClickable(true); t.setPadding(0, 8, 0, 8); }
        up.setOnClickListener(v -> step(e, max, 1));
        down.setOnClickListener(v -> step(e, max, -1));
        col.addView(up, new LayoutParams(-1, -2));
        col.addView(e, new LayoutParams(-1, -2));
        col.addView(down, new LayoutParams(-1, -2));
        return col;
    }
    private void step(EditText e, int max, int d) {
        int v = val(e) + d;
        if (v < 0) v = max;
        if (v > max) v = 0;
        e.setText(String.format(java.util.Locale.US, "%02d", v));
    }
    private static int val(EditText e) { try { return Integer.parseInt(e.getText().toString().trim()); } catch (Exception x) { return 0; } }
    public void setHour(int h) { hour = h; he.setText(String.format(java.util.Locale.US, "%02d", h)); }
    public void setMinute(int m) { minute = m; me.setText(String.format(java.util.Locale.US, "%02d", m)); }
    public int getHour() { return Math.max(0, Math.min(23, val(he))); }
    public int getMinute() { return Math.max(0, Math.min(59, val(me))); }
    public void setIs24HourView(Boolean b) { is24 = b; }
    public void setOnTimeChangedListener(OnTimeChangedListener l) {}
}
