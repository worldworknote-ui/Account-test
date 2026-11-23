package android.animation;

/** تحريك ظهور العناصر عند إضافتها (يظهر العنصر الجديد تدريجيًا). */
public class LayoutTransition {
    public static final int CHANGE_APPEARING = 0, CHANGE_DISAPPEARING = 1, APPEARING = 2, DISAPPEARING = 3, CHANGING = 4;
    private long duration = 300;
    private boolean appearing = true;
    public void enableTransitionType(int t) { if (t == APPEARING) appearing = true; }
    public void disableTransitionType(int t) { if (t == APPEARING) appearing = false; }
    public void setDuration(long d) { duration = d; }
    public void setDuration(int type, long d) { duration = d; }
    public void setStartDelay(int type, long d) {}
    public void setInterpolator(int type, TimeInterpolator i) {}
    public void setAnimateParentHierarchy(boolean a) {}
    public void setAnimator(int type, Animator a) {}
    public void addTransitionListener(Object l) {}
    public void added(android.view.ViewGroup p, android.view.View v) {
        if (!appearing || Choreo.instant || !p.isAttachedToWindow()) return;
        v.setAlpha(0f);
        v.animate().alpha(1f).setDuration(duration).start();
    }
}
