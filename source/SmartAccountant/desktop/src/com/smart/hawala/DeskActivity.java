package com.smart.hawala;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

/**
 * 11.0: شاشة الهاتف نفسها (MainActivity) على الماك والويندوز، مع اختصارات لوحة المفاتيح فقط:
 * ⌘/Ctrl+N حركة جديدة، ⌘/Ctrl+F بحث، ⌘/Ctrl+1…9 تبويبات الشريط السفلي، ⌘/Ctrl+[ رجوع، ⌘/Ctrl+H الرئيسية.
 */
public class DeskActivity extends MainActivity {
    // 12.1: «إطار الالتقاط» متاح على الكمبيوتر فقط
    static { DeskCapture.install(); }

    /** لون خلفية الشاشة (لما حول العمود في النافذة). */
    public int bgColor() { return bg; }
    public int headerColor() { return header; }
    public boolean isNight() { return night; }
    public Db database() { return db; }
    /** 11.0: بعد تغيير الوضع (فاتح/ليلي) من معالج الشكل. */
    public void restyleAll() { restyle(null); }

    @Override protected boolean shortcutKey(KeyEvent e) {
        if (e.getID() != KeyEvent.KEY_PRESSED) return false;
        int menu = (e.getModifiersEx() & (InputEvent.META_DOWN_MASK | InputEvent.CTRL_DOWN_MASK));
        if (menu == 0 || !unlocked || db == null || (db.usersEnabled() && SmartApp.currentUser == null)) return false;
        int k = e.getKeyCode();
        try {
            switch (k) {
                case KeyEvent.VK_N:
                    if (can(Auth.Perm.TX_ADD)) { beforeScreenChange(); txScreen.showNew(); }
                    return true;
                case KeyEvent.VK_F:
                    if (can(Auth.Perm.VIEW)) { beforeScreenChange(); logScreen.searchFor(""); }
                    return true;
                case KeyEvent.VK_V:
                    // 11.3: Ctrl/⌘+V خارج خانات الكتابة = «لصق رسائل واتساب» (رسالة ← نموذج، عدة رسائل ← قائمتها)
                    if (can(Auth.Perm.TX_ADD)) { beforeScreenChange(); homeScreen.pasteFromClipboard(); }
                    return true;
                case KeyEvent.VK_G:
                    // 12.1: Ctrl/⌘+G = «إطار الالتقاط» (يرقّم فقاعات واتساب تحت الإطار)
                    if (can(Auth.Perm.TX_WA)) waMove.openCapture(false);
                    return true;
                case KeyEvent.VK_H:
                    beforeScreenChange();
                    showHome();
                    return true;
                case KeyEvent.VK_OPEN_BRACKET:
                case KeyEvent.VK_LEFT:
                    onBackPressed();
                    return true;
                default:
                    if (k >= KeyEvent.VK_1 && k <= KeyEvent.VK_9) {
                        java.util.List<String> tabs = tabKeys();
                        int i = k - KeyEvent.VK_1;
                        if (i < tabs.size()) { beforeScreenChange(); openTab(tabs.get(i)); }
                        return true;
                    }
                    return false;
            }
        } catch (Throwable t) {
            error(t);
            return true;
        }
    }
}
