package android.widget;

import android.content.Context;
import android.database.DataSetObserver;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;

/** قائمة منسدلة: يعرض العنصر المختار وسهمًا، والضغط يفتح قائمة تحته. */
public class Spinner extends AdapterView<SpinnerAdapter> {
    public static final int MODE_DIALOG = 0, MODE_DROPDOWN = 1;
    private SpinnerAdapter adapter;
    private int selected = INVALID_POSITION, notified = -2;
    private View shown;
    private final DataSetObserver obs = new DataSetObserver() { @Override public void onChanged() { refresh(); } };
    private ViewRootImpl popup;
    private int dropW = -2, dropOffY;
    private Drawable popupBg;

    public Spinner(Context c) { this(c, MODE_DROPDOWN); }
    public Spinner(Context c, int mode) {
        super(c);
        setClickable(true);
        setFocusable(true);
        float d = c.getResources().getDisplayMetrics().density;
        setMinimumHeight(Math.round(48 * d));
        setPadding(Math.round(8 * d), 0, Math.round(8 * d), 0);
        boolean night = c instanceof android.app.Activity && ((android.app.Activity) c).themeNight();
        setBackground(new RippleDrawable(android.content.res.ColorStateList.valueOf(night ? 0x33FFFFFF : 0x1F000000), null, null));
        super.setOnClickListener(v -> openPopup());
    }
    public Spinner(Context c, android.util.AttributeSet a) { this(c); }

    @Override public SpinnerAdapter getAdapter() { return adapter; }
    @Override public void setAdapter(SpinnerAdapter a) {
        if (adapter != null) adapter.unregisterDataSetObserver(obs);
        adapter = a;
        if (a != null) a.registerDataSetObserver(obs);
        selected = a != null && a.getCount() > 0 ? 0 : INVALID_POSITION;
        notified = -2;
        refresh();
    }
    @Override public void setOnClickListener(View.OnClickListener l) { throw new RuntimeException("Don't call setOnClickListener for an AdapterView. You probably want setOnItemClickListener instead"); }
    @Override public void setSelection(int pos) { setSelection(pos, false); }
    public void setSelection(int pos, boolean animate) {
        if (adapter == null || pos < 0 || pos >= adapter.getCount()) return;
        if (pos == selected) return;
        selected = pos;
        refresh();
        scheduleNotify();
    }
    @Override public int getSelectedItemPosition() { return selected; }
    @Override public View getSelectedView() { return shown; }
    public void setDropDownWidth(int w) { dropW = w; }
    public void setDropDownVerticalOffset(int o) { dropOffY = o; }
    public void setDropDownHorizontalOffset(int o) {}
    public void setPopupBackgroundDrawable(Drawable d) { popupBg = d; }
    public void setPopupBackgroundResource(int r) {}
    public void setPrompt(CharSequence p) {}
    public void setGravity(int g) {}
    public boolean performClick() { super.performClick(); return true; }

    private void refresh() {
        removeAllViews();
        shown = null;
        if (adapter == null || adapter.getCount() == 0) { selected = INVALID_POSITION; requestLayout(); invalidate(); return; }
        if (selected >= adapter.getCount()) selected = adapter.getCount() - 1;
        if (selected < 0) selected = 0;
        shown = adapter.getView(selected, null, this);
        shown.setDuplicateParentStateEnabled(true);
        addView(shown, new ViewGroup.MarginLayoutParams(-1, -2));
        requestLayout();
        invalidate();
    }
    private void scheduleNotify() {
        post(() -> {
            if (selected == notified) return;
            notified = selected;
            if (itemSelected == null) return;
            if (selected >= 0) itemSelected.onItemSelected(this, shown, selected, adapter.getItemId(selected));
            else itemSelected.onNothingSelected(this);
        });
    }
    @Override public void setOnItemSelectedListener(OnItemSelectedListener l) { super.setOnItemSelectedListener(l); if (isAttachedToWindow()) scheduleNotify(); }

    private int arrowW() { return Math.round(24 * getResources().getDisplayMetrics().density); }
    @Override protected void onMeasure(int ws, int hs) {
        int pad = getPaddingLeft() + getPaddingRight() + arrowW();
        int w = 0, h = 0;
        if (shown != null) {
            // العرض بعرض أعرض عنصر (كما في أندرويد)
            int widest = 0;
            if (adapter != null && MeasureSpec.getMode(ws) != MeasureSpec.EXACTLY) {
                int n = Math.min(adapter.getCount(), 15);
                for (int i = 0; i < n; i++) {
                    View v = adapter.getView(i, null, this);
                    v.setLayoutParams(new ViewGroup.MarginLayoutParams(-2, -2));
                    v.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                    widest = Math.max(widest, v.getMeasuredWidth());
                }
            }
            int cws = MeasureSpec.getMode(ws) == MeasureSpec.UNSPECIFIED ? MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                    : MeasureSpec.makeMeasureSpec(Math.max(0, MeasureSpec.getSize(ws) - pad), MeasureSpec.getMode(ws) == MeasureSpec.EXACTLY ? MeasureSpec.EXACTLY : MeasureSpec.AT_MOST);
            shown.measure(cws, getChildMeasureSpec(hs, getPaddingTop() + getPaddingBottom(), -2));
            w = Math.max(shown.getMeasuredWidth(), widest);
            h = shown.getMeasuredHeight();
        }
        w = Math.max(w + pad, getSuggestedMinimumWidth());
        h = Math.max(h + getPaddingTop() + getPaddingBottom(), getSuggestedMinimumHeight());
        setMeasuredDimension(resolveSize(w, ws), resolveSize(h, hs));
        if (shown != null) {
            int cw = getMeasuredWidth() - pad;
            shown.measure(MeasureSpec.makeMeasureSpec(Math.max(0, cw), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(Math.max(0, getMeasuredHeight() - getPaddingTop() - getPaddingBottom()), MeasureSpec.AT_MOST));
        }
    }
    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        if (shown != null) {
            int aw = arrowW();
            int x = isRtl() ? getPaddingLeft() + aw : getPaddingLeft();
            int y = (b - t - shown.getMeasuredHeight()) / 2;
            shown.layout(x, y, x + shown.getMeasuredWidth(), y + shown.getMeasuredHeight());
        }
        if (notified == -2 && selected >= 0) scheduleNotify();
    }
    @Override protected void dispatchDraw(Canvas c) {
        super.dispatchDraw(c);
        float d = getResources().getDisplayMetrics().density;
        float cx = isRtl() ? getPaddingLeft() + arrowW() / 2f : getWidth() - getPaddingRight() - arrowW() / 2f, cy = getHeight() / 2f;
        Path p = new Path();
        p.moveTo(cx - 5 * d, cy - 2.5f * d);
        p.lineTo(cx + 5 * d, cy - 2.5f * d);
        p.lineTo(cx, cy + 2.5f * d);
        p.close();
        Paint pp = new Paint(Paint.ANTI_ALIAS_FLAG);
        boolean night = getContext() instanceof android.app.Activity && ((android.app.Activity) getContext()).themeNight();
        pp.setColor(isEnabled() ? (night ? 0xB3FFFFFF : 0x8A000000) : 0x42000000);
        c.drawPath(p, pp);
    }
    @Override public boolean onInterceptTouchEvent(MotionEvent e) { return true; }

    private void openPopup() {
        if (adapter == null || adapter.getCount() == 0 || !isEnabled()) return;
        if (popup != null) { closePopup(); return; }
        final Context ctx = getContext();
        float d = getResources().getDisplayMetrics().density;
        boolean night = ctx instanceof android.app.Activity && ((android.app.Activity) ctx).themeNight();
        int[] loc = new int[2];
        getLocationInWindow(loc);
        ViewRootImpl myRoot = root();
        int winW = myRoot == null ? 480 : myRoot.width(), winH = myRoot == null ? 800 : myRoot.height();
        FrameLayout host = new FrameLayout(ctx);
        ScrollView sv = new ScrollView(ctx);
        LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, Math.round(8 * d), 0, Math.round(8 * d));
        sv.addView(list, new FrameLayout.LayoutParams(-1, -2));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(night ? 0xFF2C3833 : 0xFFFFFFFF);
        bg.setCornerRadius(4 * d);
        sv.setBackground(popupBg != null ? popupBg : bg);
        sv.setElevation(8 * d);
        sv.setClipToOutline(true);
        for (int i = 0; i < adapter.getCount(); i++) {
            final int pos = i;
            View row = adapter.getDropDownView(i, null, list);
            if (row instanceof TextView && i == selected) ((TextView) row).setTypeface(android.graphics.Typeface.create(((TextView) row).getTypeface(), android.graphics.Typeface.BOLD));
            row.setBackground(new RippleDrawable(android.content.res.ColorStateList.valueOf(night ? 0x33FFFFFF : 0x1F000000), i == selected ? new ColorDrawable(night ? 0x1FFFFFFF : 0x0F000000) : null, new ColorDrawable(0xFFFFFFFF)));
            row.setClickable(true);
            row.setOnClickListener(v -> {
                closePopup();
                setSelection(pos);
                if (itemClick != null) itemClick.onItemClick(this, v, pos, adapter.getItemId(pos));
            });
            list.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        int w = dropW > 0 ? dropW : Math.max(getWidth(), Math.round(160 * d));
        w = Math.min(w, winW - Math.round(16 * d));
        int below = winH - (loc[1] + getHeight()) - Math.round(8 * d), above = loc[1] - Math.round(8 * d);
        sv.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int want = sv.getMeasuredHeight();
        boolean down = want <= below || below >= above;
        int h = Math.min(want, Math.max(Math.round(120 * d), down ? below : above));
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h, Gravity.TOP | Gravity.LEFT);
        int x = isRtl() ? loc[0] + getWidth() - w : loc[0];
        p.leftMargin = Math.max(Math.round(8 * d), Math.min(x, winW - w - Math.round(8 * d)));
        p.topMargin = down ? loc[1] + getHeight() + dropOffY : loc[1] - h;
        host.addView(sv, p);
        popup = android.app.WindowStack.add(host, ViewRootImpl.LAYER_POPUP);
        popup.onOutsideTouch = this::closePopup;
        popup.onBack = this::closePopup;
        host.setClickable(true);
        host.setOnClickListener(v -> closePopup());
        sv.setAlpha(0f);
        sv.setScaleY(0.92f);
        sv.setPivotY(down ? 0 : h);
        sv.animate().alpha(1f).scaleY(1f).setDuration(140).start();
        if (selected > 2) sv.post(() -> { View r = list.getChildAt(selected); if (r != null) sv.scrollTo(0, r.getTop() - h / 3); });
    }
    private void closePopup() {
        if (popup != null) android.app.WindowStack.remove(popup);
        popup = null;
    }
    @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); closePopup(); }
}
