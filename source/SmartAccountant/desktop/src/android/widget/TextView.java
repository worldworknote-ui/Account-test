package android.widget;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.text.*;
import android.text.method.*;
import android.view.*;
import java.util.ArrayList;
import java.util.List;

public class TextView extends View {
    public enum BufferType { NORMAL, SPANNABLE, EDITABLE }
    public interface OnEditorActionListener { boolean onEditorAction(TextView v, int actionId, KeyEvent e); }
    public static final int AUTO_SIZE_TEXT_TYPE_NONE = 0, AUTO_SIZE_TEXT_TYPE_UNIFORM = 1;

    protected CharSequence text = "";
    private CharSequence hint;
    private ColorStateList color = ColorStateList.valueOf(0xDE000000), hintColor = ColorStateList.valueOf(0x61000000), linkColor;
    protected final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private int gravity = Gravity.TOP | Gravity.START;
    private int maxLines = Integer.MAX_VALUE, minLines = 0, maxWidth = Integer.MAX_VALUE, minWidthPx = 0, maxHeight = Integer.MAX_VALUE, minHeightPx = 0;
    private int exactLines = -1, ems = -1;
    protected boolean singleLine;
    private TextUtils.TruncateAt ellipsize;
    private float spacingMult = 1f, spacingAdd = 0f;
    private boolean includePad = true, allCaps;
    private final Drawable[] compound = new Drawable[4];
    private boolean compoundRelative;
    private int compoundPad;
    private ColorStateList compoundTint;
    private TransformationMethod transform;
    protected final List<TextWatcher> watchers = new ArrayList<>();
    private OnEditorActionListener editorListener;
    private int imeOptions, inputType = InputType.TYPE_CLASS_TEXT;
    private InputFilter[] filters = new InputFilter[0];
    private boolean selectable, horizScroll;
    private float shadowR, shadowDx, shadowDy;
    private int shadowColor;
    private int autoMin = -1, autoMax = -1;
    private float autoSize = -1;
    private TextLines cache;
    private int cacheW = -2;
    private int textVersion;
    private int cacheVersion = -1;
    private Layout layoutView;
    private CharSequence error;
    private boolean cursorVisible = true;
    private int highlight = 0x6633B5E5;

    public TextView(Context c) {
        super(c);
        float d = c == null ? 1f : c.getResources().getDisplayMetrics().scaledDensity;
        paint.setTextSize(14 * d);
        paint.density = d;
        if (c instanceof android.app.Activity) {
            android.app.Activity a = (android.app.Activity) c;
            color = ColorStateList.valueOf(a.themeTextColor());
            hintColor = ColorStateList.valueOf(a.themeHintColor());
        }
    }
    public TextView(Context c, android.util.AttributeSet a) { this(c); }
    public TextView(Context c, android.util.AttributeSet a, int s) { this(c); }

    // ------------------------------------------------------------------ النص
    public void setText(CharSequence t) { setText(t, BufferType.NORMAL); }
    public void setText(CharSequence t, BufferType type) {
        if (t == null) t = "";
        CharSequence old = text;
        text = t;
        textChanged(old);
    }
    public final void setText(int res) { setText(getResources().getText(res)); }
    public final void setText(char[] c, int s, int n) { setText(new String(c, s, n)); }
    public void append(CharSequence t) { setText(TextUtils.concat(text, t)); }
    public void append(CharSequence t, int a, int b) { append(t.subSequence(a, b)); }
    public CharSequence getText() { return text; }
    public int length() { return text.length(); }
    protected void textChanged(CharSequence old) {
        textVersion++;
        requestLayout();
        invalidate();
    }
    public void setHint(CharSequence h) { hint = h; textVersion++; requestLayout(); invalidate(); }
    public final void setHint(int res) { setHint(getResources().getText(res)); }
    public CharSequence getHint() { return hint; }
    public void setError(CharSequence e) {
        error = e;
        invalidate();
        if (e != null && getContext() instanceof android.app.Activity && isAttachedToWindow()) {
            Toast.makeText(getContext(), e, Toast.LENGTH_LONG).show();
        }
    }
    public void setError(CharSequence e, Drawable icon) { setError(e); }
    public CharSequence getError() { return error; }

    // ------------------------------------------------------------------ المظهر
    public void setTextSize(float sp) { setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp); }
    public void setTextSize(int unit, float v) {
        float px = android.util.TypedValue.applyDimension(unit, v, getResources().getDisplayMetrics());
        if (px != paint.getTextSize()) { paint.setTextSize(px); textVersion++; requestLayout(); invalidate(); }
    }
    public float getTextSize() { return paint.getTextSize(); }
    public float getTextSizeDp() { return paint.getTextSize() / getResources().getDisplayMetrics().scaledDensity; }
    public void setTextColor(int c) { color = ColorStateList.valueOf(c); invalidate(); }
    public void setTextColor(ColorStateList c) { if (c != null) color = c; invalidate(); }
    public final ColorStateList getTextColors() { return color; }
    public final int getCurrentTextColor() { return color.getColorForState(getDrawableState(), color.getDefaultColor()); }
    public final void setHintTextColor(int c) { hintColor = ColorStateList.valueOf(c); invalidate(); }
    public final void setHintTextColor(ColorStateList c) { hintColor = c; invalidate(); }
    public final ColorStateList getHintTextColors() { return hintColor; }
    public final int getCurrentHintTextColor() { return hintColor.getDefaultColor(); }
    public final void setLinkTextColor(int c) { linkColor = ColorStateList.valueOf(c); }
    public void setHighlightColor(int c) { highlight = c; }
    public int getHighlightColor() { return highlight; }
    public void setTypeface(Typeface t) { if (t != paint.getTypeface()) { paint.setTypeface(t); textVersion++; requestLayout(); invalidate(); } }
    public void setTypeface(Typeface t, int style) { setTypeface(Typeface.create(t, style)); }
    public Typeface getTypeface() { return paint.getTypeface() == null ? Typeface.DEFAULT : paint.getTypeface(); }
    public TextPaint getPaint() { return paint; }
    public void setPaintFlags(int f) { if (paint.getFlags() != f) { paint.setFlags(f); textVersion++; requestLayout(); invalidate(); } }
    public int getPaintFlags() { return paint.getFlags(); }
    public void setLetterSpacing(float s) { paint.setLetterSpacing(s); textVersion++; requestLayout(); }
    public float getLetterSpacing() { return paint.getLetterSpacing(); }
    public void setShadowLayer(float r, float dx, float dy, int c) { shadowR = r; shadowDx = dx; shadowDy = dy; shadowColor = c; invalidate(); }
    public void setAllCaps(boolean a) { allCaps = a; textVersion++; requestLayout(); }
    public boolean isAllCaps() { return allCaps; }
    public void setTextAppearance(int res) {}
    public void setTextAppearance(Context c, int res) {}
    public void setElegantTextHeight(boolean e) {}
    public void setFontFeatureSettings(String s) {}
    public void setFallbackLineSpacing(boolean b) {}
    public void setTextLocale(java.util.Locale l) {}
    public void setTextScaleX(float s) {}

    public void setGravity(int g) {
        if ((g & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK) == 0) g |= Gravity.START;
        if ((g & Gravity.VERTICAL_GRAVITY_MASK) == 0) g |= Gravity.TOP;
        if (g != gravity) { gravity = g; invalidate(); }
    }
    public int getGravity() { return gravity; }

    public void setSingleLine() { setSingleLine(true); }
    public void setSingleLine(boolean s) {
        singleLine = s;
        if (s) { maxLines = 1; horizScroll = true; } else { if (maxLines == 1) maxLines = Integer.MAX_VALUE; horizScroll = false; }
        textVersion++;
        requestLayout();
        invalidate();
    }
    public boolean isSingleLine() { return singleLine; }
    public void setMaxLines(int m) { maxLines = m <= 0 ? Integer.MAX_VALUE : m; textVersion++; requestLayout(); invalidate(); }
    public int getMaxLines() { return maxLines; }
    public void setMinLines(int m) { minLines = m; requestLayout(); }
    public int getMinLines() { return minLines; }
    public void setLines(int n) { exactLines = n; maxLines = n; minLines = n; textVersion++; requestLayout(); }
    public void setEllipsize(TextUtils.TruncateAt e) { ellipsize = e; textVersion++; requestLayout(); invalidate(); }
    public TextUtils.TruncateAt getEllipsize() { return ellipsize; }
    public void setLineSpacing(float add, float mult) { spacingAdd = add; spacingMult = mult; textVersion++; requestLayout(); }
    public float getLineSpacingMultiplier() { return spacingMult; }
    public float getLineSpacingExtra() { return spacingAdd; }
    public int getLineHeight() { return Math.round((paint.descent() + paint.ascent()) * spacingMult + spacingAdd); }
    public void setLineHeight(int h) {}
    public void setIncludeFontPadding(boolean p) { includePad = p; textVersion++; requestLayout(); }
    public boolean getIncludeFontPadding() { return includePad; }
    public void setMaxWidth(int w) { maxWidth = w; requestLayout(); }
    public int getMaxWidth() { return maxWidth; }
    public void setMinWidth(int w) { minWidthPx = w; requestLayout(); }
    public int getMinWidth() { return minWidthPx; }
    public void setMaxHeight(int h) { maxHeight = h; requestLayout(); }
    public void setMinHeight(int h) { minHeightPx = h; requestLayout(); }
    public int getMinHeight() { return minHeightPx; }
    public void setWidth(int w) { minWidthPx = w; maxWidth = w; requestLayout(); }
    public void setHeight(int h) { minHeightPx = h; maxHeight = h; requestLayout(); }
    public void setEms(int e) { ems = e; requestLayout(); }
    public void setMinEms(int e) {}
    public void setMaxEms(int e) {}
    public void setHorizontallyScrolling(boolean h) { horizScroll = h; requestLayout(); }
    public void setBreakStrategy(int s) {}
    public void setHyphenationFrequency(int f) {}
    public void setJustificationMode(int m) {}
    public void setAutoSizeTextTypeUniformWithConfiguration(int min, int max, int step, int unit) {
        float d = getResources().getDisplayMetrics().scaledDensity;
        autoMin = Math.round(unit == android.util.TypedValue.COMPLEX_UNIT_SP ? min * d : min);
        autoMax = Math.round(unit == android.util.TypedValue.COMPLEX_UNIT_SP ? max * d : max);
        requestLayout();
    }
    public void setAutoSizeTextTypeWithDefaults(int t) { if (t == AUTO_SIZE_TEXT_TYPE_NONE) autoMin = autoMax = -1; }

    // ------------------------------------------------------------------ الصور الجانبية
    public void setCompoundDrawables(Drawable l, Drawable t, Drawable r, Drawable b) { compound[0] = l; compound[1] = t; compound[2] = r; compound[3] = b; compoundRelative = false; requestLayout(); invalidate(); }
    public void setCompoundDrawablesRelative(Drawable s, Drawable t, Drawable e, Drawable b) { compound[0] = s; compound[1] = t; compound[2] = e; compound[3] = b; compoundRelative = true; requestLayout(); invalidate(); }
    public void setCompoundDrawablesWithIntrinsicBounds(Drawable l, Drawable t, Drawable r, Drawable b) {
        for (Drawable d : new Drawable[]{l, t, r, b}) if (d != null) d.setBounds(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
        setCompoundDrawables(l, t, r, b);
    }
    public void setCompoundDrawablesWithIntrinsicBounds(int l, int t, int r, int b) {
        Context c = getContext();
        setCompoundDrawablesWithIntrinsicBounds(l == 0 ? null : c.getDrawable(l), t == 0 ? null : c.getDrawable(t), r == 0 ? null : c.getDrawable(r), b == 0 ? null : c.getDrawable(b));
    }
    public void setCompoundDrawablesRelativeWithIntrinsicBounds(Drawable s, Drawable t, Drawable e, Drawable b) {
        for (Drawable d : new Drawable[]{s, t, e, b}) if (d != null) d.setBounds(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
        setCompoundDrawablesRelative(s, t, e, b);
    }
    public void setCompoundDrawablesRelativeWithIntrinsicBounds(int s, int t, int e, int b) {
        Context c = getContext();
        setCompoundDrawablesRelativeWithIntrinsicBounds(s == 0 ? null : c.getDrawable(s), t == 0 ? null : c.getDrawable(t), e == 0 ? null : c.getDrawable(e), b == 0 ? null : c.getDrawable(b));
    }
    public Drawable[] getCompoundDrawables() { return absCompound(); }
    public Drawable[] getCompoundDrawablesRelative() { return compound.clone(); }
    public void setCompoundDrawablePadding(int p) { compoundPad = p; requestLayout(); invalidate(); }
    public int getCompoundDrawablePadding() { return compoundPad; }
    public void setCompoundDrawableTintList(ColorStateList t) { compoundTint = t; for (Drawable d : compound) if (d != null) d.setTintList(t); invalidate(); }
    private Drawable[] absCompound() {
        if (!compoundRelative || !isRtl()) return compound.clone();
        return new Drawable[]{compound[2], compound[1], compound[0], compound[3]};
    }
    private int cw(Drawable d) { return d == null ? 0 : d.getBounds().width() + compoundPad; }
    private int ch(Drawable d) { return d == null ? 0 : d.getBounds().height() + compoundPad; }
    public int getCompoundPaddingLeft() { return getPaddingLeft() + cw(absCompound()[0]); }
    public int getCompoundPaddingRight() { return getPaddingRight() + cw(absCompound()[2]); }
    public int getCompoundPaddingTop() { return getPaddingTop() + ch(compound[1]); }
    public int getCompoundPaddingBottom() { return getPaddingBottom() + ch(compound[3]); }
    public int getTotalPaddingLeft() { return getCompoundPaddingLeft(); }
    public int getTotalPaddingRight() { return getCompoundPaddingRight(); }
    public int getTotalPaddingTop() { return getCompoundPaddingTop(); }

    // ------------------------------------------------------------------ الإدخال (للخانات)
    public void setInputType(int t) {
        inputType = t;
        int v = t & InputType.TYPE_MASK_VARIATION;
        boolean pwd = ((t & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT && (v == InputType.TYPE_TEXT_VARIATION_PASSWORD || v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
                || ((t & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_NUMBER && v == InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        if (pwd) transform = PasswordTransformationMethod.getInstance();
        else if (transform instanceof PasswordTransformationMethod) transform = null;
        boolean multi = (t & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0 && (t & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT;
        if (this instanceof EditText) setSingleLine(!multi);
        textVersion++;
        invalidate();
    }
    public int getInputType() { return inputType; }
    public void setRawInputType(int t) { inputType = t; }
    public void setImeOptions(int o) { imeOptions = o; }
    public int getImeOptions() { return imeOptions; }
    public void setImeActionLabel(CharSequence l, int id) {}
    public void setOnEditorActionListener(OnEditorActionListener l) { editorListener = l; }
    public void onEditorAction(int code) { if (editorListener != null) editorListener.onEditorAction(this, code, null); }
    protected boolean fireEditorAction() {
        int act = imeOptions & android.view.inputmethod.EditorInfo.IME_MASK_ACTION;
        if (act == 0) act = singleLine ? android.view.inputmethod.EditorInfo.IME_ACTION_DONE : android.view.inputmethod.EditorInfo.IME_ACTION_UNSPECIFIED;
        if (editorListener != null && editorListener.onEditorAction(this, act, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))) return true;
        if (dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))) return true;
        return false;
    }
    public void setTransformationMethod(TransformationMethod m) { transform = m; textVersion++; requestLayout(); invalidate(); android.view.TextOverlay.textChanged(this); }
    public final TransformationMethod getTransformationMethod() { return transform; }
    public void setKeyListener(KeyListener k) { if (k != null) inputType = k.getInputType(); }
    public KeyListener getKeyListener() { return null; }
    public void setFilters(InputFilter[] f) { filters = f == null ? new InputFilter[0] : f; }
    public InputFilter[] getFilters() { return filters; }
    public void addTextChangedListener(TextWatcher w) { if (w != null && !watchers.contains(w)) watchers.add(w); }
    public void removeTextChangedListener(TextWatcher w) { watchers.remove(w); }
    public void setMovementMethod(MovementMethod m) {}
    public void setLinksClickable(boolean c) {}
    public void setAutoLinkMask(int m) {}
    public void setTextIsSelectable(boolean s) { selectable = s; }
    public boolean isTextSelectable() { return selectable; }
    public void setCursorVisible(boolean v) { cursorVisible = v; }
    public void setSelectAllOnFocus(boolean s) {}
    public void setFreezesText(boolean f) {}
    public void setShowSoftInputOnFocus(boolean s) {}
    public void setHorizontalFadingEdgeEnabled(boolean e) {}
    public void setMarqueeRepeatLimit(int l) {}
    public void setPrivateImeOptions(String o) {}
    public int getSelectionStart() { return -1; }
    public int getSelectionEnd() { return -1; }
    public boolean hasSelection() { return false; }
    public boolean didTouchFocusSelect() { return false; }
    public void setCustomSelectionActionModeCallback(Object c) {}
    public void setCustomInsertionActionModeCallback(Object c) {}

    // ------------------------------------------------------------------ التخطيط
    /** النص المعروض بعد التحويل (كلمة السر نقاط). */
    protected CharSequence shown() {
        CharSequence t = text;
        if (transform != null) t = transform.getTransformation(t, this);
        return t;
    }
    private int dirMode() {
        int d = getTextDirection();
        switch (d) {
            case TEXT_DIRECTION_ANY_RTL: return TextLines.DIR_ANY_RTL;
            case TEXT_DIRECTION_LTR: return TextLines.DIR_LTR;
            case TEXT_DIRECTION_RTL: return TextLines.DIR_RTL;
            case TEXT_DIRECTION_LOCALE: return TextLines.DIR_RTL;
            case TEXT_DIRECTION_FIRST_STRONG_LTR: return TextLines.DIR_FIRST;
            default: return TextLines.DIR_FIRST;
        }
    }
    private boolean dirFallback() {
        int d = getTextDirection();
        if (d == TEXT_DIRECTION_FIRST_STRONG_LTR) return false;
        if (d == TEXT_DIRECTION_FIRST_STRONG_RTL) return true;
        return isRtl();
    }
    TextLines lines(int width, CharSequence t) {
        if (cache != null && cacheW == width && cacheVersion == textVersion) return cache;
        TextLines.Spec s = new TextLines.Spec();
        s.paint = paint;
        s.width = horizScroll && singleLine ? -1 : width;
        s.mult = spacingMult; s.add = spacingAdd;
        s.dirMode = dirMode();
        s.rtlFallback = dirFallback();
        s.maxLines = maxLines;
        s.ell = ellipsize;
        if (singleLine && ellipsize != null && s.width <= 0) s.width = width;
        s.singleLine = singleLine;
        s.includePad = includePad;
        s.allCaps = allCaps;
        s.density = getResources().getDisplayMetrics().density;
        if (singleLine && ellipsize != null) { s.width = width; s.singleLine = true; }
        TextLines l = TextLines.build(t, s);
        // للسطر الواحد الطويل بلا «…» نقص العرض فقط (كما في أندرويد)
        cache = l;
        cacheW = width;
        cacheVersion = textVersion;
        return l;
    }
    /** الأسطر المعروضة: النص، أو التلميح إن كان فارغًا. */
    protected TextLines currentLines(int width) {
        CharSequence t = shown();
        if (t.length() == 0 && hint != null && hint.length() > 0) return linesFor(hint, width, true);
        return lines(width, t);
    }
    private TextLines hintCache;
    private int hintW = -2, hintV = -1;
    private TextLines linesFor(CharSequence h, int width, boolean isHint) {
        if (hintCache != null && hintW == width && hintV == textVersion) return hintCache;
        TextLines.Spec s = new TextLines.Spec();
        s.paint = paint; s.width = singleLine ? width : width; s.mult = spacingMult; s.add = spacingAdd;
        s.dirMode = dirMode(); s.rtlFallback = dirFallback(); s.maxLines = maxLines; s.ell = singleLine ? TextUtils.TruncateAt.END : ellipsize;
        s.singleLine = singleLine; s.includePad = includePad;
        s.density = getResources().getDisplayMetrics().density;
        hintCache = TextLines.build(h, s);
        hintW = width;
        hintV = textVersion;
        return hintCache;
    }
    private float oneLine() {
        TextLines.Spec s = new TextLines.Spec();
        s.paint = paint; s.includePad = includePad; s.mult = spacingMult; s.add = spacingAdd;
        return TextLines.build("A", s).height();
    }

    @Override protected void onMeasure(int ws, int hs) {
        int wm = MeasureSpec.getMode(ws), wsz = MeasureSpec.getSize(ws), hm = MeasureSpec.getMode(hs), hsz = MeasureSpec.getSize(hs);
        Drawable[] cd = absCompound();
        int padH = getPaddingLeft() + getPaddingRight() + cw(cd[0]) + cw(cd[2]);
        int padV = getPaddingTop() + getPaddingBottom() + ch(cd[1]) + ch(cd[3]);
        int width;
        if (autoMax > 0 && wm != MeasureSpec.UNSPECIFIED) autoFit(wsz - padH, hm == MeasureSpec.EXACTLY ? hsz - padV : -1);
        if (wm == MeasureSpec.EXACTLY) width = wsz;
        else {
            TextLines free = linesUnwrapped();
            int desired = (int) Math.ceil(free.maxWidth()) + padH;
            if (ems > 0) desired = Math.round(ems * paint.getTextSize()) + padH;
            for (Drawable d : new Drawable[]{cd[1], cd[3]}) if (d != null) desired = Math.max(desired, d.getBounds().width() + padH);
            desired = Math.max(desired, Math.max(minWidthPx, getSuggestedMinimumWidth()));
            desired = Math.min(desired, maxWidth == Integer.MAX_VALUE ? desired : maxWidth);
            width = wm == MeasureSpec.AT_MOST ? Math.min(desired, wsz) : desired;
        }
        int avail = Math.max(0, width - padH);
        TextLines tl = currentLines(avail);
        float textH = tl.count() == 0 ? oneLine() : tl.height();
        if (exactLines > 0 || minLines > 0) {
            float one = oneLine();
            int want = exactLines > 0 ? exactLines : minLines;
            if (tl.count() < want) textH = Math.max(textH, one * want);
        }
        int h = (int) Math.ceil(textH) + padV;
        for (Drawable d : new Drawable[]{cd[0], cd[2]}) if (d != null) h = Math.max(h, d.getBounds().height() + getPaddingTop() + getPaddingBottom());
        h = Math.max(h, Math.max(minHeightPx, getSuggestedMinimumHeight()));
        if (maxHeight != Integer.MAX_VALUE) h = Math.min(h, maxHeight);
        int height = hm == MeasureSpec.EXACTLY ? hsz : hm == MeasureSpec.AT_MOST ? Math.min(h, hsz) : h;
        setMeasuredDimension(width, height);
    }
    private TextLines unwrapped;
    private int unwrappedV = -1;
    private TextLines linesUnwrapped() {
        if (unwrapped != null && unwrappedV == textVersion) return unwrapped;
        TextLines.Spec s = new TextLines.Spec();
        CharSequence t = shown();
        if (t.length() == 0 && hint != null) t = hint;
        s.paint = paint; s.width = -1; s.dirMode = dirMode(); s.rtlFallback = dirFallback(); s.singleLine = singleLine; s.allCaps = allCaps;
        s.maxLines = Integer.MAX_VALUE; s.includePad = includePad; s.density = getResources().getDisplayMetrics().density;
        unwrapped = TextLines.build(t, s);
        unwrappedV = textVersion;
        return unwrapped;
    }
    private void autoFit(int w, int h) {
        float lo = autoMin, hi = autoMax, best = autoMin;
        float keep = paint.getTextSize();
        for (int i = 0; i < 8; i++) {
            float mid = (lo + hi) / 2f;
            paint.setTextSize(mid);
            textVersion++;
            TextLines.Spec s = new TextLines.Spec();
            s.paint = paint; s.width = singleLine ? -1 : w; s.maxLines = maxLines; s.singleLine = singleLine;
            TextLines l = TextLines.build(shown(), s);
            boolean fits = l.maxWidth() <= w && (h < 0 || l.height() <= h) && l.count() <= maxLines;
            if (fits) { best = mid; lo = mid; } else hi = mid;
        }
        paint.setTextSize(best);
        if (best != keep) textVersion++;
    }

    @Override public int getBaseline() {
        int avail = Math.max(0, getWidth() - getCompoundPaddingLeft() - getCompoundPaddingRight());
        TextLines tl = currentLines(avail);
        return getCompoundPaddingTop() + verticalOffset(tl) + Math.round(tl.count() == 0 ? oneLine() * 0.75f : tl.firstBaseline());
    }
    private int verticalOffset(TextLines tl) {
        int vg = gravity & Gravity.VERTICAL_GRAVITY_MASK;
        int boxH = getHeight() - getCompoundPaddingTop() - getCompoundPaddingBottom();
        float th = tl.count() == 0 ? oneLine() : tl.height();
        if (vg == Gravity.TOP) return 0;
        if (vg == Gravity.BOTTOM) return Math.max(0, Math.round(boxH - th));
        return Math.round((boxH - th) / 2f);
    }
    public int getLineCount() { return currentLines(Math.max(0, getWidth() - getCompoundPaddingLeft() - getCompoundPaddingRight())).count(); }
    public Layout getLayout() {
        if (getWidth() == 0) return null;
        return new Layout(shown(), paint, Math.max(1, getWidth() - getCompoundPaddingLeft() - getCompoundPaddingRight()), Layout.Alignment.ALIGN_NORMAL, spacingMult, spacingAdd, isRtl(), maxLines, ellipsize) {};
    }

    // ------------------------------------------------------------------ الرسم
    /** خانات الكتابة لا ترسم نصها حين تظهر الخانة الحقيقية فوقها. */
    protected boolean hideTextForOverlay() { return false; }

    @Override protected void onDraw(Canvas c) {
        int[] st = getDrawableState();
        Drawable[] cd = absCompound();
        int w = getWidth(), h = getHeight();
        int l = getCompoundPaddingLeft(), r = getCompoundPaddingRight(), t = getCompoundPaddingTop(), b = getCompoundPaddingBottom();
        // الصور الجانبية
        if (cd[0] != null) drawCompound(c, cd[0], getPaddingLeft(), (t + h - b - cd[0].getBounds().height()) / 2);
        if (cd[2] != null) drawCompound(c, cd[2], w - getPaddingRight() - cd[2].getBounds().width(), (t + h - b - cd[2].getBounds().height()) / 2);
        if (cd[1] != null) drawCompound(c, cd[1], (l + w - r - cd[1].getBounds().width()) / 2, getPaddingTop());
        if (cd[3] != null) drawCompound(c, cd[3], (l + w - r - cd[3].getBounds().width()) / 2, h - getPaddingBottom() - cd[3].getBounds().height());
        if (hideTextForOverlay()) {
            if (text.length() > 0) return;
        }
        boolean showingHint = shown().length() == 0;
        if (showingHint && (hint == null || hint.length() == 0)) return;
        int avail = Math.max(0, w - l - r);
        TextLines tl = currentLines(avail);
        int col = showingHint ? hintColor.getColorForState(st, hintColor.getDefaultColor()) : color.getColorForState(st, color.getDefaultColor());
        if (!isEnabled() && !showingHint) col = (Math.round(Color.alpha(col) * 0.5f) << 24) | (col & 0xFFFFFF);
        int hg = Gravity.getAbsoluteGravity(gravity, getLayoutDirection()) & Gravity.HORIZONTAL_GRAVITY_MASK;
        boolean relative = (gravity & Gravity.RELATIVE_LAYOUT_DIRECTION) != 0;
        int mode;
        int ta = getTextAlignment();
        if (ta == TEXT_ALIGNMENT_CENTER) mode = 1;
        else if (ta == TEXT_ALIGNMENT_VIEW_START) mode = isRtl() ? 4 : 3;
        else if (ta == TEXT_ALIGNMENT_VIEW_END) mode = isRtl() ? 3 : 4;
        else if (ta == TEXT_ALIGNMENT_TEXT_START) mode = 0;
        else if (ta == TEXT_ALIGNMENT_TEXT_END) mode = 2;
        else if (hg == Gravity.CENTER_HORIZONTAL) mode = 1;
        else if (relative) {
            // START/END: حسب اتجاه الفقرة (ALIGN_NORMAL/OPPOSITE) كما في أندرويد
            int rg = gravity & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK;
            mode = (rg & Gravity.END) == Gravity.END ? 2 : 0;
        } else mode = hg == Gravity.RIGHT ? 4 : 3;
        c.save();
        c.clipRect(l - 1, getPaddingTop() - 2, w - r + 1, h - getPaddingBottom() + 2);
        float y = t + verticalOffset(tl);
        if (shadowR > 0 && Color.alpha(shadowColor) > 0) {
            float o = c.alpha;
            c.save();
            c.translate(shadowDx, shadowDy);
            c.alpha = o * 0.8f;
            tl.draw(c, l, y, avail, mode, shadowColor);
            c.restore();
        }
        tl.draw(c, l, y, avail, mode, col);
        c.restore();
    }
    private void drawCompound(Canvas c, Drawable d, int x, int y) {
        c.save();
        c.translate(x - d.getBounds().left, y - d.getBounds().top);
        d.setState(getDrawableState());
        d.draw(c);
        c.restore();
    }
    @Override protected void drawableStateChanged() {
        super.drawableStateChanged();
        for (Drawable d : compound) if (d != null) d.setState(getDrawableState());
        if (color.isStateful()) invalidate();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (text instanceof Spanned && e.getAction() == MotionEvent.ACTION_UP) {
            android.text.style.ClickableSpan[] cs = ((Spanned) text).getSpans(0, text.length(), android.text.style.ClickableSpan.class);
            if (cs.length == 1 && !isClickable()) { cs[0].onClick(this); return true; }
        }
        return super.onTouchEvent(e);
    }

    /** المساحة والنمط للخانة الحقيقية (EditText). */
    protected java.awt.Font awtFont() {
        Typeface tf = getTypeface();
        return Typeface.base(tf.familyName(), tf.isBold()).deriveFont(paint.getTextSize());
    }
    protected boolean textRtl() {
        int m = dirMode();
        String s = text.toString();
        if (m == TextLines.DIR_LTR) return false;
        if (m == TextLines.DIR_RTL) return true;
        if (m == TextLines.DIR_ANY_RTL) { for (char ch : s.toCharArray()) { byte d = Character.getDirectionality(ch); if (d == 1 || d == 2) return true; } return dirFallback() && s.isEmpty(); }
        if (s.isEmpty() && hint != null) s = hint.toString();
        for (char ch : s.toCharArray()) { byte d = Character.getDirectionality(ch); if (d == 1 || d == 2) return true; if (d == 0) return false; }
        return dirFallback();
    }
    protected int swingAlign() {
        int hg = Gravity.getAbsoluteGravity(gravity, getLayoutDirection()) & Gravity.HORIZONTAL_GRAVITY_MASK;
        if (hg == Gravity.CENTER_HORIZONTAL) return javax.swing.SwingConstants.CENTER;
        if ((gravity & Gravity.RELATIVE_LAYOUT_DIRECTION) != 0) return textRtl() ? javax.swing.SwingConstants.RIGHT : javax.swing.SwingConstants.LEFT;
        return hg == Gravity.RIGHT ? javax.swing.SwingConstants.RIGHT : javax.swing.SwingConstants.LEFT;
    }
    protected boolean isSingleLineMode() { return singleLine; }
    protected int maxLength() {
        for (InputFilter f : filters) if (f instanceof InputFilter.LengthFilter) return ((InputFilter.LengthFilter) f).getMax();
        return -1;
    }
}
