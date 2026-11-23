package android.animation;

import java.util.ArrayList;
import java.util.List;

/** حركة حسب حالة العنصر (الضغط مثلًا). */
public class StateListAnimator {
    private final List<int[]> specs = new ArrayList<>();
    private final List<Animator> anims = new ArrayList<>();
    private Object target;
    private Animator current;
    private int currentIndex = -2;

    public void addState(int[] spec, Animator a) { specs.add(spec); anims.add(a); if (target != null) a.setTarget(target); }
    public void setTarget(Object t) { target = t; for (Animator a : anims) a.setTarget(t); }
    public void jumpToCurrentState() { if (current != null) current.end(); }
    public void setState(int[] st) {
        int idx = -1;
        for (int i = 0; i < specs.size(); i++) {
            boolean ok = true;
            for (int s : specs.get(i)) {
                boolean has = false;
                for (int x : st) if (x == Math.abs(s)) { has = true; break; }
                if ((s > 0 && !has) || (s < 0 && has)) { ok = false; break; }
            }
            if (ok) { idx = i; break; }
        }
        if (idx == currentIndex) return;
        if (current != null) current.cancel();
        currentIndex = idx;
        current = idx < 0 ? null : anims.get(idx);
        if (current != null) {
            if (target != null) current.setTarget(target);
            current.start();
        }
    }
    @Override public StateListAnimator clone() { StateListAnimator s = new StateListAnimator(); for (int i = 0; i < specs.size(); i++) s.addState(specs.get(i), anims.get(i).clone()); return s; }
}
