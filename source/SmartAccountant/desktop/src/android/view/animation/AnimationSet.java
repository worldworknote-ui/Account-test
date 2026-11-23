package android.view.animation;
public class AnimationSet extends Animation {
    final java.util.List<Animation> list = new java.util.ArrayList<>();
    public AnimationSet(boolean share) {}
    public void addAnimation(Animation a) { list.add(a); }
    @Override public void start(android.view.View v) { for (Animation a : list) { if (a.duration == 300) a.duration = duration; a.start(v); } }
}
