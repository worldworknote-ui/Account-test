package android.animation;

import java.util.ArrayList;
import java.util.List;

public class ValueAnimator extends Animator {
    public static final int RESTART = 1, REVERSE = 2, INFINITE = -1;
    public interface AnimatorUpdateListener { void onAnimationUpdate(ValueAnimator a); }

    private static final TimeInterpolator DEFAULT = new android.view.animation.AccelerateDecelerateInterpolator();

    long duration = 300, delay;
    TimeInterpolator interp = DEFAULT;
    PropertyValuesHolder[] values;
    private List<AnimatorUpdateListener> updates;
    private int repeatCount, repeatMode = RESTART, repeats;
    private boolean running, started, reversing;
    private long startNs = -1;
    private float fraction;

    public static ValueAnimator ofFloat(float... v) { ValueAnimator a = new ValueAnimator(); a.setFloatValues(v); return a; }
    public static ValueAnimator ofInt(int... v) { ValueAnimator a = new ValueAnimator(); a.setIntValues(v); return a; }
    public static ValueAnimator ofArgb(int... v) { ValueAnimator a = new ValueAnimator(); a.setIntValues(v); a.setEvaluator(ArgbEvaluator.getInstance()); return a; }
    public static ValueAnimator ofObject(TypeEvaluator ev, Object... v) { ValueAnimator a = new ValueAnimator(); a.setObjectValues(v); a.setEvaluator(ev); return a; }
    public static ValueAnimator ofPropertyValuesHolder(PropertyValuesHolder... h) { ValueAnimator a = new ValueAnimator(); a.values = h; return a; }
    public static boolean areAnimatorsEnabled() { return true; }
    public static float getDurationScale() { return 1f; }

    public void setFloatValues(float... v) { values = new PropertyValuesHolder[]{PropertyValuesHolder.ofFloat("", v)}; }
    public void setIntValues(int... v) { values = new PropertyValuesHolder[]{PropertyValuesHolder.ofInt("", v)}; }
    public void setObjectValues(Object... v) { values = new PropertyValuesHolder[]{PropertyValuesHolder.ofObject("", null, v)}; }
    public void setValues(PropertyValuesHolder... h) { values = h; }
    public PropertyValuesHolder[] getValues() { return values; }
    @SuppressWarnings("rawtypes")
    public void setEvaluator(TypeEvaluator ev) { if (values != null) for (PropertyValuesHolder h : values) h.evaluator = ev; }

    @Override public ValueAnimator setDuration(long d) { duration = Math.max(0, d); return this; }
    @Override public long getDuration() { return duration; }
    @Override public long getStartDelay() { return delay; }
    @Override public void setStartDelay(long d) { delay = Math.max(0, d); }
    @Override public void setInterpolator(TimeInterpolator i) { interp = i == null ? new android.view.animation.LinearInterpolator() : i; }
    @Override public TimeInterpolator getInterpolator() { return interp; }
    public void setRepeatCount(int c) { repeatCount = c; }
    public int getRepeatCount() { return repeatCount; }
    public void setRepeatMode(int m) { repeatMode = m; }
    public int getRepeatMode() { return repeatMode; }
    public void addUpdateListener(AnimatorUpdateListener l) { if (updates == null) updates = new ArrayList<>(); updates.add(l); }
    public void removeUpdateListener(AnimatorUpdateListener l) { if (updates != null) updates.remove(l); }
    public void removeAllUpdateListeners() { updates = null; }
    public Object getAnimatedValue() { return values == null || values.length == 0 ? null : values[0].value; }
    public Object getAnimatedValue(String name) { if (values != null) for (PropertyValuesHolder h : values) if (h.name.equals(name)) return h.value; return null; }
    public float getAnimatedFraction() { return fraction; }
    public long getCurrentPlayTime() { return startNs < 0 ? 0 : (System.nanoTime() - startNs) / 1_000_000L; }
    public void setCurrentPlayTime(long t) { animateValue(interp.getInterpolation(duration == 0 ? 1f : Math.min(1f, t / (float) duration))); }
    public void setCurrentFraction(float f) { animateValue(interp.getInterpolation(f)); }
    @Override public boolean isRunning() { return running; }
    @Override public boolean isStarted() { return started; }

    @Override public void start() { start(false); }
    public void reverse() { start(true); }
    private void start(boolean rev) {
        if (started) cancelQuiet();
        reversing = rev;
        started = true;
        running = delay == 0;
        repeats = 0;
        startNs = System.nanoTime();
        prepare();
        notifyStart();
        if (Choreo.instant) { finish(); return; }
        if (delay == 0) animateValue(interp.getInterpolation(rev ? 1f : 0f));
        if (duration == 0 && delay == 0) { finish(); return; }
        Choreo.add(this);
    }
    /** للمشتقات: قراءة قيم البداية (ObjectAnimator). */
    void prepare() {}

    void tick(long now) {
        if (!started) { Choreo.remove(this); return; }
        long ms = (now - startNs) / 1_000_000L - delay;
        if (ms < 0) return;
        if (!running) { running = true; }
        float f = duration == 0 ? 1f : ms / (float) duration;
        if (f >= 1f) {
            if (repeatCount == INFINITE || repeats < repeatCount) {
                repeats++;
                startNs = now;
                delay = 0;
                if (repeatMode == REVERSE) reversing = !reversing;
                notifyRepeat();
                f = 0;
            } else {
                finish();
                return;
            }
        }
        animateValue(interp.getInterpolation(reversing ? 1f - f : f));
    }

    private void finish() {
        Choreo.remove(this);
        animateValue(interp.getInterpolation(reversing ? 0f : 1f));
        running = false;
        started = false;
        notifyEnd();
    }

    void animateValue(float f) {
        fraction = f;
        if (values != null) for (PropertyValuesHolder h : values) h.calc(f);
        if (updates != null) for (AnimatorUpdateListener l : new ArrayList<>(updates)) l.onAnimationUpdate(this);
    }

    private void cancelQuiet() { Choreo.remove(this); running = false; started = false; }

    @Override public void cancel() {
        if (!started) return;
        cancelQuiet();
        notifyCancel();
        notifyEnd();
    }
    @Override public void end() {
        if (!started) { prepare(); started = true; }
        finish();
    }
    @Override public ValueAnimator clone() {
        ValueAnimator a = (ValueAnimator) super.clone();
        if (updates != null) a.updates = new ArrayList<>(updates);
        if (values != null) { a.values = new PropertyValuesHolder[values.length]; for (int i = 0; i < values.length; i++) a.values[i] = values[i].clone(); }
        a.started = false; a.running = false;
        return a;
    }
}
