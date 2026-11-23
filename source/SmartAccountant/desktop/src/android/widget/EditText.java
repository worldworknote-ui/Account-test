package android.widget;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.text.*;
import android.view.*;

/** خانة الكتابة: تُرسم كما في الهاتف، وحين تُركَّز تظهر فوقها خانة حقيقية للكتابة (TextOverlay). */
public class EditText extends TextView implements TextHost {
    private final Buffer buf = new Buffer();
    private int selS, selE;
    private boolean live, quiet;

    /** النص القابل للتعديل: كل تعديل يمر بمراقبي النص كما في أندرويد. */
    private final class Buffer extends SpannableStringBuilder {
        @Override public SpannableStringBuilder replace(int st, int en, CharSequence s, int a, int b) {
            CharSequence src = s.subSequence(a, b);
            for (InputFilter f : getFilters()) {
                CharSequence r = f.filter(src, 0, src.length(), this, st, en);
                if (r != null) src = r;
            }
            int before = en - st, after = src.length();
            for (TextWatcher w : new java.util.ArrayList<>(watchers)) w.beforeTextChanged(this, st, before, after);
            super.replace(st, en, src, 0, src.length());
            int caret = st + after;
            selS = selE = Math.min(caret, length());
            textChangedInternal();
            for (TextWatcher w : new java.util.ArrayList<>(watchers)) w.onTextChanged(this, st, before, after);
            for (TextWatcher w : new java.util.ArrayList<>(watchers)) w.afterTextChanged(this);
            return this;
        }
    }

    public EditText(Context c) {
        super(c);
        text = buf;
        setFocusable(true);
        setFocusableInTouchMode(true);
        setClickable(true);
        setLongClickable(true);
        float d = c.getResources().getDisplayMetrics().density;
        setTextSize(18);
        setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        setMinimumHeight(Math.round(48 * d));
        setPadding(Math.round(4 * d), Math.round(10 * d), Math.round(4 * d), Math.round(12 * d));
        setBackground(new Underline(c, d));
        setSingleLine(false);
        setInputType(InputType.TYPE_CLASS_TEXT);
    }
    public EditText(Context c, android.util.AttributeSet a) { this(c); }
    public EditText(Context c, android.util.AttributeSet a, int s) { this(c); }

    @Override public Editable getText() { return buf; }
    @Override public void setText(CharSequence t, BufferType type) {
        if (t == null) t = "";
        if (t == buf) return;
        buf.replace(0, buf.length(), t);
        selS = selE = buf.length();
    }
    private void textChangedInternal() {
        textChanged(null);
        TextOverlay.textChanged(this);
    }
    @Override protected void textChanged(CharSequence old) { super.textChanged(old); }

    public void setSelection(int i) { setSelection(i, i); }
    public void setSelection(int s, int e) {
        int n = buf.length();
        if (s < 0 || s > n || e < 0 || e > n) throw new IndexOutOfBoundsException("setSpan (" + s + " ... " + e + ") ends beyond length " + n);
        selS = Math.min(s, e);
        selE = Math.max(s, e);
        TextOverlay.textChanged(this);
    }
    public void selectAll() { setSelection(0, buf.length()); }
    public void extendSelection(int i) { setSelection(selS, i); }
    @Override public int getSelectionStart() { return selS; }
    @Override public int getSelectionEnd() { return selE; }
    @Override public boolean hasSelection() { return selS != selE; }
    @Override public boolean onCheckIsTextEditor() { return true; }
    @Override protected boolean hideTextForOverlay() { return live; }
    @Override public boolean performClick() { boolean r = super.performClick(); return r; }

    @Override public void setEnabled(boolean e) {
        super.setEnabled(e);
        if (!e && isFocused()) clearFocus();
    }

    // ------------------------------------------------------------------ TextHost
    @Override public String hostText() { return buf.toString(); }
    @Override public void userText(String s, int a, int b) {
        String cur = buf.toString();
        if (s.equals(cur)) return;
        int pre = 0, max = Math.min(s.length(), cur.length());
        while (pre < max && s.charAt(pre) == cur.charAt(pre)) pre++;
        int suf = 0;
        while (suf < max - pre && s.charAt(s.length() - 1 - suf) == cur.charAt(cur.length() - 1 - suf)) suf++;
        buf.replace(pre, cur.length() - suf, s.substring(pre, s.length() - suf));
        selS = Math.min(a, buf.length());
        selE = Math.min(b, buf.length());
    }
    @Override public void userSelection(int s, int e) { selS = Math.min(s, buf.length()); selE = Math.min(e, buf.length()); }
    @Override public int selStart() { return selS; }
    @Override public int selEnd() { return selE; }
    @Override public java.awt.Font hostFont() { return awtFont(); }
    @Override public int hostColor() { return getCurrentTextColor(); }
    @Override public int hostHighlight() { return getContext() instanceof android.app.Activity ? ((android.app.Activity) getContext()).themeAccent() : 0xFF0B6B53; }
    @Override public boolean singleLine() { return isSingleLineMode(); }
    @Override public boolean password() {
        return getTransformationMethod() instanceof android.text.method.PasswordTransformationMethod;
    }
    @Override public int numeric() {
        int t = getInputType(), cls = t & InputType.TYPE_MASK_CLASS;
        if (cls == InputType.TYPE_CLASS_NUMBER) return (t & InputType.TYPE_NUMBER_FLAG_DECIMAL) != 0 ? 2 : 1;
        if (cls == InputType.TYPE_CLASS_PHONE) return 3;
        return 0;
    }
    @Override public int maxLength() { return super.maxLength(); }
    @Override public int align() { return swingAlign(); }
    @Override public boolean rtlText() { return textRtl(); }
    @Override public boolean enterPressed() { return fireEditorAction(); }
    @Override public boolean editable() { return isEnabled(); }
    @Override public void liveChanged(boolean l) { live = l; invalidate(); }

    /** خط Material السفلي (للخانات بلا خلفية مخصصة). */
    static final class Underline extends Drawable {
        final float d;
        final int accent, normal;
        Underline(Context c, float d) {
            this.d = d;
            android.app.Activity a = c instanceof android.app.Activity ? (android.app.Activity) c : null;
            accent = a == null ? 0xFF0B6B53 : a.themeAccent();
            normal = a == null ? 0x8A000000 : a.themeHintColor();
        }
        @Override public boolean isStateful() { return true; }
        @Override protected boolean onStateChange(int[] s) { return true; }
        @Override public boolean getPadding(Rect p) { return false; }
        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            boolean f = false;
            for (int s : getState()) if (s == android.R.attr.state_focused) f = true;
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(f ? accent : normal);
            float th = f ? 2 * d : 1 * d;
            float y = b.bottom - 8 * d;
            c.drawRect(b.left + 4 * d, y - th, b.right - 4 * d, y, p);
        }
    }
}
