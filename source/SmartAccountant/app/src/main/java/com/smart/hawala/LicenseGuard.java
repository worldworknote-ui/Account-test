package com.smart.hawala;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 12.7: حماية الرخصة دون خادم — مشتركة بين الهاتف والكمبيوتر (التوقيع بمفتاح الجهاز من License في كل منصة).
 * <ul>
 *   <li><b>هوية المكتب</b>: رقم عشوائي يُنشأ مرة واحدة ويتزامن مع بيانات المكتب. الرمز الصادر بحقل «of» يعمل على بيانات
 *   هذا المكتب وحده؛ على بيانات مكتب آخر (انضمام لمزامنة أخرى، قاعدة مستقلة، نسخة مكتب آخر) يعمل الجهاز بالمجاني فورًا،
 *   وتُسجَّل المحاولة في سجل مختوم بمفتاح الجهاز يصل إلى البائع مع طلب التجديد أو الجهاز الإضافي.</li>
 *   <li><b>الوقت الموثوق</b>: تاريخ من الإنترنت (رأس Date لموقع كبير عبر HTTPS؛ يكفي واحد). بين مرة وأخرى يُكمَّل بعدّاد
 *   الجهاز منذ الإقلاع (لا تغيّره الساعة)، ولا يرجع التاريخ عن أكبر يوم رآه التطبيق ولا عن أوقات أجهزة المزامنة.
 *   حقل «tc» في الرمز = كل كم يوم يلزم تأكيد الوقت من الإنترنت، و«tg» = المهلة بعدها قبل حدود المجاني (0 = لا يلزم).</li>
 * </ul>
 * لا شيء هنا يحذف بيانات أو يحجزها: الفشل = حدود المجاني للإضافة فقط.
 */
public final class LicenseGuard {
    private LicenseGuard() {}

    public static final String K_OFFICE = "of", K_CHECK_DAYS = "tc", K_GRACE_DAYS = "tg", K_DEVICES = "md";
    static final String OFFICE_UID = "office_uid";
    /** إعداد خاص بالجهاز: آخر وقت موثوق من الإنترنت ووقت العدّاد عنده. */
    static final String TIME = "license_time";
    static final String[] TIME_SOURCES = {"https://www.google.com", "https://www.cloudflare.com", "https://www.microsoft.com"};
    private static final String MISUSE_FILE = "license-misuse.json";
    static final long DAY = 86_400_000L;

    /** للاختبار: مصادر بديلة (خادم محلي وهمي). */
    static volatile String[] testSources;

    // ===================================================================================
    // هوية المكتب
    // ===================================================================================

    /** هوية المكتب لهذه البيانات (تُنشأ مرة واحدة وتتزامن مع بقية الإعدادات). */
    public static String officeUid(Db db) {
        String v = db.setting(OFFICE_UID, "").trim();
        if (!v.isEmpty()) return v;
        // جهاز منضم لا يُنشئ هوية بنفسه: تصله من الجهاز الرئيسي بالمزامنة (فلا تتعارض هويتان لمكتب واحد)
        try {
            if (SyncEngine.enabled(db) && !SyncStore.founder(db.getReadableDatabase())) return "";
        } catch (Throwable ignored) {
            // بلا مزامنة
        }
        byte[] r = new byte[9];
        new SecureRandom().nextBytes(r);
        v = "OF-" + BackupFormat.hex(r).toUpperCase(Locale.ROOT);
        db.setSettingQuiet(OFFICE_UID, v);
        return v;
    }

    /** الهوية الحالية دون إنشاء (فارغ = لم تُنشأ بعد). */
    public static String currentUid(Db db) { return db.setting(OFFICE_UID, "").trim(); }

    /** 12.7: «مسح كل البيانات» يبدأ مكتبًا جديدًا: هوية جديدة (رخصة المكتب القديم لا تنتقل للبيانات الجديدة). */
    public static void rotateOffice(Db db) {
        db.deleteSetting(OFFICE_UID);
        officeUid(db);
    }

    // ===================================================================================
    // القرار
    // ===================================================================================

    /** حالة تأكيد الوقت للعرض: 0 = لا يلزم/سليم، 1 = حان التأكيد (مهلة)، 2 = انتهت المهلة. */
    public static volatile int timeState;
    public static volatile String timeNote = "";

    /** ما يحق للجهاز من رمز صالح: الاشتراك واليوم الموثوق، ثم المكتب، ثم مدة تأكيد الوقت. */
    /** آخر رمز صالح على هذا الجهاز (ولو صار الجهاز بالمجاني لسبب هنا): لمعرفة هل يلزم جلب الوقت. */
    static volatile LicenseToken.Grant lastGrant;

    public static Entitlements decide(Context ctx, Db db, LicenseToken.Grant g) {
        lastGrant = g;
        long now = trustedNow(db);
        long today = Long.parseLong(dayOf(now));
        long floor = db.licenseDay();
        if (today > floor) db.setSettingQuiet("license_day", String.valueOf(today));   // اليوم الموثوق لا يرجع بعدها
        Entitlements e = Entitlements.of(g, BuildInfo.BUILD_DATE, Math.max(today, floor));
        timeState = 0;
        timeNote = "";
        if (!e.pro) return e;
        String of = g.get(K_OFFICE).trim();
        if (!of.isEmpty()) {
            String mine = officeUid(db);
            if (mine.isEmpty())
                return Entitlements.free("لم تصل «هوية المكتب» من الجهاز الرئيسي إلى هذا الجهاز بعد. اضغط «مزامنة الآن»؛ تعود الرخصة فور وصولها.");
            if (!of.equals(mine)) {
                recordMisuse(ctx, g.lid(), of, mine);
                return Entitlements.free("هذه الرخصة (" + g.lid() + ") صادرة لمكتب آخر (" + of + ")، والبيانات المفتوحة على هذا الجهاز لمكتب «" + mine
                        + "». تعمل الرخصة على بيانات مكتبها فقط؛ هنا حدود المجاني. إن انضممت إلى مزامنة مكتبك بعد التفعيل فاطلب من البائع رمزًا جديدًا.");
            }
        }
        long tc = g.num(K_CHECK_DAYS), tg = g.num(K_GRACE_DAYS);
        if (tc > 0) {
            long last = lastTrusted(db);
            long since = last <= 0 ? Long.MAX_VALUE : now - last;
            if (since > (tc + Math.max(0, tg)) * DAY) {
                timeState = 2;
                timeNote = "انتهت مهلة تأكيد الرخصة: اتصل بالإنترنت مرة واحدة (يكفي فتح التطبيق والإنترنت متصل) لتعود الرخصة فورًا. بياناتك كلها كما هي.";
                return Entitlements.free(timeNote);
            }
            if (since > tc * DAY) {
                timeState = 1;
                long left = Math.max(0, (tc + Math.max(0, tg)) * DAY - since) / DAY;
                timeNote = "اتصل بالإنترنت لتأكيد الرخصة: بقي " + left + " يومًا قبل العمل بحدود المجاني.";
            }
        }
        return e;
    }

    // ===================================================================================
    // الوقت الموثوق
    // ===================================================================================

    /** آخر وقت موثوق (ملّي ثانية) أو 0. */
    public static long lastTrusted(Db db) {
        String[] p = db.setting(TIME, "").split("\\|");
        try {
            return p.length >= 2 ? Long.parseLong(p[0]) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * أفضل تقدير للوقت الآن: الأكبر من ساعة الجهاز، والوقت الموثوق + ما مضى بعدّاد الإقلاع (إن لم يُعد تشغيل الجهاز)،
     * وأوقات أجهزة المزامنة (بحد 3 أيام فوق تقديرنا حتى لا تُنهي ساعة جهاز آخر خاطئة رخصة هذا الجهاز مبكرًا).
     */
    public static long trustedNow(Db db) {
        long dev = Clock.now();
        long best = dev;
        String[] p = db.setting(TIME, "").split("\\|");
        if (p.length >= 2) {
            try {
                long t = Long.parseLong(p[0]), at = Long.parseLong(p[1]), el = android.os.SystemClock.elapsedRealtime();
                long est = el >= at ? t + (el - at) : t;   // أُعيد تشغيل الجهاز: لا يقل عن آخر وقت موثوق
                best = Math.max(best, est);
            } catch (Exception ignored) {
                // قيمة تالفة
            }
        }
        try {
            String h = SyncStore.meta(db.getReadableDatabase(), "hlc");
            if (h != null && h.length() >= 16) {
                long w = Long.parseLong(h.substring(0, 16));
                if (w > best && w - best < 3 * DAY) best = w;
            }
        } catch (Throwable ignored) {
            // بلا مزامنة
        }
        return best;
    }

    static String dayOf(long t) {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd", Locale.US);
        return f.format(new Date(t));
    }

    /** هل حان جلب الوقت؟ (مرة كل 12 ساعة على الأكثر، ولا يلزم إلا برخصة فيها tc). */
    public static boolean timeDue(Db db, LicenseToken.Grant g) {
        if (g == null || g.num(K_CHECK_DAYS) <= 0) return false;
        return Clock.now() - db.settingLong("license_time_try", 0) > 12 * 3600_000L || lastTrusted(db) <= 0;
    }

    /** يجلب التاريخ من الإنترنت (يكفي مصدر واحد). يرجع الوقت أو 0. لا يرمي. على خيط خلفية فقط. */
    public static long fetchTime(Db db) {
        db.setSettingQuiet("license_time_try", "" + Clock.now());
        String[] src = testSources != null ? testSources : TIME_SOURCES;
        for (String u : src) {
            HttpURLConnection c = null;
            try {
                long el0 = android.os.SystemClock.elapsedRealtime();
                c = (HttpURLConnection) new URL(u).openConnection();
                c.setRequestMethod("HEAD");
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setUseCaches(false);
                c.getResponseCode();
                long t = c.getHeaderFieldDate("Date", 0);
                if (t <= 0) continue;
                long el1 = android.os.SystemClock.elapsedRealtime();
                long mid = el0 + (el1 - el0) / 2;
                long prev = lastTrusted(db);
                if (prev > 0 && t + 600_000L < prev) continue;   // وقت أقدم من آخر وقت موثوق: يُرفض المصدر
                db.setSettingQuiet(TIME, t + "|" + mid + "|" + u);
                return t;
            } catch (Throwable ignored) {
                // المصدر التالي
            } finally {
                if (c != null) c.disconnect();
            }
        }
        return 0;
    }

    private static volatile boolean fetching;

    /** عند الفتح: إن لزم تأكيد الوقت يُجلب في الخلفية ثم يُعاد حساب الرخصة (لا ينتظره شيء). */
    public static void refreshAsync(final Context ctx, final Db db) {
        final LicenseToken.Grant g = lastGrant;
        if (fetching || db == null || !timeDue(db, g)) return;
        fetching = true;
        new Thread(() -> {
            try {
                if (fetchTime(db) > 0) License.load(ctx, db);
            } catch (Throwable ignored) {
                // يُعاد لاحقًا
            } finally {
                fetching = false;
            }
        }, "license-time").start();
    }

    // ===================================================================================
    // سجل محاولات الاستخدام لمكتب آخر (مختوم بمفتاح الجهاز)
    // ===================================================================================

    static File misuseFile(Context ctx) { return new File(ctx.getFilesDir(), MISUSE_FILE); }

    /** السجل كما هو: {"log":[{at,lid,of,saw}], "sig":"…"} أو فارغ. */
    static JSONObject misuse(Context ctx) {
        try {
            return new JSONObject(new String(Db.readAll(misuseFile(ctx)), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** نص التوقيع: الأسطر بترتيبها (لا تُحذف ولا تُعدَّل دون أن يكسر التوقيع). */
    static String misuseBody(JSONArray log) {
        StringBuilder b = new StringBuilder("SA-MISUSE-1");
        for (int i = 0; i < log.length(); i++) {
            JSONObject x = log.optJSONObject(i);
            if (x == null) continue;
            b.append('|').append(x.optLong("at")).append(',').append(x.optString("lid")).append(',').append(x.optString("of")).append(',').append(x.optString("saw"));
        }
        return b.toString();
    }

    /** يسجّل محاولة (مرة لكل مكتب آخر في اليوم). */
    static synchronized void recordMisuse(Context ctx, String lid, String of, String saw) {
        try {
            JSONObject j = misuse(ctx);
            JSONArray log = j.optJSONArray("log");
            if (log == null) log = new JSONArray();
            String today = dayOf(Clock.now());
            for (int i = 0; i < log.length(); i++) {
                JSONObject x = log.getJSONObject(i);
                if (saw.equals(x.optString("saw")) && today.equals(dayOf(x.optLong("at")))) return;
            }
            if (log.length() >= 200) return;
            JSONObject e = new JSONObject();
            e.put("at", Clock.now());
            e.put("lid", lid);
            e.put("of", of);
            e.put("saw", saw);
            log.put(e);
            JSONObject out = new JSONObject();
            out.put("log", log);
            out.put("sig", License.signData(misuseBody(log).getBytes(StandardCharsets.UTF_8)));
            File f = misuseFile(ctx), tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream o = new FileOutputStream(tmp)) {
                o.write(out.toString().getBytes(StandardCharsets.UTF_8));
            }
            tmp.renameTo(f);
            try {
                SmartApp.db.audit("LICENSE_OTHER_OFFICE", "license", 0, lid + " → " + saw);
            } catch (Throwable ignored) {
                // السجل المختوم يكفي
            }
        } catch (Throwable ignored) {
            // لا يمنع العمل
        }
    }

    /** ما يُضاف إلى طلب التفعيل: هوية المكتب، وسجل المحاولات وتوقيعه (تفحصه أداة البائع بالمفتاح العام في الطلب). */
    static void addToRequest(Context ctx, Db db, JSONObject req) throws Exception {
        String of = officeUid(db);
        if (of.isEmpty()) throw new IllegalStateException("لم تصل «هوية المكتب» من الجهاز الرئيسي بعد. اضغط «مزامنة الآن» ثم أنشئ طلب التفعيل.");
        req.put("of", of);
        JSONObject m = misuse(ctx);
        JSONArray log = m.optJSONArray("log");
        if (log != null && log.length() > 0) {
            req.put("mis", log);
            req.put("misSig", m.optString("sig"));
        }
    }
}
