package android.animation;

import java.util.ArrayList;
import java.util.List;

public abstract class Animator implements Cloneable {
    public static final long DURATION_INFINITE = -1;
    public interface AnimatorListener {
        void onAnimationStart(Animator a);
        void onAnimationEnd(Animator a);
        void onAnimationCancel(Animator a);
        void onAnimationRepeat(Animator a);
        default void onAnimationStart(Animator a, boolean reverse) { onAnimationStart(a); }
        default void onAnimationEnd(Animator a, boolean reverse) { onAnimationEnd(a); }
    }
    public interface AnimatorPauseListener { void onAnimationPause(Animator a); void onAnimationResume(Animator a); }

    protected List<AnimatorListener> listeners;

    public abstract void start();
    public void cancel() {}
    public void end() {}
    public void pause() {}
    public void resume() {}
    public boolean isPaused() { return false; }
    public abstract long getStartDelay();
    public abstract void setStartDelay(long d);
    public abstract Animator setDuration(long d);
    public abstract long getDuration();
    public abstract void setInterpolator(TimeInterpolator i);
    public TimeInterpolator getInterpolator() { return null; }
    public abstract boolean isRunning();
    public boolean isStarted() { return isRunning(); }
    public void setTarget(Object t) {}
    public void setupStartValues() {}
    public void setupEndValues() {}
    public long getTotalDuration() { return getStartDelay() + getDuration(); }
    public void addListener(AnimatorListener l) { if (listeners == null) listeners = new ArrayList<>(); listeners.add(l); }
    public void removeListener(AnimatorListener l) { if (listeners != null) listeners.remove(l); }
    public ArrayList<AnimatorListener> getListeners() { return listeners == null ? null : new ArrayList<>(listeners); }
    public void removeAllListeners() { listeners = null; }
    public void addPauseListener(AnimatorPauseListener l) {}
    @Override public Animator clone() {
        try {
            Animator a = (Animator) super.clone();
            if (listeners != null) a.listeners = new ArrayList<>(listeners);
            return a;
        } catch (CloneNotSupportedException e) { throw new AssertionError(e); }
    }

    protected void notifyStart() { if (listeners != null) for (AnimatorListener l : new ArrayList<>(listeners)) l.onAnimationStart(this); }
    protected void notifyEnd() { if (listeners != null) for (AnimatorListener l : new ArrayList<>(listeners)) l.onAnimationEnd(this); }
    protected void notifyCancel() { if (listeners != null) for (AnimatorListener l : new ArrayList<>(listeners)) l.onAnimationCancel(this); }
    protected void notifyRepeat() { if (listeners != null) for (AnimatorListener l : new ArrayList<>(listeners)) l.onAnimationRepeat(this); }
}
