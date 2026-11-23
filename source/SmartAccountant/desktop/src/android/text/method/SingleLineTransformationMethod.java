package android.text.method;
public class SingleLineTransformationMethod implements TransformationMethod {
    private static final SingleLineTransformationMethod I = new SingleLineTransformationMethod();
    public static SingleLineTransformationMethod getInstance() { return I; }
    @Override public CharSequence getTransformation(CharSequence s, android.view.View v) { return s.toString().replace('\n', ' '); }
}
