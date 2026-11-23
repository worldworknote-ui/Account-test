package android.animation;
public class FloatEvaluator implements TypeEvaluator<Number> {
    @Override public Float evaluate(float f, Number s, Number e) { return s.floatValue() + f * (e.floatValue() - s.floatValue()); }
}
