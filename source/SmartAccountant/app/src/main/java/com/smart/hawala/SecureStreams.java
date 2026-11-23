package com.smart.hawala;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * تشفير متدفّق للنسخ الاحتياطية (صيغة SMARTBK5).
 *
 * <p>لماذا صيغة جديدة؟ الصيغة القديمة (SMARTBK4) كانت تجمع كل البيانات والصور (Base64) في الذاكرة
 * ثم تشفّرها دفعة واحدة، فتفشل أو تنهار مع كثرة الصور. هنا تُقسَّم البيانات إلى أجزاء ثابتة الحجم،
 * وكل جزء مشفّر ومُوقَّع مستقلًا بـ AES-256-GCM، فيبقى استهلاك الذاكرة صغيرًا مهما كبرت النسخة.
 *
 * <p>بنية الملف:
 * <pre>
 *  header   = "SMARTBK5" | int version | int iterations | int saltLen | salt | int prefixLen | prefix | int chunkSize
 *  verifier = int len | GCM(iv = prefix||0xFFFFFFFF, aad = header||"VERIFY", 16 zero bytes)
 *  record*  = byte flag (0 = تتبعه أجزاء, 1 = الجزء الأخير) | int len | GCM(iv = prefix||index, aad = header||index||flag, chunk)
 * </pre>
 * علامة «الجزء الأخير» داخلة في التوقيع، فأي قطع للملف أو حذف لجزء منه يُكتشف عند القراءة.
 *
 * <p>10.14 — الإصدار 2 (يُكتب فقط إن فُعّل «رمز استرداد النسخ»): الأجزاء مشفّرة بمفتاح بيانات عشوائي DK، وDK مغلّف مرتين في الرأس:
 * بمفتاح من كلمة المرور، وبمفتاح من رمز الاسترداد. فتُفتح النسخة بأيٍّ منهما. الإصدار 1 يبقى كما هو (تقرؤه الإصدارات الأقدم).
 * <pre>
 *  header v2 = header v1 | int rcSaltLen | rcSalt | int rcIter | int wpLen | GCM(pwKey, prefix||0xFFFFFFFE, headBase, DK)
 *              | int wrLen | GCM(rcKey, prefix||0xFFFFFFFD, headBase, DK)          (headBase = الرأس حتى rcIter)
 * </pre>
 * وجزء التحقق (verifier) يسمح بالتمييز بين «كلمة مرور خاطئة» و«ملف تالف».
 *
 * <p>الملف لا يستخدم أي واجهة خاصة بأندرويد حتى يمكن اختباره على الكمبيوتر.
 */
public final class SecureStreams {

    public static final byte[] MAGIC = "SMARTBK5".getBytes(StandardCharsets.US_ASCII);
    public static final int VERSION = 1;
    /** 10.14: نسخة تُفتح بكلمة المرور أو برمز الاسترداد. */
    public static final int VERSION_RC = 2;

    /** 10.14: مفتاح رمز استرداد النسخ (مشتق مرة واحدة من الرمز، ويُحفظ مشفّرًا بمفتاح الجهاز). */
    public static final class RecoveryKey {
        public final byte[] salt;
        public final int iterations;
        public final SecretKeySpec key;

        public RecoveryKey(byte[] salt, int iterations, SecretKeySpec key) {
            this.salt = salt;
            this.iterations = iterations;
            this.key = key;
        }

        /** يشتق المفتاح من الرمز (بأي شكل كُتب: بالشرطات أو بدونها، بأحرف صغيرة). */
        public static RecoveryKey derive(String code, byte[] salt, int iterations) throws GeneralSecurityException {
            return new RecoveryKey(salt, iterations, deriveKey(normalizeCode(code).toCharArray(), salt, iterations));
        }
    }

    /** صيغة رمز الاسترداد الموحّدة (24 حرفًا ورقمًا، بلا شرطات). */
    public static String normalizeCode(String s) {
        return s == null ? "" : s.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]", "").replace('O', '0').replace('I', '1');
    }

    /** هل النص المكتوب بشكل رمز استرداد (24 حرفًا ورقمًا)؟ */
    public static boolean looksLikeCode(char[] s) {
        return s != null && normalizeCode(new String(s)).length() == 24;
    }
    public static final int DEFAULT_ITERATIONS = 310000;
    public static final int MIN_ITERATIONS = 100000;
    public static final int MAX_ITERATIONS = 5000000;
    public static final int DEFAULT_CHUNK = 256 * 1024;
    private static final int MAX_CHUNK = 16 * 1024 * 1024;
    private static final int TAG_BITS = 128;
    private static final int TAG_BYTES = 16;
    private static final int SALT_BYTES = 16;
    private static final int PREFIX_BYTES = 8;
    private static final byte[] VERIFY_AAD = "VERIFY".getBytes(StandardCharsets.US_ASCII);

    private SecureStreams() {}

    /** كلمة المرور لا تطابق النسخة. */
    public static final class WrongPasswordException extends IOException {
        public WrongPasswordException() {
            super("كلمة المرور غير صحيحة. تأكد من الأحرف الكبيرة والصغيرة ولغة لوحة المفاتيح ثم أعد المحاولة.");
        }

        /** 10.14: نسخة تقبل رمز استرداد النسخ أيضًا. */
        public WrongPasswordException(boolean recoverable) {
            super(recoverable ? "كلمة المرور أو رمز استرداد النسخ غير صحيح. تأكد من الأحرف ولغة لوحة المفاتيح، أو اكتب رمز الاسترداد (24 حرفًا)."
                    : "كلمة المرور غير صحيحة. تأكد من الأحرف الكبيرة والصغيرة ولغة لوحة المفاتيح ثم أعد المحاولة.");
        }
    }

    /** الملف ليس نسخة صالحة، أو ناقص، أو عُدِّل بعد إنشائه. */
    public static final class CorruptBackupException extends IOException {
        public CorruptBackupException(String message) { super(message); }
        public CorruptBackupException(String message, Throwable cause) { super(message, cause); }
    }

    /** يشتق مفتاح AES-256 من كلمة المرور. PBEKeySpec ينسخ المصفوفة، ولا يتم تعديل مصفوفة المستدعي إطلاقًا. */
    public static SecretKeySpec deriveKey(char[] password, byte[] salt, int iterations) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
        try {
            byte[] raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            try {
                return new SecretKeySpec(raw, "AES");
            } finally {
                Arrays.fill(raw, (byte) 0);
            }
        } finally {
            spec.clearPassword();
        }
    }

    /** يقرأ أول 8 بايت لمعرفة نوع الملف دون استهلاك بقية البيانات. */
    public static boolean startsWith(byte[] head, byte[] magic) {
        if (head == null || head.length < magic.length) return false;
        for (int i = 0; i < magic.length; i++) if (head[i] != magic[i]) return false;
        return true;
    }

    private static byte[] iv(byte[] prefix, int counter) {
        byte[] iv = new byte[12];
        System.arraycopy(prefix, 0, iv, 0, 8);
        iv[8] = (byte) (counter >>> 24);
        iv[9] = (byte) (counter >>> 16);
        iv[10] = (byte) (counter >>> 8);
        iv[11] = (byte) counter;
        return iv;
    }

    private static byte[] chunkAad(byte[] header, long index, int flag) {
        byte[] aad = new byte[header.length + 9];
        System.arraycopy(header, 0, aad, 0, header.length);
        int p = header.length;
        for (int i = 7; i >= 0; i--) aad[p++] = (byte) (index >>> (8 * i));
        aad[p] = (byte) flag;
        return aad;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private static byte[] gcm(int mode, SecretKeySpec key, byte[] iv, byte[] aad, byte[] data, int off, int len)
            throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(mode, key, new GCMParameterSpec(TAG_BITS, iv));
        c.updateAAD(aad);
        return c.doFinal(data, off, len);
    }

    // ===================================================================================
    // الكتابة
    // ===================================================================================

    /**
     * يكتب نسخة مشفّرة. يجب استدعاء {@link #close()} لكتابة الجزء الأخير؛ وعند حدوث خطأ أثناء الكتابة
     * يُستدعى {@link #abort()} بدلًا منه حتى لا ينتج ملف يبدو مكتملًا.
     * لا يغلق المجرى الأصلي (حتى يتمكن المستدعي من مزامنة الملف مع التخزين ثم إغلاقه).
     */
    public static final class EncryptingOutputStream extends OutputStream {
        private final OutputStream out;
        private final SecretKeySpec key;
        private final byte[] header;
        private final byte[] prefix;
        private final byte[] buf;
        private int pos;
        private int index;
        private boolean closed;
        private long plainBytes;

        public EncryptingOutputStream(OutputStream out, char[] password) throws IOException {
            this(out, password, DEFAULT_ITERATIONS, DEFAULT_CHUNK);
        }

        public EncryptingOutputStream(OutputStream out, char[] password, int iterations, int chunkSize) throws IOException {
            this(out, password, iterations, chunkSize, null);
        }

        /** 10.14: rk ليس null = نسخة تُفتح بكلمة المرور أو برمز الاسترداد (الإصدار 2). */
        public EncryptingOutputStream(OutputStream out, char[] password, RecoveryKey rk) throws IOException {
            this(out, password, DEFAULT_ITERATIONS, DEFAULT_CHUNK, rk);
        }

        public EncryptingOutputStream(OutputStream out, char[] password, int iterations, int chunkSize, RecoveryKey rk) throws IOException {
            if (password == null || password.length == 0) throw new IOException("كلمة المرور فارغة");
            if (chunkSize < 1024 || chunkSize > MAX_CHUNK) throw new IllegalArgumentException("chunk");
            this.out = out;
            SecureRandom rnd = new SecureRandom();
            byte[] salt = new byte[SALT_BYTES];
            rnd.nextBytes(salt);
            prefix = new byte[PREFIX_BYTES];
            rnd.nextBytes(prefix);
            ByteArrayOutputStream h = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(h);
            d.write(MAGIC);
            d.writeInt(rk == null ? VERSION : VERSION_RC);
            d.writeInt(iterations);
            d.writeInt(salt.length);
            d.write(salt);
            d.writeInt(prefix.length);
            d.write(prefix);
            d.writeInt(chunkSize);
            try {
                if (rk == null) {
                    d.flush();
                    header = h.toByteArray();
                    key = deriveKey(password, salt, iterations);
                } else {
                    // الإصدار 2: مفتاح بيانات عشوائي مغلّف بكلمة المرور وبرمز الاسترداد
                    d.writeInt(rk.salt.length);
                    d.write(rk.salt);
                    d.writeInt(rk.iterations);
                    d.flush();
                    byte[] base = h.toByteArray();
                    byte[] dk = new byte[32];
                    rnd.nextBytes(dk);
                    byte[] wp = gcm(Cipher.ENCRYPT_MODE, deriveKey(password, salt, iterations), iv(prefix, 0xFFFFFFFE), base, dk, 0, dk.length);
                    byte[] wr = gcm(Cipher.ENCRYPT_MODE, rk.key, iv(prefix, 0xFFFFFFFD), base, dk, 0, dk.length);
                    d.writeInt(wp.length);
                    d.write(wp);
                    d.writeInt(wr.length);
                    d.write(wr);
                    d.flush();
                    header = h.toByteArray();
                    key = new SecretKeySpec(dk, "AES");
                    Arrays.fill(dk, (byte) 0);
                }
                byte[] v = gcm(Cipher.ENCRYPT_MODE, key, iv(prefix, 0xFFFFFFFF), concat(header, VERIFY_AAD), new byte[16], 0, 16);
                out.write(header);
                writeInt(out, v.length);
                out.write(v);
            } catch (GeneralSecurityException e) {
                throw new IOException("تعذر تجهيز التشفير على هذا الهاتف: " + e.getMessage(), e);
            } catch (IOException e) {
                throw e;
            }
            buf = new byte[chunkSize];
        }

        public long plainBytes() { return plainBytes; }

        @Override public void write(int b) throws IOException {
            if (closed) throw new IOException("closed");
            if (pos == buf.length) emit(false);
            buf[pos++] = (byte) b;
            plainBytes++;
        }

        @Override public void write(byte[] b, int off, int len) throws IOException {
            if (closed) throw new IOException("closed");
            while (len > 0) {
                if (pos == buf.length) emit(false);
                int n = Math.min(len, buf.length - pos);
                System.arraycopy(b, off, buf, pos, n);
                pos += n;
                off += n;
                len -= n;
                plainBytes += n;
            }
        }

        private void emit(boolean last) throws IOException {
            int flag = last ? 1 : 0;
            try {
                byte[] ct = gcm(Cipher.ENCRYPT_MODE, key, iv(prefix, index), chunkAad(header, index, flag), buf, 0, pos);
                out.write(flag);
                writeInt(out, ct.length);
                out.write(ct);
            } catch (GeneralSecurityException e) {
                throw new IOException("فشل التشفير: " + e.getMessage(), e);
            }
            index++;
            pos = 0;
        }

        @Override public void flush() throws IOException { out.flush(); }

        /** يكتب الجزء الأخير ويفرغ المخزن المؤقت، دون إغلاق المجرى الأصلي. */
        @Override public void close() throws IOException {
            if (closed) return;
            emit(true);
            Arrays.fill(buf, (byte) 0);
            out.flush();
            closed = true;
        }

        /** إنهاء بدون كتابة الجزء الأخير: الملف الناتج سيُرفض عند القراءة. */
        public void abort() {
            closed = true;
            Arrays.fill(buf, (byte) 0);
        }
    }

    // ===================================================================================
    // القراءة
    // ===================================================================================

    public static final class DecryptingInputStream extends InputStream {
        private final DataInputStream in;
        private final SecretKeySpec key;
        private final byte[] header;
        private final byte[] prefix;
        private final int chunkSize;
        private byte[] buf = new byte[0];
        private int pos;
        private int index;
        private boolean finished;

        public DecryptingInputStream(InputStream source, char[] password) throws IOException {
            in = new DataInputStream(source);
            try {
                byte[] magic = new byte[MAGIC.length];
                in.readFully(magic);
                if (!Arrays.equals(magic, MAGIC)) throw new CorruptBackupException("هذا الملف ليس نسخة احتياطية من المحاسب الذكي");
                int version = in.readInt();
                if (version != VERSION && version != VERSION_RC) throw new CorruptBackupException("إصدار النسخة غير مدعوم في هذا الإصدار من التطبيق");
                int iterations = in.readInt();
                if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) throw new CorruptBackupException("معلمات حماية النسخة غير صالحة");
                int saltLen = in.readInt();
                if (saltLen != SALT_BYTES) throw new CorruptBackupException("بنية النسخة غير صالحة");
                byte[] salt = new byte[saltLen];
                in.readFully(salt);
                int prefixLen = in.readInt();
                if (prefixLen != PREFIX_BYTES) throw new CorruptBackupException("بنية النسخة غير صالحة");
                prefix = new byte[prefixLen];
                in.readFully(prefix);
                chunkSize = in.readInt();
                if (chunkSize < 1024 || chunkSize > MAX_CHUNK) throw new CorruptBackupException("بنية النسخة غير صالحة");

                ByteArrayOutputStream h = new ByteArrayOutputStream();
                DataOutputStream d = new DataOutputStream(h);
                d.write(MAGIC);
                d.writeInt(version);
                d.writeInt(iterations);
                d.writeInt(saltLen);
                d.write(salt);
                d.writeInt(prefixLen);
                d.write(prefix);
                d.writeInt(chunkSize);
                SecretKeySpec opened = null;
                if (version == VERSION_RC) {
                    // 10.14: مفتاح البيانات مغلّف بكلمة المرور وبرمز الاسترداد: يُفتح بأيهما
                    int rsl = in.readInt();
                    if (rsl != SALT_BYTES) throw new CorruptBackupException("بنية النسخة غير صالحة");
                    byte[] rsalt = new byte[rsl];
                    in.readFully(rsalt);
                    int riter = in.readInt();
                    if (riter < MIN_ITERATIONS || riter > MAX_ITERATIONS) throw new CorruptBackupException("معلمات حماية النسخة غير صالحة");
                    d.writeInt(rsl);
                    d.write(rsalt);
                    d.writeInt(riter);
                    d.flush();
                    byte[] base = h.toByteArray();
                    int wpl = in.readInt();
                    if (wpl != 32 + TAG_BYTES) throw new CorruptBackupException("بنية النسخة غير صالحة");
                    byte[] wp = new byte[wpl];
                    in.readFully(wp);
                    int wrl = in.readInt();
                    if (wrl != 32 + TAG_BYTES) throw new CorruptBackupException("بنية النسخة غير صالحة");
                    byte[] wr = new byte[wrl];
                    in.readFully(wr);
                    d.writeInt(wpl);
                    d.write(wp);
                    d.writeInt(wrl);
                    d.write(wr);
                    byte[] dk = null;
                    try {
                        dk = gcm(Cipher.DECRYPT_MODE, deriveKey(password, salt, iterations), iv(prefix, 0xFFFFFFFE), base, wp, 0, wp.length);
                    } catch (AEADBadTagException bad) {
                        if (looksLikeCode(password)) {
                            try {
                                dk = gcm(Cipher.DECRYPT_MODE, RecoveryKey.derive(new String(password), rsalt, riter).key, iv(prefix, 0xFFFFFFFD), base, wr, 0, wr.length);
                            } catch (AEADBadTagException bad2) {
                                dk = null;
                            } catch (GeneralSecurityException e2) {
                                throw new CorruptBackupException("تعذر فك النسخة: " + e2.getMessage(), e2);
                            }
                        }
                    } catch (GeneralSecurityException e) {
                        throw new CorruptBackupException("تعذر فك النسخة: " + e.getMessage(), e);
                    }
                    if (dk == null) throw new WrongPasswordException(true);
                    opened = new SecretKeySpec(dk, "AES");
                    Arrays.fill(dk, (byte) 0);
                }
                d.flush();
                header = h.toByteArray();

                int vlen = in.readInt();
                if (vlen != 16 + TAG_BYTES) throw new CorruptBackupException("بنية النسخة غير صالحة");
                byte[] v = new byte[vlen];
                in.readFully(v);
                try {
                    key = opened != null ? opened : deriveKey(password, salt, iterations);
                } catch (GeneralSecurityException e) {
                    throw new IOException("تعذر تجهيز فك التشفير على هذا الهاتف: " + e.getMessage(), e);
                }
                byte[] check;
                try {
                    check = gcm(Cipher.DECRYPT_MODE, key, iv(prefix, 0xFFFFFFFF), concat(header, VERIFY_AAD), v, 0, v.length);
                } catch (AEADBadTagException bad) {
                    throw new WrongPasswordException();
                } catch (GeneralSecurityException e) {
                    throw new CorruptBackupException("تعذر فك النسخة: " + e.getMessage(), e);
                }
                for (byte b : check) if (b != 0) throw new WrongPasswordException();
            } catch (EOFException eof) {
                throw new CorruptBackupException("الملف ناقص أو فارغ، وليس نسخة احتياطية مكتملة");
            }
        }

        private boolean next() throws IOException {
            if (finished) return false;
            int flag = in.read();
            if (flag == -1) throw new CorruptBackupException("الملف ناقص: انتهى قبل نهاية النسخة (ربما لم يكتمل النقل أو الحفظ)");
            if (flag != 0 && flag != 1) throw new CorruptBackupException("الملف تالف (علامة غير صالحة في الجزء " + (index + 1) + ")");
            int len;
            byte[] ct;
            try {
                len = in.readInt();
                if (len < TAG_BYTES || len > chunkSize + TAG_BYTES) throw new CorruptBackupException("الملف تالف (طول غير صالح في الجزء " + (index + 1) + ")");
                ct = new byte[len];
                in.readFully(ct);
            } catch (EOFException eof) {
                throw new CorruptBackupException("الملف ناقص: انتهى في منتصف الجزء " + (index + 1));
            }
            try {
                buf = gcm(Cipher.DECRYPT_MODE, key, iv(prefix, index), chunkAad(header, index, flag), ct, 0, ct.length);
            } catch (AEADBadTagException bad) {
                throw new CorruptBackupException("الملف تالف أو تم تعديله بعد إنشائه (الجزء " + (index + 1) + ")");
            } catch (GeneralSecurityException e) {
                throw new CorruptBackupException("تعذر فك الجزء " + (index + 1) + ": " + e.getMessage(), e);
            }
            pos = 0;
            index++;
            if (flag == 1) {
                finished = true;
                if (in.read() != -1) throw new CorruptBackupException("توجد بيانات زائدة بعد نهاية النسخة؛ الملف معدّل");
            }
            return true;
        }

        @Override public int read() throws IOException {
            while (pos >= buf.length) {
                if (!next()) return -1;
            }
            return buf[pos++] & 0xFF;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            while (pos >= buf.length) {
                if (!next()) return -1;
            }
            int n = Math.min(len, buf.length - pos);
            System.arraycopy(buf, pos, b, off, n);
            pos += n;
            return n;
        }

        /** هل وصلت القراءة إلى الجزء الأخير الموقّع؟ */
        public boolean isComplete() { return finished && pos >= buf.length; }

        @Override public void close() throws IOException { in.close(); }
    }

    private static void writeInt(OutputStream o, int v) throws IOException {
        o.write(v >>> 24);
        o.write(v >>> 16);
        o.write(v >>> 8);
        o.write(v);
    }
}
