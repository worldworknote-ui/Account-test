package android.animation;

import java.util.ArrayList;
import java.util.List;

public final class AnimatorSet extends Animator {
    /** مجموعات تبدأ متتالية؛ كل مجموعة تعمل معًا. */
    private final List<List<Animator>> stages = new ArrayList<>();
    private long duration = -1, delay;
    private TimeInterpolator interp;
    private boolean running;
    private int stage;
    private int pending;

    public void playTogether(Animator... a) { List<Animator> l = new ArrayList<>(); for (Animator x : a) if (x != null) l.add(x); stages.add(l); }
    public void playTogether(java.util.Collection<Animator> a) { stages.add(new ArrayList<>(a)); }
    public void playSequentially(Animator... a) { for (Animator x : a) if (x != null) { List<Animator> l = new ArrayList<>(); l.add(x); stages.add(l); } }
    public void playSequentially(List<Animator> a) { for (Animator x : a) { List<Animator> l = new ArrayList<>(); l.add(x); stages.add(l); } }
    public Builder play(Animator a) {
        List<Animator> l = new ArrayList<>();
        l.add(a);
        stages.add(l);
        return new Builder(l);
    }
    public final class Builder {
        private final List<Animator> stageList;
        Builder(List<Animator> s) { stageList = s; }
        public Builder with(Animator a) { stageList.add(a); return this; }
        public Builder before(Animator a) {
            int i = stages.indexOf(stageList);
            List<Animator> l = new ArrayList<>(); l.add(a);
            stages.add(i + 1, l);
            return this;
        }
        public Builder after(Animator a) {
            int i = stages.indexOf(stageList);
            List<Animator> l = new ArrayList<>(); l.add(a);
            stages.add(i, l);
            return this;
        }
        public Builder after(long d) { return this; }
    }
    public ArrayList<Animator> getChildAnimations() { ArrayList<Animator> r = new ArrayList<>(); for (List<Animator> s : stages) r.addAll(s); return r; }
    @Override public AnimatorSet setDuration(long d) { duration = d; return this; }
    @Override public long getDuration() { return duration; }
    @Override public long getStartDelay() { return delay; }
    @Override public void setStartDelay(long d) { delay = d; }
    @Override public void setInterpolator(TimeInterpolator i) { interp = i; }
    @Override public boolean isRunning() { return running; }
    @Override public void setTarget(Object t) { for (List<Animator> s : stages) for (Animator a : s) a.setTarget(t); }

    @Override public void start() {
        if (running) cancel();
        running = true;
        notifyStart();
        stage = 0;
        if (delay > 0) android.view.AttachInfo.MAIN.postDelayed(this::runStage, delay);
        else runStage();
    }
    private void runStage() {
        if (!running) return;
        if (stage >= stages.size()) { running = false; notifyEnd(); return; }
        List<Animator> s = stages.get(stage);
        pending = s.size();
        if (pending == 0) { stage++; runStage(); return; }
        for (Animator a : s) {
            if (duration >= 0) a.setDuration(duration);
            if (interp != null) a.setInterpolator(interp);
            a.addListener(new AnimatorListenerAdapter() {
                boolean done;
                @Override public void onAnimationEnd(Animator x) {
                    if (done) return;
                    done = true;
                    x.removeListener(this);
                    if (--pending == 0 && running) { stage++; runStage(); }
                }
            });
            a.start();
        }
    }
    @Override public void cancel() {
        if (!running) return;
        running = false;
        for (List<Animator> s : stages) for (Animator a : s) a.cancel();
        notifyCancel();
        notifyEnd();
    }
    @Override public void end() {
        running = false;
        for (List<Animator> s : stages) for (Animator a : s) a.end();
        notifyEnd();
    }
    @Override public AnimatorSet clone() {
        AnimatorSet a = (AnimatorSet) super.clone();
        return a;
    }
}
