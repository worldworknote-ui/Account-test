package android.text.method;
public class HideReturnsTransformationMethod implements TransformationMethod {
    private static final HideReturnsTransformationMethod I = new HideReturnsTransformationMethod();
    public static HideReturnsTransformationMethod getInstance() { return I; }
    @Override public CharSequence getTransformation(CharSequence s, android.view.View v) { return s; }
}
