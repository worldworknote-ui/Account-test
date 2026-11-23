package android.view;

import android.animation.Animator;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import java.util.*;

/**
 * view.animate() كما في أندرويد: المدة والتأخير ومنحنى الحركة والمستمع تبقى للمرات التالية، والحركة تبدأ تلقائيًا
 * في الإطار التالي إن لم يُستدعَ start()، وحركة جديدة لخاصية تلغي القديمة لها فقط.
 */
public class ViewPropertyAnimator {
    static final int ALPHA = 0, TX = 1, TY = 2, TZ = 3, SX = 4, SY = 5, ROT = 6, X = 7, Y = 8, Z = 9, RX = 10, RY = 11;

    private final View view;
    private long duration = 300, delay;
    private boolean durationSet;
    private TimeInterpolator interp;
    private Animator.AnimatorListener listener;
    private ValueAnimator.AnimatorUpdateListener update;
    private final Map<Integer, Float> pending = new LinkedHashMap<>();
    private Runnable startAction, endAction;
    private boolean scheduled;
    private final List<Run> runs = new ArrayList<>();

    private final class Run {
        final Map<Integer, float[]> props = new LinkedHashMap<>();
        ValueAnimator anim;
        Runnable start, end;
        Animator.AnimatorListener listener;
    }

    ViewPropertyAnimator(View v) { view = v; }

    public ViewPropertyAnimator setDuration(long d) { duration = Math.max(0, d); durationSet = true; return this; }
    public long getDuration() { return duration; }
    public ViewPropertyAnimator setStartDelay(long d) { delay = Math.max(0, d); return this; }
    public long getStartDelay() { return delay; }
    public ViewPropertyAnimator setInterpolator(TimeInterpolator i) { interp = i; return this; }
    public TimeInterpolator getInterpolator() { return interp; }
    public ViewPropertyAnimator setListener(Animator.AnimatorListener l) { listener = l; return this; }
    public ViewPropertyAnimator setUpdateListener(ValueAnimator.AnimatorUpdateListener l) { update = l; return this; }
    public ViewPropertyAnimator withStartAction(Runnable r) { startAction = r; schedule(); return this; }
    public ViewPropertyAnimator withEndAction(Runnable r) { endAction = r; schedule(); return this; }
    public ViewPropertyAnimator withLayer() { return this; }

    private ViewPropertyAnimator to(int p, float v) { pending.put(p, v); schedule(); return this; }
    private ViewPropertyAnimator by(int p, float v) { pending.put(p, get(p) + v); schedule(); return this; }
    public ViewPropertyAnimator alpha(float v) { return to(ALPHA, v); }
    public ViewPropertyAnimator alphaBy(float v) { return by(ALPHA, v); }
    public ViewPropertyAnimator translationX(float v) { return to(TX, v); }
    public ViewPropertyAnimator translationXBy(float v) { return by(TX, v); }
    public ViewPropertyAnimator translationY(float v) { return to(TY, v); }
    public ViewPropertyAnimator translationYBy(float v) { return by(TY, v); }
    public ViewPropertyAnimator translationZ(float v) { return to(TZ, v); }
    public ViewPropertyAnimator scaleX(float v) { return to(SX, v); }
    public ViewPropertyAnimator scaleXBy(float v) { return by(SX, v); }
    public ViewPropertyAnimator scaleY(float v) { return to(SY, v); }
    public ViewPropertyAnimator scaleYBy(float v) { return by(SY, v); }
    public ViewPropertyAnimator rotation(float v) { return to(ROT, v); }
    public ViewPropertyAnimator rotationBy(float v) { return by(ROT, v); }
    public ViewPropertyAnimator rotationX(float v) { return to(RX, v); }
    public ViewPropertyAnimator rotationY(float v) { return to(RY, v); }
    public ViewPropertyAnimator x(float v) { return to(X, v); }
    public ViewPropertyAnimator xBy(float v) { return by(X, v); }
    public ViewPropertyAnimator y(float v) { return to(Y, v); }
    public ViewPropertyAnimator yBy(float v) { return by(Y, v); }
    public ViewPropertyAnimator z(float v) { return to(Z, v); }

    private float get(int p) {
        switch (p) {
            case ALPHA: return view.getAlpha();
            case TX: return view.getTranslationX();
            case TY: return view.getTranslationY();
            case TZ: return view.getTranslationZ();
            case SX: return view.getScaleX();
            case SY: return view.getScaleY();
            case ROT: return view.getRotation();
            case RX: return view.getRotationX();
            case RY: return view.getRotationY();
            case X: return view.getX();
            case Y: return view.getY();
            default: return view.getZ();
        }
    }
    private void set(int p, float v) {
        switch (p) {
            case ALPHA: view.setAlpha(v); break;
            case TX: view.setTranslationX(v); break;
            case TY: view.setTranslationY(v); break;
            case TZ: view.setTranslationZ(v); break;
            case SX: view.setScaleX(v); break;
            case SY: view.setScaleY(v); break;
            case ROT: view.setRotation(v); break;
            case RX: view.setRotationX(v); break;
            case RY: view.setRotationY(v); break;
            case X: view.setX(v); break;
            case Y: view.setY(v); break;
            default: view.setZ(v); break;
        }
    }

    private void schedule() {
        if (scheduled) return;
        scheduled = true;
        AttachInfo.MAIN.post(() -> { if (scheduled) start(); });
    }

    public void start() {
        scheduled = false;
        if (pending.isEmpty() && startAction == null && endAction == null) return;
        final Run run = new Run();
        for (Map.Entry<Integer, Float> e : pending.entrySet()) {
            int p = e.getKey();
            // خاصية جديدة تلغي حركتها السابقة فقط
            for (Run r : new ArrayList<>(runs)) {
                r.props.remove(p);
                if (r.props.isEmpty() && r.anim != null && r.end == null && r.listener == null) { r.anim.cancel(); runs.remove(r); }
            }
            run.props.put(p, new float[]{get(p), e.getValue()});
        }
        pending.clear();
        run.start = startAction;
        run.end = endAction;
        run.listener = listener;
        startAction = null;
        endAction = null;
        final ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(duration);
        a.setStartDelay(delay);
        if (interp != null) a.setInterpolator(interp);
        run.anim = a;
        final ValueAnimator.AnimatorUpdateListener ul = update;
        a.addUpdateListener(x -> {
            float f = (float) x.getAnimatedValue();
            for (Map.Entry<Integer, float[]> e : run.props.entrySet()) {
                float[] v = e.getValue();
                set(e.getKey(), v[0] + (v[1] - v[0]) * f);
            }
            if (ul != null) ul.onAnimationUpdate(x);
        });
        a.addListener(new Animator.AnimatorListener() {
            boolean cancelled;
            @Override public void onAnimationStart(Animator x) {
                if (run.start != null) run.start.run();
                if (run.listener != null) run.listener.onAnimationStart(x);
            }
            @Override public void onAnimationEnd(Animator x) {
                runs.remove(run);
                if (run.listener != null) run.listener.onAnimationEnd(x);
                if (!cancelled && run.end != null) run.end.run();
            }
            @Override public void onAnimationCancel(Animator x) {
                cancelled = true;
                if (run.listener != null) run.listener.onAnimationCancel(x);
            }
            @Override public void onAnimationRepeat(Animator x) {}
        });
        runs.add(run);
        a.start();
    }

    public void cancel() {
        scheduled = false;
        pending.clear();
        startAction = null;
        endAction = null;
        for (Run r : new ArrayList<>(runs)) if (r.anim != null) r.anim.cancel();
        runs.clear();
    }
}
