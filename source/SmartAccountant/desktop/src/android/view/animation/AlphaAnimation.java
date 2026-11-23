package android.view.animation;
public class AlphaAnimation extends Animation {
    final float from, to;
    public AlphaAnimation(float from, float to) { this.from = from; this.to = to; }
    @Override public void start(android.view.View v) {
        v.setAlpha(from);
        v.animate().alpha(to).setDuration(duration).setStartDelay(offset).setInterpolator(interp).withEndAction(() -> { if (listener != null) listener.onAnimationEnd(this); }).start();
        if (listener != null) listener.onAnimationStart(this);
    }
}
