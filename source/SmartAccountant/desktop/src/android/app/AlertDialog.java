package android.app;

import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;
import java.util.ArrayList;
import java.util.List;

/** نافذة التنبيه بشكل Material كما في الهاتف: عنوان، رسالة، محتوى مخصص أو قائمة، وأزرار نصية في الأسفل. */
public class AlertDialog extends Dialog {
    public static final int BUTTON_POSITIVE = DialogInterface.BUTTON_POSITIVE, BUTTON_NEGATIVE = DialogInterface.BUTTON_NEGATIVE, BUTTON_NEUTRAL = DialogInterface.BUTTON_NEUTRAL;
    public static final int THEME_DEVICE_DEFAULT_LIGHT = 5, THEME_DEVICE_DEFAULT_DARK = 4, THEME_HOLO_LIGHT = 3;

    private CharSequence title, message;
    private View custom, customTitle;
    private Drawable icon;
    private final CharSequence[] btnText = new CharSequence[3];
    private final DialogInterface.OnClickListener[] btnListen = new DialogInterface.OnClickListener[3];
    private final Button[] buttons = new Button[3];
    private CharSequence[] items;
    private DialogInterface.OnClickListener itemListener;
    private int choiceMode, checked = -1;
    private boolean[] multi;
    private DialogInterface.OnMultiChoiceClickListener multiListener;
    private ListAdapter adapter;
    private TextView messageView;
    private ListView listView;
    private final float d;
    private final boolean night;

    protected AlertDialog(Context c) { this(c, 0); }
    protected AlertDialog(Context c, int theme) {
        super(c, theme);
        d = c.getResources().getDisplayMetrics().density;
        night = c instanceof Activity && ((Activity) c).themeNight();
    }

    @Override public void setTitle(CharSequence t) { title = t; if (isShowing()) build(); }
    public void setMessage(CharSequence m) { message = m; if (messageView != null) messageView.setText(m); }
    public void setView(View v) { custom = v; }
    public void setCustomTitle(View v) { customTitle = v; }
    public void setIcon(Drawable i) { icon = i; }
    public void setIcon(int r) { icon = ctx.getDrawable(r); }
    public void setButton(int which, CharSequence text, DialogInterface.OnClickListener l) { int i = idx(which); btnText[i] = text; btnListen[i] = l; }
    public Button getButton(int which) { return buttons[idx(which)]; }
    public ListView getListView() { return listView; }
    private static int idx(int which) { return which == BUTTON_POSITIVE ? 0 : which == BUTTON_NEGATIVE ? 1 : 2; }

    @Override public void show() {
        build();
        super.show();
    }

    private int px(float v) { return Math.round(v * d); }

    private void build() {
        Activity a = ctx instanceof Activity ? (Activity) ctx : null;
        int bg = a == null ? 0xFFFFFFFF : a.themeDialogBg();
        int primaryText = a == null ? 0xDE000000 : a.themeTextColor();
        int secondaryText = a == null ? 0x8A000000 : a.themeSecondaryText();
        int accent = a == null ? 0xFF0B6B53 : a.themeAccent();
        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(bg);
        g.setCornerRadius(px(2));
        panel.setBackground(g);
        panel.setElevation(px(24));
        panel.setClickable(true);
        boolean hasTitle = customTitle != null || (title != null && title.length() > 0);
        if (customTitle != null) panel.addView(customTitle, new LinearLayout.LayoutParams(-1, -2));
        else if (hasTitle) {
            TextView t = new TextView(ctx);
            t.setText(title);
            t.setTextSize(20);
            t.setTextColor(primaryText);
            t.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            t.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            t.setPadding(px(24), px(22), px(24), (message == null && custom == null && items == null && adapter == null) ? px(10) : px(10));
            if (icon != null) { icon.setBounds(0, 0, px(32), px(32)); t.setCompoundDrawablesRelative(icon, null, null, null); t.setCompoundDrawablePadding(px(8)); }
            panel.addView(t, new LinearLayout.LayoutParams(-1, -2));
        }
        LinearLayout.LayoutParams grow = new LinearLayout.LayoutParams(-1, -2, 1f);
        if (message != null) {
            ScrollView sv = new ScrollView(ctx);
            messageView = new TextView(ctx);
            messageView.setText(message);
            messageView.setTextSize(16);
            messageView.setTextColor(secondaryText);
            messageView.setLineSpacing(0, 1.1f);
            messageView.setPadding(px(24), hasTitle ? px(4) : px(24), px(24), px(16));
            sv.addView(messageView, new FrameLayout.LayoutParams(-1, -2));
            panel.addView(sv, new LinearLayout.LayoutParams(-1, -2, 1f));
        }
        if (items != null || adapter != null) {
            listView = new ListView(ctx);
            listView.setDivider(null);
            if (adapter != null) listView.setAdapter(adapter);
            else {
                final List<CharSequence> l = new ArrayList<>();
                for (CharSequence c : items) l.add(c);
                listView.setAdapter(new ChoiceAdapter(ctx, l, primaryText, accent));
            }
            listView.setOnItemClickListener((p, v, pos, id) -> {
                if (choiceMode == 2) {
                    multi[pos] = !multi[pos];
                    ((BaseAdapter) listView.getAdapter()).notifyDataSetChanged();
                    if (multiListener != null) multiListener.onClick(this, pos, multi[pos]);
                    return;
                }
                if (choiceMode == 1) {
                    checked = pos;
                    ((BaseAdapter) listView.getAdapter()).notifyDataSetChanged();
                    if (itemListener != null) itemListener.onClick(this, pos);
                    return;
                }
                if (itemListener != null) itemListener.onClick(this, pos);
                dismiss();
            });
            listView.setPadding(0, hasTitle ? 0 : px(8), 0, px(8));
            panel.addView(listView, new LinearLayout.LayoutParams(-1, -2, 1f));
        }
        if (custom != null) {
            // 11.3: كما في أندرويد: عنصر له أب يرمي خطأ (كان يُفصل بصمت فتمرّ أخطاء تُسقط التطبيق على الهاتف)
            FrameLayout holder = new FrameLayout(ctx);
            ViewGroup.LayoutParams cp = custom.getLayoutParams();
            holder.addView(custom, new FrameLayout.LayoutParams(-1, cp != null && cp.height > 0 ? cp.height : -2));
            panel.addView(holder, new LinearLayout.LayoutParams(-1, -2, 1f));
        }
        boolean anyBtn = btnText[0] != null || btnText[1] != null || btnText[2] != null;
        if (anyBtn) {
            LinearLayout bar = new LinearLayout(ctx);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setPadding(px(12), px(4), px(12), px(4));
            // ترتيب أندرويد: المحايد في البداية، ثم فراغ، ثم السلبي، ثم الإيجابي في النهاية
            int[] order = {2, -1, 1, 0};
            for (int k : order) {
                if (k == -1) { bar.addView(new View(ctx), new LinearLayout.LayoutParams(0, 1, 1f)); continue; }
                if (btnText[k] == null) { buttons[k] = null; continue; }
                final int which = k == 0 ? BUTTON_POSITIVE : k == 1 ? BUTTON_NEGATIVE : BUTTON_NEUTRAL;
                final DialogInterface.OnClickListener l = btnListen[k];
                Button b = new Button(ctx);
                b.setText(btnText[k]);
                b.setTextColor(accent);
                b.setTextSize(14);
                b.setAllCaps(false);
                b.setMinWidth(px(64));
                b.setMinimumWidth(px(64));
                b.setMinHeight(px(48));
                b.setPadding(px(8), 0, px(8), 0);
                b.setElevation(0);
                b.setStateListAnimator(null);
                b.setBackground(new RippleDrawable(android.content.res.ColorStateList.valueOf(night ? 0x33FFFFFF : 0x1F000000), null,
                        round(px(2))));
                b.setOnClickListener(v -> {
                    if (l != null) l.onClick(this, which);
                    dismiss();
                });
                buttons[k] = b;
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, px(48));
                bp.setMargins(px(2), px(4), px(2), px(4));
                bar.addView(b, bp);
            }
            panel.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        }
        // العرض: 95٪ من عمود الهاتف وبحد أقصى 560dp؛ الارتفاع لا يتجاوز النافذة
        int sw = ctx.getResources().getDisplayMetrics().widthPixels;
        int w = Math.min(Math.round(sw * 0.92f), px(560));
        if (sw < px(300)) w = sw - px(16);
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, -2, Gravity.CENTER);
        p.topMargin = p.bottomMargin = px(24);
        content.removeAllViews();
        content.addView(panel, new FrameLayout.LayoutParams(-1, -2));
        content.setLayoutParams(p);
        content.setLayoutDirection(View.LAYOUT_DIRECTION_INHERIT);
    }

    private static Drawable round(int r) { GradientDrawable g = new GradientDrawable(); g.setColor(0xFFFFFFFF); g.setCornerRadius(r); return g; }

    /** سطور القائمة (عادية، أو باختيار واحد، أو بعدة اختيارات). */
    private final class ChoiceAdapter extends BaseAdapter {
        final Context c; final List<CharSequence> l; final int fg, accent;
        ChoiceAdapter(Context c, List<CharSequence> l, int fg, int accent) { this.c = c; this.l = l; this.fg = fg; this.accent = accent; }
        @Override public int getCount() { return l.size(); }
        @Override public Object getItem(int i) { return l.get(i); }
        @Override public View getView(int pos, View convert, ViewGroup parent) {
            if (choiceMode == 1 || choiceMode == 2) {
                CompoundButton cb = choiceMode == 1 ? new RadioButton(c) : new CheckBox(c);
                cb.setText(l.get(pos));
                cb.setTextSize(16);
                cb.setTextColor(fg);
                cb.setChecked(choiceMode == 1 ? pos == checked : multi[pos]);
                cb.setPadding(px(18), 0, px(20), 0);
                cb.setClickable(false);
                cb.setFocusable(false);
                cb.setDuplicateParentStateEnabled(true);
                LinearLayout row = new LinearLayout(c);
                row.addView(cb, new LinearLayout.LayoutParams(-1, px(48)));
                return row;
            }
            TextView t = new TextView(c);
            t.setText(l.get(pos));
            t.setTextSize(16);
            t.setTextColor(fg);
            t.setMinHeight(px(48));
            t.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            t.setPadding(px(24), px(6), px(24), px(6));
            return t;
        }
    }

    public static class Builder {
        private final Context ctx;
        private final AlertDialog dlg;
        private boolean cancelable = true;
        private OnCancelListener cancel;
        private OnDismissListener dismiss;
        private OnKeyListener key;
        public Builder(Context c) { this(c, 0); }
        public Builder(Context c, int theme) { ctx = c; dlg = new AlertDialog(c, theme); }
        public Context getContext() { return ctx; }
        public Builder setTitle(CharSequence t) { dlg.title = t; return this; }
        public Builder setTitle(int r) { dlg.title = ctx.getString(r); return this; }
        public Builder setCustomTitle(View v) { dlg.customTitle = v; return this; }
        public Builder setMessage(CharSequence m) { dlg.message = m; return this; }
        public Builder setMessage(int r) { dlg.message = ctx.getString(r); return this; }
        public Builder setIcon(int r) { dlg.setIcon(r); return this; }
        public Builder setIcon(Drawable i) { dlg.icon = i; return this; }
        public Builder setView(View v) { dlg.custom = v; return this; }
        public Builder setView(int layout) { return this; }
        public Builder setPositiveButton(CharSequence t, DialogInterface.OnClickListener l) { dlg.setButton(BUTTON_POSITIVE, t, l); return this; }
        public Builder setPositiveButton(int r, DialogInterface.OnClickListener l) { return setPositiveButton(ctx.getString(r), l); }
        public Builder setNegativeButton(CharSequence t, DialogInterface.OnClickListener l) { dlg.setButton(BUTTON_NEGATIVE, t, l); return this; }
        public Builder setNegativeButton(int r, DialogInterface.OnClickListener l) { return setNegativeButton(ctx.getString(r), l); }
        public Builder setNeutralButton(CharSequence t, DialogInterface.OnClickListener l) { dlg.setButton(BUTTON_NEUTRAL, t, l); return this; }
        public Builder setNeutralButton(int r, DialogInterface.OnClickListener l) { return setNeutralButton(ctx.getString(r), l); }
        public Builder setItems(CharSequence[] items, DialogInterface.OnClickListener l) { dlg.items = items; dlg.itemListener = l; dlg.choiceMode = 0; return this; }
        public Builder setItems(int r, DialogInterface.OnClickListener l) { return this; }
        public Builder setAdapter(ListAdapter a, DialogInterface.OnClickListener l) { dlg.adapter = a; dlg.itemListener = l; return this; }
        public Builder setSingleChoiceItems(CharSequence[] items, int checked, DialogInterface.OnClickListener l) {
            dlg.items = items; dlg.checked = checked; dlg.itemListener = l; dlg.choiceMode = 1; return this;
        }
        public Builder setSingleChoiceItems(ListAdapter a, int checked, DialogInterface.OnClickListener l) { dlg.adapter = a; dlg.checked = checked; dlg.itemListener = l; dlg.choiceMode = 1; return this; }
        public Builder setMultiChoiceItems(CharSequence[] items, boolean[] checked, DialogInterface.OnMultiChoiceClickListener l) {
            dlg.items = items; dlg.multi = checked == null ? new boolean[items.length] : checked; dlg.multiListener = l; dlg.choiceMode = 2; return this;
        }
        public Builder setCancelable(boolean c) { cancelable = c; return this; }
        public Builder setOnCancelListener(OnCancelListener l) { cancel = l; return this; }
        public Builder setOnDismissListener(OnDismissListener l) { dismiss = l; return this; }
        public Builder setOnKeyListener(OnKeyListener l) { key = l; return this; }
        public AlertDialog create() {
            dlg.setCancelable(cancelable);
            if (cancel != null) dlg.setOnCancelListener(cancel);
            if (dismiss != null) dlg.setOnDismissListener(dismiss);
            if (key != null) dlg.setOnKeyListener(key);
            return dlg;
        }
        public AlertDialog show() { AlertDialog a = create(); a.show(); return a; }
    }
}
