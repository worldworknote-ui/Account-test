package com.smart.hawala.desk;

import com.smart.hawala.*;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * 10.16: «الانتقال السريع» بلوحة المفاتيح (Ctrl+K): اكتب جزءًا من اسم قسم أو مكتب ثم Enter.
 * لكل مكتب: فتح صفحته، كشف حسابه، أو تسجيل حركة له — دون لمس الفأرة. الأسهم للتنقل وEsc للإغلاق.
 */
final class QuickJump {
    private QuickJump() {}

    private static final class Item {
        final String label, hint, norm;
        final Runnable run;

        Item(String label, String hint, Runnable run) {
            this.label = label;
            this.hint = hint;
            this.run = run;
            this.norm = OwnerReports.norm(label);
        }

        @Override public String toString() { return label; }
    }

    /** اختصارات لوحة المفاتيح (للمساعدة F1). */
    static final String[][] KEYS = {
            {"Ctrl+K", "الانتقال السريع: أي قسم أو مكتب بالكتابة"}, {"Ctrl+N", "تسجيل حركة جديدة"}, {"Ctrl+Shift+N", "حركة قص عملات"},
            {"Ctrl+Shift+T", "حركة بين مكتبين"}, {"Ctrl+F", "البحث في سجل الحركات"}, {"Ctrl+1 … Ctrl+9", "أقسام القائمة بالترتيب"},
            {"Ctrl+Z / Esc / Alt+←", "رجوع إلى الشاشة السابقة"}, {"F5", "تحديث الشاشة الحالية"}, {"Ctrl+R", "مزامنة الآن"}, {"Ctrl+B", "نسخة احتياطية الآن"},
            {"Ctrl+L", "قفل البرنامج"}, {"Ctrl+E", "إخفاء المبالغ أو إظهارها"}, {"F1", "هذه القائمة"},
            {"Tab / Shift+Tab", "التنقل بين الحقول"}, {"Enter", "الحفظ في نماذج التسجيل والموافقة في النوافذ"}, {"Esc", "إغلاق النافذة"},
            {"PageUp / PageDown", "تمرير الشاشة"}};

    static void help(Component parent) {
        JPanel g = new JPanel(new GridLayout(0, 1, 0, 4));
        g.setOpaque(false);
        String mod = Ui.MAC ? "Cmd" : "Ctrl";
        for (String[] k : KEYS) {
            JLabel key = Ui.label(k[0].replace("Ctrl", mod), 13.5f, Ui.primaryInk, true);
            key.setPreferredSize(new Dimension(170, 22));
            JPanel r = new JPanel(new BorderLayout(14, 0));
            r.setOpaque(false);
            r.add(key, BorderLayout.LINE_START);
            r.add(Ui.label(k[1], 13.5f, Ui.ink, false), BorderLayout.CENTER);
            g.add(r);
        }
        JOptionPane.showMessageDialog(Ui.win(parent), Ui.rtl(g), "اختصارات لوحة المفاتيح", JOptionPane.PLAIN_MESSAGE);
    }

    static List<Item> items(App app) {
        List<Item> l = new ArrayList<>();
        for (String[] s : App.SECTIONS) {
            if ("-".equals(s[0]) || !app.navAllowed(s[0]) || !app.allowed(s[0])) continue;
            final String k = s[0];
            l.add(new Item(s[1], "قسم", () -> app.go(k)));
        }
        if (app.allowed("fx")) l.add(new Item("تسجيل حركة قص عملات", "إجراء", () -> app.go("fx")));
        if (app.allowed("xfer")) l.add(new Item("تسجيل حركة بين مكتبين", "إجراء", () -> app.go("xfer")));
        l.add(new Item("مزامنة الآن", "إجراء", () -> SyncView.now(app)));
        l.add(new Item("اختصارات لوحة المفاتيح", "مساعدة", () -> help(App.frame)));
        // 10.18: الإعدادات والمهام بالاسم، ليجدها المستخدم الجديد بالكتابة
        l.add(new Item("رجوع إلى الشاشة السابقة", "إجراء", app::back));
        l.add(new Item("نسخة احتياطية الآن", "إجراء", () -> app.go("backup")));
        l.add(new Item("إخفاء المبالغ أو إظهارها", "إجراء", () -> {
            app.toggleHidden();
            app.refreshCurrent();
        }));
        l.add(new Item("الوضع الليلي / النهاري والألوان والخط (مظهري)", "إعداد", app::myColors));
        l.add(new Item("قفل البرنامج الآن", "إجراء", app::lockNow));
        String[][] set = {{"تصميم رسالة مطابقة الرصيد", "settings"}, {"الإشعارات والتذكيرات", "settings"}, {"سجل الإشعارات", "notifs"}, {"توقيت المكتب الموحّد والساعة", "settings"},
                {"قفل البرنامج بكلمة سر", "settings"}, {"مصدر أسعار الصرف", "settings"}, {"العملات", "settings"}, {"الإقفال المحاسبي", "settings"},
                {"اعتماد الحركات الكبيرة", "settings"}, {"مفتاح الذكاء الاصطناعي واستخراج الرسائل", "settings"}, {"مساعد النقل من برنامج آخر", "backup"},
                {"استعادة نسخة احتياطية", "backup"}, {"النسخ التلقائي وكلمة مروره", "backup"}, {"المستخدمون والصلاحيات", "users"},
                {"رسالة إلى المحاسبين", "users"}, {"فرز المكاتب بين المحاسبين", "team"}, {"أجهزة المزامنة وتسميتها", "sync"}, {"تعارضات المزامنة وتنبيهاتها", "sync"},
                {"سجل العمليات (من فعل ماذا)", "reports"}, {"فحص صحة البيانات", "reports"}, {"تخصيص الصفحة الرئيسية", "home"}, {"الأمانات والمبالغ المجمّدة", "holds"},
                {"تقرير اليوم وإقفال اليومية", "daily"}, {"أرصدة كل المكاتب", "balances"}, {"الحركات بانتظار الاعتماد", "log"}};
        for (String[] x : set) {
            final String k = x[1];
            if (!"notifs".equals(k) && !"holds".equals(k) && !"home".equals(k) && (!app.allowed(k) || !app.navAllowed(k) && !"team".equals(k))) continue;
            l.add(new Item(x[0], "إعداد / مهمة", () -> {
                if ("notifs".equals(k)) NotifLog.open(App.frame, app.db);
                else if ("holds".equals(k)) app.go("balances");
                else app.go(k, "الحركات بانتظار الاعتماد".equals(x[0]) ? "pending" : null);
            }));
        }
        if (app.can(Auth.Perm.VIEW)) {
            for (Model.Office o : app.db.offices()) {
                final long id = o.id;
                l.add(new Item(o.name, o.active ? "مكتب — فتح صفحته" : "مكتب موقوف", () -> app.go("office", id)));
                l.add(new Item("كشف " + o.name, "كشف حساب", () -> app.go("statement", id)));
                if (o.active && app.can(Auth.Perm.TX_ADD)) l.add(new Item("حركة لـ " + o.name, "تسجيل حركة", () -> app.go("tx", new TxView.Prefill(id))));
            }
        }
        return l;
    }

    static void open(App app) { open(app, ""); }

    static void open(App app, String initial) {
        if (app.locked) return;
        final List<Item> all = items(app);
        final JDialog d = new JDialog(App.frame, "الانتقال السريع", Dialog.ModalityType.APPLICATION_MODAL);
        d.setUndecorated(true);
        final Ui.Field q = Ui.field("اكتب اسم قسم أو مكتب…  (↑ ↓ ثم Enter، وEsc للإغلاق)");
        final DefaultListModel<Item> m = new DefaultListModel<>();
        final JList<Item> list = new JList<>(m);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellHeight(34);
        list.setCellRenderer((jl, it, i, sel, foc) -> {
            JPanel r = new JPanel(new BorderLayout(10, 0));
            r.setBorder(new EmptyBorder(0, 12, 0, 12));
            r.setBackground(sel ? Ui.primarySoft : Ui.surface);
            r.add(Ui.label(it.label, 14.5f, Ui.ink, sel), BorderLayout.CENTER);
            r.add(Ui.label(it.hint, 12, Ui.muted, false), BorderLayout.LINE_END);
            r.applyComponentOrientation(Ui.RTL);
            return r;
        });
        final Runnable fill = () -> {
            String f = OwnerReports.norm(q.getText());
            m.clear();
            List<Item> starts = new ArrayList<>(), has = new ArrayList<>();
            for (Item it : all) {
                if (f.isEmpty() || it.norm.startsWith(f)) starts.add(it);
                else {
                    boolean allW = true;
                    for (String wq : f.split(" ")) allW &= wq.isEmpty() || it.norm.contains(wq);
                    if (allW) has.add(it);
                }
            }
            // بلا كتابة: الأقسام والإجراءات فقط (المكاتب تظهر بالكتابة)
            for (Item it : starts) if (!f.isEmpty() || !it.hint.contains("مكتب") && !it.hint.contains("كشف حساب") && !it.hint.contains("تسجيل حركة") && !it.hint.contains("إعداد")) m.addElement(it);
            for (Item it : has) m.addElement(it);
            if (!m.isEmpty()) list.setSelectedIndex(0);
        };
        final Runnable go = () -> {
            Item it = list.getSelectedValue();
            if (it == null) return;
            d.dispose();
            SwingUtilities.invokeLater(it.run);
        };
        q.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { fill.run(); }

            @Override public void removeUpdate(DocumentEvent e) { fill.run(); }

            @Override public void changedUpdate(DocumentEvent e) { fill.run(); }
        });
        q.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                int n = m.getSize(), i = list.getSelectedIndex();
                if (e.getKeyCode() == KeyEvent.VK_DOWN && n > 0) list.setSelectedIndex(Math.min(n - 1, i + 1));
                else if (e.getKeyCode() == KeyEvent.VK_UP && n > 0) list.setSelectedIndex(Math.max(0, i - 1));
                else if (e.getKeyCode() == KeyEvent.VK_PAGE_DOWN && n > 0) list.setSelectedIndex(Math.min(n - 1, i + 8));
                else if (e.getKeyCode() == KeyEvent.VK_PAGE_UP && n > 0) list.setSelectedIndex(Math.max(0, i - 8));
                else if (e.getKeyCode() == KeyEvent.VK_ENTER) go.run();
                else if (e.getKeyCode() == KeyEvent.VK_ESCAPE) d.dispose();
                else return;
                list.ensureIndexIsVisible(list.getSelectedIndex());
                e.consume();
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { go.run(); }
        });
        q.setText(initial == null ? "" : initial);
        fill.run();
        JPanel c = new JPanel(new BorderLayout(0, 8));
        c.setBackground(Ui.surface);
        c.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Ui.primary, 2), new EmptyBorder(12, 12, 12, 12)));
        c.add(q, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(list);
        sp.setBorder(BorderFactory.createLineBorder(Ui.line));
        sp.setPreferredSize(new Dimension(560, 340));
        c.add(sp, BorderLayout.CENTER);
        c.applyComponentOrientation(Ui.RTL);
        d.setContentPane(c);
        d.pack();
        d.setLocationRelativeTo(App.frame);
        d.setLocation(d.getX(), Math.max(App.frame.getY() + 90, d.getY() - 120));
        d.setVisible(true);
    }
}
