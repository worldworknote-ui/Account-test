package android.view;

import java.awt.*;
import java.awt.event.*;
import java.awt.event.KeyEvent;
import java.awt.event.InputEvent;
import java.util.WeakHashMap;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;

/** الخانة الحقيقية (Swing) فوق خانة الكتابة المركّزة: تُوضع فوقها تمامًا بخطها ولونها، والباقي يرسمه أندرويد. */
public final class TextOverlay {
    private TextOverlay() {}

    private static final class Live {
        View view;
        TextHost host;
        JPanel clip;
        JTextComponent field;
        boolean quiet;
        boolean pwd, single;
        boolean shown;
        boolean notifying;
    }

    private static final WeakHashMap<ViewRootImpl, Live> map = new WeakHashMap<>();

    public static View active(ViewRootImpl r) { Live l = map.get(r); return l == null ? null : l.view; }

    public static void hide(ViewRootImpl r) {
        Live l = map.remove(r);
        if (l == null) return;
        if (l.clip.getParent() != null) {
            Container p = l.clip.getParent();
            p.remove(l.clip);
            p.repaint();
        }
        l.host.liveChanged(false);
        l.view.invalidate();
    }

    /** يُظهر الخانة الحقيقية لهذا العنصر؛ (x,y) موضع النقرة بإحداثيات العنصر لوضع المؤشر (-1: لا تغيير). */
    public static void show(ViewRootImpl r, View v, float x, float y) {
        if (!(v instanceof TextHost)) return;
        TextHost h = (TextHost) v;
        Live l = map.get(r);
        if (l != null && (l.view != v || l.pwd != h.password() || l.single != h.singleLine())) { hide(r); l = null; }
        if (l == null) {
            l = create(r, v, h);
            map.put(r, l);
        }
        refresh(l);
        sync(r);
        final Live fl = l;
        if (x >= 0 && l.shown) {
            Rectangle b = l.field.getBounds();
            Point fp = SwingUtilities.convertPoint(r.pane, contentOrigin(v, x, y), l.clip);
            int pos = l.field.viewToModel2D(new Point(fp.x - b.x, fp.y - b.y));
            if (pos >= 0) l.field.setCaretPosition(Math.min(pos, l.field.getDocument().getLength()));
        } else if (x < 0) {
            int s = Math.max(0, Math.min(h.selStart(), l.field.getDocument().getLength())), e = Math.max(0, Math.min(h.selEnd(), l.field.getDocument().getLength()));
            fl.quiet = true;
            l.field.setCaretPosition(s);
            if (e != s) l.field.moveCaretPosition(e);
            fl.quiet = false;
        }
        SwingUtilities.invokeLater(() -> { if (fl.field.isShowing()) fl.field.requestFocusInWindow(); });
    }

    private static Rectangle sx(Rectangle r, float s) {
        int x = Math.round(r.x * s), y = Math.round(r.y * s);
        return new Rectangle(x, y, Math.round((r.x + r.width) * s) - x, Math.round((r.y + r.height) * s) - y);
    }

    private static Point contentOrigin(View v, float x, float y) {
        float[] p = {x, y};
        v.mapToRoot(p);
        float sc = ViewRootImpl.scale();
        return new Point(Math.round(p[0] * sc), Math.round(p[1] * sc));
    }

    private static Live create(ViewRootImpl r, View v, TextHost h) {
        Live l = new Live();
        l.view = v;
        l.host = h;
        l.pwd = h.password();
        l.single = h.singleLine();
        JTextComponent f;
        if (l.pwd) {
            JPasswordField p = new JPasswordField();
            p.setUI(new javax.swing.plaf.basic.BasicPasswordFieldUI());
            f = p;
        } else if (l.single) {
            JTextField t = new JTextField();
            t.setUI(new javax.swing.plaf.basic.BasicTextFieldUI());
            f = t;
        } else {
            JTextArea a = new JTextArea();
            a.setUI(new javax.swing.plaf.basic.BasicTextAreaUI());
            a.setLineWrap(true);
            a.setWrapStyleWord(true);
            f = a;
        }
        f.setOpaque(false);
        f.setBorder(BorderFactory.createEmptyBorder());
        f.setMargin(new Insets(0, 0, 0, 0));
        f.setBackground(new Color(0, 0, 0, 0));
        f.setFocusTraversalKeysEnabled(false);
        final Live fl = l;
        if (f.getDocument() instanceof AbstractDocument) ((AbstractDocument) f.getDocument()).setDocumentFilter(new Filter(h));
        f.getDocument().addDocumentListener(new DocumentListener() {
            void ch() {
                if (fl.quiet) return;
                String s = fl.field.getText();
                fl.notifying = true;
                try {
                    fl.host.userText(s, fl.field.getSelectionStart(), fl.field.getSelectionEnd());
                } finally {
                    fl.notifying = false;
                }
            }
            @Override public void insertUpdate(DocumentEvent e) { ch(); }
            @Override public void removeUpdate(DocumentEvent e) { ch(); }
            @Override public void changedUpdate(DocumentEvent e) {}
        });
        f.addCaretListener(e -> { if (!fl.quiet) fl.host.userSelection(Math.min(e.getDot(), e.getMark()), Math.max(e.getDot(), e.getMark())); });
        f.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER && (fl.single || e.isControlDown() || e.isMetaDown())) {
                    if (fl.host.enterPressed()) { e.consume(); return; }
                    if (fl.single) { r.moveFocus(true); e.consume(); return; }
                }
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE || e.getKeyCode() == KeyEvent.VK_TAB
                        || ((e.isMetaDown() || e.isControlDown()) && e.getKeyCode() != KeyEvent.VK_C && e.getKeyCode() != KeyEvent.VK_V
                        && e.getKeyCode() != KeyEvent.VK_X && e.getKeyCode() != KeyEvent.VK_A && e.getKeyCode() != KeyEvent.VK_Z
                        && e.getKeyCode() != KeyEvent.VK_Y && e.getKeyCode() != KeyEvent.VK_LEFT && e.getKeyCode() != KeyEvent.VK_RIGHT
                        && e.getKeyCode() != KeyEvent.VK_BACK_SPACE && e.getKeyCode() != KeyEvent.VK_DELETE)) {
                    r.key(e);
                }
            }
        });
        f.setComponentPopupMenu(menu(f));
        installUndo(f);
        JPanel clip = new JPanel(null);
        clip.setOpaque(false);
        clip.add(f);
        l.clip = clip;
        l.field = f;
        r.pane.add(clip);
        h.liveChanged(true);
        return l;
    }

    private static JPopupMenu menu(JTextComponent f) {
        JPopupMenu m = new JPopupMenu();
        m.applyComponentOrientation(ComponentOrientation.RIGHT_TO_LEFT);
        JMenuItem cut = new JMenuItem("قص"), copy = new JMenuItem("نسخ"), paste = new JMenuItem("لصق"), all = new JMenuItem("تحديد الكل");
        cut.addActionListener(e -> f.cut());
        copy.addActionListener(e -> f.copy());
        paste.addActionListener(e -> f.paste());
        all.addActionListener(e -> f.selectAll());
        m.add(cut); m.add(copy); m.add(paste); m.addSeparator(); m.add(all);
        m.addPopupMenuListener(new PopupMenuListener() {
            @Override public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                boolean sel = f.getSelectionStart() != f.getSelectionEnd();
                cut.setEnabled(sel && f.isEditable() && !(f instanceof JPasswordField));
                copy.setEnabled(sel && !(f instanceof JPasswordField));
                paste.setEnabled(f.isEditable());
            }
            @Override public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {}
            @Override public void popupMenuCanceled(PopupMenuEvent e) {}
        });
        return m;
    }

    private static void installUndo(JTextComponent f) {
        javax.swing.undo.UndoManager um = new javax.swing.undo.UndoManager();
        f.getDocument().addUndoableEditListener(um);
        int menu;
        try { menu = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx(); } catch (HeadlessException e) { menu = InputEvent.CTRL_DOWN_MASK; }
        f.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, menu), "sa-undo");
        f.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, menu | InputEvent.SHIFT_DOWN_MASK), "sa-redo");
        f.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, menu), "sa-redo");
        f.getActionMap().put("sa-undo", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { if (um.canUndo()) um.undo(); } });
        f.getActionMap().put("sa-redo", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { if (um.canRedo()) um.redo(); } });
    }

    /** يطابق الخانة الحقيقية مع العنصر (النص، الخط، اللون، المحاذاة). */
    private static void refresh(Live l) {
        TextHost h = l.host;
        JTextComponent f = l.field;
        java.awt.Font hf = h.hostFont();
        float sc = ViewRootImpl.scale();
        f.setFont(sc == 1f ? hf : hf.deriveFont(hf.getSize2D() * sc));
        Color c = new Color(h.hostColor(), true);
        f.setForeground(c);
        f.setCaretColor(c);
        f.setDisabledTextColor(c);
        int hl = h.hostHighlight();
        f.setSelectionColor(new Color((hl & 0xFFFFFF) | 0x55000000, true));
        f.setSelectedTextColor(c);
        f.setEditable(h.editable());
        ComponentOrientation o = h.rtlText() ? ComponentOrientation.RIGHT_TO_LEFT : ComponentOrientation.LEFT_TO_RIGHT;
        f.setComponentOrientation(o);
        if (f instanceof JTextField) ((JTextField) f).setHorizontalAlignment(h.align());
        String want = h.hostText();
        if (!want.equals(f.getText())) {
            l.quiet = true;
            f.setText(want);
            l.quiet = false;
        }
    }

    /** بعد أي تخطيط أو تمرير: الموضع والقص والإظهار. */
    public static void sync(ViewRootImpl r) {
        Live l = map.get(r);
        if (l == null) return;
        View v = l.view;
        if (v.attachInfo == null || v.root() != r) { hide(r); return; }
        boolean ok = v.isShown();
        float alpha = 1f;
        boolean transformed = false;
        Rectangle vis = new Rectangle(0, 0, r.width(), r.height());
        for (View a = v; a != null; a = a.parent instanceof View ? (View) a.parent : null) {
            alpha *= a.getAlpha();
            if (!a.hasIdentityTransform()) transformed = true;
            if (a != v) {
                float[] o = {0, 0};
                a.mapToRoot(o);
                vis = vis.intersection(new Rectangle(Math.round(o[0]), Math.round(o[1]), a.getWidth(), a.getHeight()));
            }
        }
        if (alpha < 0.97f || transformed || vis.isEmpty()) ok = false;
        refreshText(l);
        if (!ok) {
            if (l.shown) { l.clip.setVisible(false); l.shown = false; l.host.liveChanged(false); v.invalidate(); }
            return;
        }
        // 11.2: دون مساحة الرسوم الجانبية (مثل «العين» في خانات السر) فتصل النقرة عليها إلى العنصر
        int pl = v instanceof android.widget.TextView ? ((android.widget.TextView) v).getCompoundPaddingLeft() : v.getPaddingLeft();
        int pr = v instanceof android.widget.TextView ? ((android.widget.TextView) v).getCompoundPaddingRight() : v.getPaddingRight();
        float[] o = {pl, v.getPaddingTop()};
        v.mapToRoot(o);
        int cw = Math.max(1, v.getWidth() - pl - pr), ch = Math.max(1, v.getHeight() - v.getPaddingTop() - v.getPaddingBottom());
        Rectangle content = new Rectangle(Math.round(o[0]), Math.round(o[1]), cw, ch);
        Rectangle clip = content.intersection(vis);
        // 11.0: الخانة الحقيقية بإحداثيات الشاشة الفعلية (المقياس العام)
        float sc = ViewRootImpl.scale();
        if (sc != 1f) {
            content = sx(content, sc);
            clip = sx(clip, sc);
        }
        if (clip.isEmpty()) { if (l.shown) { l.clip.setVisible(false); l.shown = false; l.host.liveChanged(false); v.invalidate(); } return; }
        if (!l.clip.getBounds().equals(clip)) l.clip.setBounds(clip);
        int fh = ch;
        fh = content.height;
        if (!l.single) fh = Math.max(fh, l.field.getPreferredSize().height);
        Rectangle fb = new Rectangle(content.x - clip.x, content.y - clip.y, content.width, fh);
        if (!l.field.getBounds().equals(fb)) l.field.setBounds(fb);
        if (!l.shown) {
            l.clip.setVisible(true);
            l.shown = true;
            l.host.liveChanged(true);
            v.invalidate();
            final Live fl = l;
            SwingUtilities.invokeLater(() -> { if (fl.field.isShowing() && !fl.field.hasFocus()) fl.field.requestFocusInWindow(); });
        }
    }

    /** نص تغيّر من الكود (setText) أثناء ظهور الخانة. */
    private static void refreshText(Live l) {
        String want = l.host.hostText();
        if (!want.equals(l.field.getText())) {
            l.quiet = true;
            int caret = Math.min(Math.max(0, l.host.selEnd()), want.length());
            l.field.setText(want);
            l.field.setCaretPosition(caret);
            l.quiet = false;
        }
    }

    /** النص تغيّر برمجيًا: تحديث فوري للخانة الظاهرة. */
    public static void textChanged(View v) {
        if (v.attachInfo == null) return;
        Live l = map.get(v.attachInfo.root);
        if (l == null || l.view != v) return;
        // تعديل من مراقب النص أثناء كتابة المستخدم: يُطبَّق بعد انتهاء إشعار الخانة
        if (l.notifying) {
            final Live fl = l;
            SwingUtilities.invokeLater(() -> { if (map.get(v.attachInfo == null ? null : v.attachInfo.root) == fl) textChanged(v); });
            return;
        }
        // 11.2: إظهار/إخفاء كلمة السر (العين): خانة حقيقية من النوع الجديد في المكان نفسه
        if (l.pwd != l.host.password()) {
            final ViewRootImpl root = v.attachInfo.root;
            SwingUtilities.invokeLater(() -> {
                if (v.attachInfo != null && v.isFocused()) {
                    show(root, v, -1, -1);
                    Live n = map.get(root);
                    if (n != null && n.field != null) n.field.requestFocusInWindow();
                }
            });
            return;
        }
        refresh(l);
        int s = Math.max(0, Math.min(l.host.selStart(), l.field.getDocument().getLength())), e = Math.max(0, Math.min(l.host.selEnd(), l.field.getDocument().getLength()));
        l.quiet = true;
        l.field.setCaretPosition(s);
        if (e != s) l.field.moveCaretPosition(e);
        l.quiet = false;
    }

    /** يقصر المدخلات على الأرقام وعلى الطول الأقصى كما في لوحة مفاتيح الهاتف. */
    private static final class Filter extends DocumentFilter {
        final TextHost h;
        Filter(TextHost h) { this.h = h; }
        String clean(String s, int curLen) {
            if (s == null) return null;
            int num = h.numeric();
            if (h.singleLine()) s = s.replace("\r", "").replace('\n', ' ');
            if (num == 1 || num == 2) {
                StringBuilder b = new StringBuilder();
                for (char c : s.toCharArray()) {
                    if (Character.isDigit(c) || c == '-' || c == '+' || (num == 2 && (c == '.' || c == ',' || c == '٫' || c == '٬'))) b.append(c);
                }
                s = b.toString();
            } else if (num == 3) {
                StringBuilder b = new StringBuilder();
                for (char c : s.toCharArray()) if (Character.isDigit(c) || "+-() #*".indexOf(c) >= 0) b.append(c);
                s = b.toString();
            }
            int max = h.maxLength();
            if (max > 0 && curLen + s.length() > max) s = s.substring(0, Math.max(0, max - curLen));
            return s;
        }
        @Override public void insertString(FilterBypass fb, int off, String s, AttributeSet a) throws BadLocationException {
            super.insertString(fb, off, clean(s, fb.getDocument().getLength()), a);
        }
        @Override public void replace(FilterBypass fb, int off, int len, String s, AttributeSet a) throws BadLocationException {
            super.replace(fb, off, len, clean(s, fb.getDocument().getLength() - len), a);
        }
    }
}
