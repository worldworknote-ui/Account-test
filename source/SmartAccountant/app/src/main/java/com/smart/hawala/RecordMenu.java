package com.smart.hawala;

import java.util.ArrayList;
import java.util.List;

/**
 * 10.6: أنواع الحركة في صفحة «تسجيل حركة» (مشتركة بين الهاتف والماك والويندوز): عادية، قص عملات، بين مكتبين.
 * 10.7: تُختار بأزرار أعلى الصفحة فوق المكتب (أُلغيت القائمة نصف الدائرية).
 * ما لا يملك المستخدم صلاحيته لا يظهر له؛ والقواعد نفسها تُفرض مرة أخرى في طبقة البيانات عند الحفظ.
 */
public final class RecordMenu {
    private RecordMenu() {}

    public static final String TX = "tx", FX = "fx", XFER = "xfer";

    public static final class Item {
        public final String key, title, label, hint;

        Item(String key, String title, String label, String hint) {
            this.key = key;
            this.title = title;
            this.label = label;
            this.hint = hint;
        }
    }

    /** بالترتيب من اليمين: [المفتاح، عنوان الشاشة، الاسم في القائمة، سطر توضيحي]. */
    public static final Item[] ITEMS = {
            new Item(TX, "تسجيل حركة جديدة", "حركة عادية", "لنا أو علينا مع مكتب"),
            new Item(FX, "تسجيل حركة قص عملات", "قص عملات", "نبيع عملة ونشتري أخرى"),
            new Item(XFER, "تسجيل حركة بين مكتبين", "بين مكتبين", "طالب الحركة ومنفّذها")};

    public static Item item(String key) {
        for (Item i : ITEMS) if (i.key.equals(key)) return i;
        return null;
    }

    /** الصلاحيات اللازمة لكل خيار (القص يسجّل ساقين مرتبطتين بسعر، فيحتاج صلاحية التعديل أيضًا كما في Db.addFx). */
    public static Auth.Perm[] perms(String key) {
        // 12.0: صلاحيتان مستقلتان يختارهما المدير
        if (FX.equals(key)) return new Auth.Perm[]{Auth.Perm.TX_ADD, Auth.Perm.FX};
        if (XFER.equals(key)) return new Auth.Perm[]{Auth.Perm.TX_ADD, Auth.Perm.XFER};
        return new Auth.Perm[]{Auth.Perm.TX_ADD};
    }

    public interface Can { boolean can(Auth.Perm p); }

    /** الخيارات المتاحة لهذا المستخدم. */
    public static List<Item> allowed(Can c) {
        List<Item> out = new ArrayList<>();
        for (Item i : ITEMS) {
            boolean ok = true;
            for (Auth.Perm p : perms(i.key)) ok &= c.can(p);
            if (ok) out.add(i);
        }
        return out;
    }

}
