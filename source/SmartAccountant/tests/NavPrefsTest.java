import com.smart.hawala.NavPrefs;

/** 10.15: إعدادات الشريط الزجاجي وقائمة «+» (من «أموالي»): القيم الافتراضية، الحفظ والقراءة، الحدود، والقيم التالفة. */
public class NavPrefsTest {
    static int fails = 0, passes = 0;

    static void check(boolean c, String m) {
        if (c) passes++;
        else fails++;
        System.out.println((c ? "  PASS " : "  FAIL ") + m);
    }

    static NavPrefs lightBar() {
        NavPrefs p = new NavPrefs();
        p.barColor = NavPrefs.BAR_COLORS[4];
        return p;
    }

    public static void main(String[] a) {
        System.out.println("== الشريط الزجاجي وقائمة «+»");
        NavPrefs d = NavPrefs.from("");
        check(d.fan.equals(NavPrefs.DEFAULT_FAN) && d.plusMenu && d.labels && d.barColor == NavPrefs.BAR_COLORS[1] && d.circleColor == NavPrefs.AUTO,
                "الافتراضي: حركة عادية، قص، بين مكتبين، واتساب؛ الشريط بني زجاجي كأموالي والدائرة بلون التبويب");
        int header = NavPrefs.rgb(11, 107, 83), tab = NavPrefs.rgb(37, 99, 235);
        NavPrefs au = new NavPrefs();
        au.barColor = NavPrefs.AUTO;
        check(au.bar(header) == header && d.circle(tab) == tab, "«تلقائي»: الشريط بلون الشريط العلوي، والدائرة بلون التبويب");
        check(d.icons(header) == 0xFFFFFFFF && au.icons(header) == 0xFFFFFFFF && lightBar().icons(header) == NavPrefs.rgb(22, 50, 92), "الأيقونات بيضاء على الداكن وكحلية على الفاتح");
        check("notch".equals(NavPrefs.style(null)) && "notch".equals(NavPrefs.style("xyz")) && "island".equals(NavPrefs.style("island"))
                && "bubble".equals(NavPrefs.style("bubble")), "النمط: الزجاجي افتراضي، والجزيرة والدائرة باقيتان");

        NavPrefs p = new NavPrefs();
        p.barColor = NavPrefs.BAR_COLORS[2];
        p.circleColor = NavPrefs.CIRCLE_COLORS[3];
        p.iconColor = NavPrefs.ICON_COLORS[2];
        p.barAlpha = 0;
        p.barSize = 2;
        p.barFloat = true;
        p.labels = false;
        p.plusMenu = false;
        p.fanStyle = 1;
        p.fanSize = 2;
        p.itemColors.put("WA", NavPrefs.rgb(1, 2, 3));
        check(p.toggleFan("CASH_IN") == null && p.fan.size() == 5, "إضافة زر إلى القائمة");
        NavPrefs.move(p.fan, 0, 1);
        NavPrefs q = NavPrefs.from(p.toJson());
        check(q.fan.equals(p.fan) && q.fan.get(0).equals("FX"), "الترتيب يُحفظ ويُقرأ");
        check(q.barColor == p.barColor && q.circleColor == p.circleColor && q.iconColor == p.iconColor && q.barAlpha == 0 && q.barSize == 2 && q.barFloat
                && !q.labels && !q.plusMenu && q.fanStyle == 1 && q.fanSize == 2, "كل الإعدادات تُحفظ وتُقرأ");
        check(q.itemColor("WA") == NavPrefs.rgb(1, 2, 3) && q.itemColor("TX") == (Integer) NavPrefs.ITEMS.get("TX")[1], "لون كل زر (المخصص والافتراضي)");

        check(p.toggleFan("COUNT") == null && p.fan.size() == 6 && p.toggleFan("DAILY") != null && p.fan.size() == 6, "الحد الأقصى 6 أزرار");
        NavPrefs few = new NavPrefs();
        few.toggleFan("TX");
        few.toggleFan("FX");
        check(few.fan.size() == 2 && few.toggleFan("XFER") != null && few.fan.size() == 2, "الحد الأدنى زران");
        check(few.toggleFan("NOPE") != null, "زر غير معروف يُرفض");

        NavPrefs bad = NavPrefs.from("{\"fan\":[\"TX\",\"TX\",\"NOPE\",\"FX\",\"WA\"],\"barAlpha\":99,\"barSize\":-3,\"fanSize\":7,\"itemColors\":{\"NOPE\":5}}");
        check(bad.fan.size() == 3 && bad.fan.get(0).equals("TX") && bad.fan.get(1).equals("FX") && bad.barAlpha == 2 && bad.barSize == 0 && bad.fanSize == 2
                && bad.itemColors.isEmpty(), "القيم التالفة تُصلح: كل زر مرة، والمجهول يُحذف، والحدود تُضبط");
        check(NavPrefs.from("{\"fan\":[\"TX\"]}").fan.equals(NavPrefs.DEFAULT_FAN), "قائمة بزر واحد تعود للافتراضي");
        check(NavPrefs.from("{not json").fan.equals(NavPrefs.DEFAULT_FAN), "نص تالف = الافتراضي");
        System.out.println(fails == 0 ? passes + " نجح، 0 فشل" : "FAILED " + fails);
        if (fails > 0) System.exit(1);
    }
}
