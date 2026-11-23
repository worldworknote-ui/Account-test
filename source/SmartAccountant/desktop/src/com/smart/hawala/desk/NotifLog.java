package com.smart.hawala.desk;

import com.smart.hawala.*;
import java.awt.*;
import java.util.List;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

/**
 * 10.18: سجل الإشعارات على هذا الجهاز: كل إشعار ظهر (مزامنة، تعارض، دخول جهاز، رسالة مدير، تذكير، نسخ) يبقى هنا بتاريخه،
 * ومعه سجل تنبيهات المزامنة كلها (المفتوح وما عُلّم «تمت»). لا يضيع الإشعار بالضغط عليه.
 */
final class NotifLog {
    private NotifLog() {}

    static void open(Component parent, Db db) {
        JTabbedPane tabs = new JTabbedPane();
        tabs.setFont(Ui.font(13.5f, true));
        tabs.setComponentOrientation(Ui.RTL);
        tabs.addTab("كل الإشعارات", table(new String[]{"الوقت", "العنوان", "النص"}, db.notifLog(500), new int[]{140, 220, 560}));
        java.util.List<String[]> rows = new java.util.ArrayList<>();
        for (String[] c : SyncEngine.noticeHistory(db, 500)) rows.add(new String[]{Model.date(Long.parseLong(c[1])), SyncStore.kindName(c[2]), c[5], "1".equals(c[7]) ? "تمت" : "مفتوح"});
        tabs.addTab("تنبيهات المزامنة", table(new String[]{"الوقت", "النوع", "التفاصيل", "الحالة"}, rows, new int[]{140, 200, 500, 80}));
        tabs.setPreferredSize(new Dimension(980, 560));
        JOptionPane p = new JOptionPane(tabs, JOptionPane.PLAIN_MESSAGE, JOptionPane.DEFAULT_OPTION, null, new Object[]{"إغلاق", "مسح سجل الإشعارات"}, "إغلاق");
        Ui.show(parent, p, "سجل الإشعارات");
        if ("مسح سجل الإشعارات".equals(p.getValue()) && Ui.confirm(parent, "مسح سجل الإشعارات", "يُمسح سجل الإشعارات على هذا الجهاز. تنبيهات المزامنة تبقى.", "مسح")) {
            db.clearNotifLog();
            Ui.toast("مُسح سجل الإشعارات");
        }
    }

    private static JComponent table(String[] cols, List<String[]> rows, int[] widths) {
        DefaultTableModel m = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        for (String[] r : rows) m.addRow(r);
        JTable t = Ui.table(m);
        Ui.widths(t, widths);
        t.applyComponentOrientation(Ui.RTL);
        // النص الكامل عند الوقوف على السطر (الإشعار قد يكون طويلًا)
        t.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override public void mouseMoved(java.awt.event.MouseEvent e) {
                int r = t.rowAtPoint(e.getPoint());
                t.setToolTipText(r < 0 ? null : "<html><div style='width:420px'>" + Ui.esc(String.valueOf(t.getValueAt(r, 2))) + "</div></html>");
            }
        });
        JScrollPane sp = Ui.tableBox(t);
        if (rows.isEmpty()) {
            JPanel e = Ui.column();
            e.add(Ui.label("لا شيء بعد.", 14, Ui.muted, false));
            return e;
        }
        return sp;
    }
}
