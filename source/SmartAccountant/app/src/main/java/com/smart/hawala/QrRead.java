package com.smart.hawala;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.util.EnumMap;
import java.util.Map;

/**
 * 11.6: قراءة رمز QR من صورة (لقطة شاشة أو صورة من واتساب) بمكتبة ZXing الأساسية (جافا خالصة، بلا كاميرا ولا أذونات).
 * تُستعمل لرمز الترخيص من أداة البائع (smartacc://activate?k=…). نفس الكود على الهاتف والكمبيوتر.
 */
final class QrRead {
    private QrRead() {}

    /** النص المقروء، أو null إن لم يوجد رمز QR مقروء. argb: بكسلات الصورة صفًا صفًا. */
    static String decode(int[] argb, int w, int h) {
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, java.util.Collections.singletonList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
        RGBLuminanceSource src = new RGBLuminanceSource(w, h, argb);
        // صورة عادية، ثم معكوسة الألوان (رمز فاتح على خلفية داكنة)
        for (int pass = 0; pass < 2; pass++) {
            try {
                Result r = new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(pass == 0 ? src : src.invert())), hints);
                if (r != null && r.getText() != null) return r.getText();
            } catch (Exception ignored) {
                // نجرب المحاولة التالية
            }
        }
        return null;
    }

    /** من صورة أندرويد (تُصغَّر الصور الكبيرة جدًا أولًا). */
    static String decode(android.graphics.Bitmap bm) {
        if (bm == null) return null;
        int w = bm.getWidth(), h = bm.getHeight();
        int[] px = new int[w * h];
        bm.getPixels(px, 0, w, 0, 0, w, h);
        return decode(px, w, h);
    }
}
