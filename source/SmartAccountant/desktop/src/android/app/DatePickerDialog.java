package android.app;

import android.content.Context;
import android.widget.DatePicker;

public class DatePickerDialog extends AlertDialog {
    public interface OnDateSetListener { void onDateSet(DatePicker v, int y, int m, int d); }
    private final DatePicker picker;
    private OnDateSetListener listener;

    public DatePickerDialog(Context c, OnDateSetListener l, int y, int m, int d) { this(c, 0, l, y, m, d); }
    public DatePickerDialog(Context c, int theme, OnDateSetListener l, int y, int m, int d) {
        super(c, theme);
        listener = l;
        picker = new DatePicker(c);
        picker.init(y, m, d, null);
        setView(picker);
        setButton(BUTTON_POSITIVE, "حسنًا", (dlg, w) -> { if (listener != null) listener.onDateSet(picker, picker.getYear(), picker.getMonth(), picker.getDayOfMonth()); });
        setButton(BUTTON_NEGATIVE, "إلغاء", null);
    }
    public DatePickerDialog(Context c) { this(c, null, java.util.Calendar.getInstance().get(java.util.Calendar.YEAR), java.util.Calendar.getInstance().get(java.util.Calendar.MONTH), java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_MONTH)); }
    public DatePicker getDatePicker() { return picker; }
    public void setOnDateSetListener(OnDateSetListener l) { listener = l; }
    public void updateDate(int y, int m, int d) { picker.updateDate(y, m, d); }
}
