package com.smart.hawala;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/** تصغير صور الإيصالات وتصحيح اتجاهها قبل حفظها، لتقليل حجم البيانات والنسخ الاحتياطية. */
public final class ImageUtil {
    private static final int MAX_EDGE = 1600;

    private ImageUtil() {}

    /** يفك الصورة بحجم مصغّر (لا تتجاوز حافتها الطويلة maxEdge) لتوفير الذاكرة عند العرض والتصدير. */
    public static Bitmap decodeSampled(byte[] raw, int maxEdge) {
        if (raw == null || raw.length == 0) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(raw, 0, raw.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(raw, 0, raw.length, opts);
        } catch (Throwable t) {
            return null;
        }
    }

    /** أبعاد الصورة دون فكها. */
    public static int[] size(byte[] raw) {
        if (raw == null) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(raw, 0, raw.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        return new int[]{bounds.outWidth, bounds.outHeight};
    }

    public static byte[] shrink(byte[] raw) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(raw, 0, raw.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return raw;

            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap decoded = BitmapFactory.decodeByteArray(raw, 0, raw.length, opts);
            if (decoded == null) return raw;

            int rotation = 0;
            try {
                ExifInterface exif = new ExifInterface(new ByteArrayInputStream(raw));
                int o = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                if (o == ExifInterface.ORIENTATION_ROTATE_90) rotation = 90;
                else if (o == ExifInterface.ORIENTATION_ROTATE_180) rotation = 180;
                else if (o == ExifInterface.ORIENTATION_ROTATE_270) rotation = 270;
            } catch (Throwable ignored) {
                // بدون معلومات اتجاه
            }

            int w = decoded.getWidth();
            int h = decoded.getHeight();
            float scale = Math.min(1f, (float) MAX_EDGE / Math.max(w, h));
            Bitmap out = decoded;
            if (scale < 1f || rotation != 0) {
                Matrix m = new Matrix();
                if (scale < 1f) m.postScale(scale, scale);
                if (rotation != 0) m.postRotate(rotation);
                out = Bitmap.createBitmap(decoded, 0, 0, w, h, m, true);
            }
            ByteArrayOutputStream z = new ByteArrayOutputStream();
            out.compress(Bitmap.CompressFormat.JPEG, 85, z);
            if (out != decoded) out.recycle();
            decoded.recycle();
            byte[] res = z.toByteArray();
            return res.length > 0 ? res : raw;
        } catch (Throwable t) {
            return raw;
        }
    }

    /** هل البايتات صورة يمكن فكها؟ */
    public static boolean isImage(byte[] raw) {
        return size(raw) != null;
    }
}
