package com.smart.hawala;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;

/**
 * 12.2: نافذة «التجميع» الصغيرة على ويندوز وماك، فوق كل النوافذ: كل رسالة تنسخها من واتساب تظهر فورًا برقمها
 * (1، 2، 3…) وجزء من نصها، ثم «نقل» ينقلها كلها إلى البرنامج ويرجعك إليه.
 */
public final class DeskCollect implements WaMove.CollectView {
    static final String TITLE = "تجميع الرسائل";
    private JFrame f;
    private JPanel list;
    private JLabel head;
    private JButton move;
    private Runnable onMove, onStop;
    private Point at;

    /** للتجارب. */
    static DeskCollect last;

    DeskCollect() { last = this; }

    @Override public void show(List<WaText.Msg> collected, Runnable move, Runnable stop) {
        final List<WaText.Msg> l = new ArrayList<>(collected);
        Runnable r = () -> {
            onMove = move;
            onStop = stop;
            if (f == null || !f.isDisplayable()) build();
            fill(l);
            if (!f.isVisible()) {
                f.setVisible(true);
                f.toFront();
            }
        };
        if (SwingUtilities.isEventDispatchThread()) r.run();
        else SwingUtilities.invokeLater(r);
    }

    @Override public void close() {
        Runnable r = () -> {
            if (f != null) {
                at = f.getLocation();
                f.dispose();
            }
            f = null;
        };
        if (SwingUtilities.isEventDispatchThread()) r.run();
        else SwingUtilities.invokeLater(r);
    }

    /** للتجارب. */
    JFrame frame() { return f; }

    JButton moveButton() { return move; }

    int rows() { return list == null ? 0 : list.getComponentCount(); }

    private void build() {
        f = new JFrame(TITLE);
        f.setAlwaysOnTop(true);
        f.setAutoRequestFocus(false);
        f.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        f.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) { stop(); }
        });
        JPanel root = new JPanel(new BorderLayout(0, 6));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        root.setComponentOrientation(ComponentOrientation.RIGHT_TO_LEFT);
        head = new JLabel("", SwingConstants.RIGHT);
        head.setFont(head.getFont().deriveFont(Font.BOLD, 13f));
        root.add(head, BorderLayout.NORTH);
        list = new JPanel();
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setComponentOrientation(ComponentOrientation.RIGHT_TO_LEFT);
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.add(list, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(wrap);
        sp.setBorder(BorderFactory.createLineBorder(new Color(0xDD, 0xDD, 0xDD)));
        sp.getVerticalScrollBar().setUnitIncrement(16);
        root.add(sp, BorderLayout.CENTER);
        JPanel btns = new JPanel(new GridLayout(1, 2, 8, 0));
        btns.setComponentOrientation(ComponentOrientation.RIGHT_TO_LEFT);
        move = new JButton("نقل");
        move.setFont(move.getFont().deriveFont(Font.BOLD, 14f));
        move.setBackground(DeskCapture.ACCENT);
        move.setForeground(Color.WHITE);
        move.addActionListener(e -> doMove());
        JButton stop = new JButton("إيقاف التجميع");
        stop.addActionListener(e -> stop());
        btns.add(move);
        btns.add(stop);
        root.add(btns, BorderLayout.SOUTH);
        f.setContentPane(root);
        f.setSize(320, 380);
        if (at != null) f.setLocation(at);
        else {
            Rectangle b = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            f.setLocation(b.x + 30, b.y + Math.max(30, (b.height - 380) / 2));
        }
    }

    private void fill(List<WaText.Msg> l) {
        list.removeAll();
        head.setText(l.isEmpty() ? "<html><div dir='rtl'>انسخ الرسائل من واتساب واحدة تلو الأخرى (Ctrl+C أو ⌘C)، فتظهر هنا مرقّمة.</div></html>"
                : "جُمعت " + l.size() + (l.size() == 1 ? " رسالة" : " رسائل") + " • انسخ غيرها أو اضغط «نقل»");
        int i = 1;
        for (WaText.Msg m : l) {
            JPanel row = new JPanel(new BorderLayout(8, 0));
            row.setComponentOrientation(ComponentOrientation.RIGHT_TO_LEFT);
            row.setBorder(BorderFactory.createEmptyBorder(5, 4, 5, 4));
            JLabel n = new JLabel(String.valueOf(i++), SwingConstants.CENTER);
            n.setOpaque(true);
            n.setBackground(DeskCapture.ACCENT);
            n.setForeground(Color.WHITE);
            n.setFont(n.getFont().deriveFont(Font.BOLD, 13f));
            n.setPreferredSize(new Dimension(26, 26));
            row.add(n, BorderLayout.LINE_START);
            String t = m.body.replace('\n', ' ').trim();
            JLabel s = new JLabel(t.length() > 48 ? t.substring(0, 48) + "…" : t, SwingConstants.RIGHT);
            s.setToolTipText("<html><div dir='rtl' style='width:260px'>" + esc(m.body).replace("\n", "<br>") + "</div></html>");
            row.add(s, BorderLayout.CENTER);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
            list.add(row);
        }
        move.setText(l.isEmpty() ? "نقل" : "نقل (" + l.size() + ")");
        move.setEnabled(!l.isEmpty());
        list.revalidate();
        list.repaint();
    }

    private static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

    private void doMove() {
        Runnable m = onMove;
        close();
        if (m != null) m.run();
        backToApp();
    }

    private void stop() {
        Runnable s = onStop;
        close();
        if (s != null) s.run();
    }

    /** يرجع إلى نافذة البرنامج الرئيسية بعد «نقل». */
    static void backToApp() {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_REQUEST_FOREGROUND))
                Desktop.getDesktop().requestForeground(true);
        } catch (Throwable ignored) {
            // ليس كل نظام يدعمه
        }
        Frame main = com.smart.hawala.desk.PhoneWindow.frame();
        if (main != null && main.isVisible()) {
            if ((main.getExtendedState() & Frame.ICONIFIED) != 0) main.setExtendedState(main.getExtendedState() & ~Frame.ICONIFIED);
            main.toFront();
            main.requestFocus();
            return;
        }
        for (Frame fr : Frame.getFrames()) {
            if (!fr.isVisible() || DeskCapture.TITLE.equals(fr.getTitle()) || TITLE.equals(fr.getTitle())) continue;
            if ((fr.getExtendedState() & Frame.ICONIFIED) != 0) fr.setExtendedState(fr.getExtendedState() & ~Frame.ICONIFIED);
            fr.toFront();
            fr.requestFocus();
            break;
        }
    }
}
