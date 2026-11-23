package android.view.animation;
public class TranslateAnimation extends Animation {
    final float fx, tx, fy, ty;
    public TranslateAnimation(float fx, float tx, float fy, float ty) { this.fx = fx; this.tx = tx; this.fy = fy; this.ty = ty; }
    @Override public void start(android.view.View v) {
        v.setTranslationX(fx); v.setTranslationY(fy);
        v.animate().translationX(tx).translationY(ty).setDuration(duration).setStartDelay(offset).setInterpolator(interp)
                .withEndAction(() -> { v.setTranslationX(0); v.setTranslationY(0); if (listener != null) listener.onAnimationEnd(this); }).start();
    }
}
