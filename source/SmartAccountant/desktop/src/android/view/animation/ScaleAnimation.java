package android.view.animation;
public class ScaleAnimation extends Animation {
    final float fx, tx, fy, ty;
    public ScaleAnimation(float fx, float tx, float fy, float ty) { this.fx = fx; this.tx = tx; this.fy = fy; this.ty = ty; }
    public ScaleAnimation(float fx, float tx, float fy, float ty, int a, float b, int c, float d) { this(fx, tx, fy, ty); }
    public ScaleAnimation(float fx, float tx, float fy, float ty, float px, float py) { this(fx, tx, fy, ty); }
    @Override public void start(android.view.View v) {
        v.setScaleX(fx); v.setScaleY(fy);
        v.animate().scaleX(tx).scaleY(ty).setDuration(duration).setStartDelay(offset).setInterpolator(interp)
                .withEndAction(() -> { v.setScaleX(1); v.setScaleY(1); if (listener != null) listener.onAnimationEnd(this); }).start();
    }
}
