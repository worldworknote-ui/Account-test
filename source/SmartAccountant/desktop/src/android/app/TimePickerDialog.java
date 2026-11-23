package android.app;

import android.content.Context;
import android.widget.TimePicker;

public class TimePickerDialog extends AlertDialog {
    public interface OnTimeSetListener { void onTimeSet(TimePicker v, int h, int m); }
    private final TimePicker picker;
    public TimePickerDialog(Context c, OnTimeSetListener l, int h, int m, boolean is24) { this(c, 0, l, h, m, is24); }
    public TimePickerDialog(Context c, int theme, OnTimeSetListener l, int h, int m, boolean is24) {
        super(c, theme);
        picker = new TimePicker(c);
        picker.setHour(h);
        picker.setMinute(m);
        setView(picker);
        setButton(BUTTON_POSITIVE, "حسنًا", (dlg, w) -> { if (l != null) l.onTimeSet(picker, picker.getHour(), picker.getMinute()); });
        setButton(BUTTON_NEGATIVE, "إلغاء", null);
    }
    public void updateTime(int h, int m) { picker.setHour(h); picker.setMinute(m); }
}
