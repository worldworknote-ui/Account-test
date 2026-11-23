package android.text.method;
public class PasswordTransformationMethod implements TransformationMethod {
    private static final PasswordTransformationMethod I = new PasswordTransformationMethod();
    public static PasswordTransformationMethod getInstance() { return I; }
    @Override public CharSequence getTransformation(CharSequence s, android.view.View v) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) b.append('•');
        return b;
    }
}
