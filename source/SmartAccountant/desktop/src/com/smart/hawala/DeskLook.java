package com.smart.hawala;

import android.app.AlertDialog;
import android.widget.Button;
import javax.swing.SwingUtilities;

/**
 * 11.0: قسم «الكمبيوتر: الشكل والحجم» في شاشة «المظهر» (على الماك والويندوز فقط):
 * الشكل (مثل الهاتف أو القديم)، والحجم في النافذة (تلقائي يتبع سحب طرف النافذة، أو عمود ثابت)، والتكبير، ومعالج الشكل.
 * كلها إعدادات لهذا الجهاز وحده.
 */
public final class DeskLook {
    private DeskLook() {}

    public static final String[] COLS = {"auto", "480", "600", "full"};
    public static final String[] COL_LABELS = {"تلقائي", "عمود 480", "عمود 600", "كامل العرض"};
    public static final String[] ZOOMS = {"85", "100", "115", "130", "150"};
    public static final String[] ZOOM_LABELS = {"أصغر", "عادي", "أكبر", "كبير", "كبير جدًا"};

    public static void install() { Ui.deskDesignHook(DeskLook::section); }

    public static int indexOf(String[] a, String v) {
        for (int i = 0; i < a.length; i++) if (a[i].equals(v)) return i;
        return -1;
    }

    static void section(MainActivity a) {
        a.title("الكمبيوتر: الشكل والحجم");
        a.seg(new String[]{"مثل الهاتف", "الشكل القديم"}, 0, i -> {
            if (i != 1) return;
            new AlertDialog.Builder(a)
                    .setTitle("الانتقال إلى الشكل القديم؟")
                    .setMessage("تُفتح واجهة الكمبيوتر السابقة (قائمة جانبية وجداول) بالبيانات نفسها. تعود إلى هذا الشكل من قائمة «عرض ← الشكل الجديد (مثل الهاتف)».")
                    .setPositiveButton("انتقال", (d, w) -> SwingUtilities.invokeLater(() -> com.smart.hawala.desk.Main.switchUi("classic")))
                    .setNegativeButton("إلغاء", (d, w) -> a.restyle("design"))
                    .setOnCancelListener(d -> a.restyle("design"))
                    .show();
        });
        a.hint("الشكل القديم: واجهة الكمبيوتر السابقة. البيانات نفسها في الشكلين.");

        String col = a.db.setting("desk_column", "auto");
        int ci = indexOf(COLS, col);
        a.seg(COL_LABELS, ci < 0 ? 0 : ci, i -> {
            a.db.setSettingQuiet("desk_column", COLS[i]);
            com.smart.hawala.desk.PhoneWindow.refresh();
        });
        a.hint("«تلقائي»: الشاشة تملأ النافذة، والأزرار والخانات بحجمها الطبيعي لا تكبر مع النافذة الكبيرة (كبّرها من «الحجم» تحت إن أردت). «عمود»: حجم ثابت وسط النافذة. «كامل العرض»: الشاشات العريضة بعمودين.");

        int zi = indexOf(ZOOMS, a.db.setting("desk_zoom", "100"));
        a.seg(ZOOM_LABELS, zi < 0 ? 1 : zi, i -> {
            a.db.setSettingQuiet("desk_zoom", ZOOMS[i]);
            com.smart.hawala.desk.PhoneWindow.refresh();
        });
        a.hint("حجم النصوص والأزرار وكل شيء. بدقة أكبر (من 70٪ إلى 170٪) من معالج الشكل.");

        // 11.6: لا اختيار ثابت للمشاركة بعد الآن: القائمة تظهر في كل مرة
        Button w = a.smallButton("معالج الشكل والحجم (مع معاينة)", a.primary, v -> com.smart.hawala.desk.LookWizard.open(false));
        a.body.addView(w, a.lp(-1, 44, 6));
    }
}
