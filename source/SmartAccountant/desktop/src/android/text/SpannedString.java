package android.text;
public final class SpannedString extends SpannableString { public SpannedString(CharSequence s) { super(s); } public static SpannedString valueOf(CharSequence s) { return new SpannedString(s); } }
