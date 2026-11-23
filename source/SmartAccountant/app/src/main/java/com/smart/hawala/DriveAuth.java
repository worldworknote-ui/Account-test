package com.smart.hawala;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import com.google.android.gms.auth.GoogleAuthUtil;
import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.tasks.Tasks;
import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * 10.2: رمز الدخول إلى Google Drive على أندرويد (خدمات Google): صلاحية «مجلد التطبيق المخفي» فقط (drive.appdata)،
 * فلا يرى التطبيق أي ملف آخر في Drive. أول مرة يظهر اختيار الحساب والموافقة؛ بعدها يعمل الرمز في الخلفية دون سؤال.
 *
 * <p>يتطلب مشروع Google Cloud فيه «Google Drive API» وعميل OAuth من نوع Android لاسم الحزمة وبصمة توقيع التطبيق
 * (الخطوات في دليل الإصدار).
 */
final class DriveAuth implements DriveSyncFolder.Tokens {
    static final int REQ = 71, REQ_PICK = 73;
    private final Context ctx;
    /** 10.14: حساب Google المحدد لهذا الجهاز (البريد)؛ فارغ = ما وافق عليه المستخدم سابقًا (سلوك 10.13 وما قبله). */
    private final String account;
    private String last;

    DriveAuth(Context ctx) { this(ctx, ""); }

    DriveAuth(Context ctx, String account) {
        this.ctx = ctx.getApplicationContext();
        this.account = account == null ? "" : account;
    }

    static AuthorizationRequest request() { return request(""); }

    /** 10.14: الصلاحية لحساب محدد صراحة، فـ«تغيير الحساب» يغيّره فعلًا (بدونه يعيد Google الحساب السابق بصمت). */
    static AuthorizationRequest request(String account) {
        AuthorizationRequest.Builder b = AuthorizationRequest.builder().setRequestedScopes(Collections.singletonList(new Scope(DriveSyncFolder.SCOPE)));
        if (account != null && !account.isEmpty()) b.setAccount(new android.accounts.Account(account, "com.google"));
        return b.build();
    }

    // 10.14: اختيار حساب Google من حسابات الهاتف
    interface Picked {
        void on(String email);
    }

    private static Picked picking;

    static void pickAccount(Activity a, String current, Picked done) {
        try {
            android.accounts.Account cur = current == null || current.isEmpty() ? null : new android.accounts.Account(current, "com.google");
            Intent i = android.accounts.AccountManager.newChooseAccountIntent(cur, null, new String[]{"com.google"}, null, null, null, null);
            picking = done;
            if (a instanceof Ui) ((Ui) a).markOwn();
            a.startActivityForResult(i, REQ_PICK);
        } catch (Exception e) {
            picking = null;
            if (a instanceof Ui) ((Ui) a).toast("تعذر فتح قائمة حسابات Google على هذا الهاتف: " + e.getMessage());
        }
    }

    static void onPicked(Activity a, int code, Intent data) {
        Picked p = picking;
        picking = null;
        if (p == null || code != Activity.RESULT_OK || data == null) return;
        String email = data.getStringExtra(android.accounts.AccountManager.KEY_ACCOUNT_NAME);
        if (email != null && !email.isEmpty()) p.on(email);
    }

    /** في الخلفية فقط (لا على الخيط الرئيسي). */
    @Override public String token(boolean refresh) throws IOException {
        try {
            if (refresh && last != null) {
                try {
                    GoogleAuthUtil.clearToken(ctx, last);
                } catch (Exception ignored) {
                    // يُطلب رمز جديد على أي حال
                }
            }
            AuthorizationResult r = Tasks.await(Identity.getAuthorizationClient(ctx).authorize(request(account)), 45, TimeUnit.SECONDS);
            if (r.hasResolution() || r.getAccessToken() == null) {
                throw new IOException("الدخول إلى Google مطلوب من جديد: افتح «المزامنة» واضغط «تسجيل الدخول بحساب Google».");
            }
            last = r.getAccessToken();
            return last;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            throw new IOException("تعذر الدخول إلى Google: " + (c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage())
                    + ". تأكد من خدمات Google والإنترنت.");
        }
    }

    interface Done {
        void ok();

        void fail(Exception e);
    }

    private static Done pending;

    /** تسجيل الدخول التفاعلي من الشاشة: يظهر اختيار الحساب والموافقة إن لزم. */
    static void connect(final Activity a, final Done done) { connect(a, "", done); }

    /** 10.14: الدخول بحساب محدد. */
    static void connect(final Activity a, String account, final Done done) {
        Identity.getAuthorizationClient(a).authorize(request(account)).addOnSuccessListener(r -> {
            if (r.hasResolution()) {
                PendingIntent pi = r.getPendingIntent();
                try {
                    pending = done;
                    if (a instanceof Ui) ((Ui) a).markOwn();
                    a.startIntentSenderForResult(pi.getIntentSender(), REQ, null, 0, 0, 0);
                } catch (Exception e) {
                    pending = null;
                    done.fail(e);
                }
            } else done.ok();
        }).addOnFailureListener(e -> done.fail(new IOException(friendly(e))));
    }

    static void onResult(Activity a, int code, Intent data) {
        Done d = pending;
        pending = null;
        if (d == null) return;
        if (code != Activity.RESULT_OK || data == null) {
            d.fail(new IOException("لم يكتمل الدخول إلى Google"));
            return;
        }
        try {
            AuthorizationResult r = Identity.getAuthorizationClient(a).getAuthorizationResultFromIntent(data);
            if (r.getAccessToken() == null) throw new IOException("لم تُمنح الصلاحية");
            d.ok();
        } catch (Exception e) {
            d.fail(new IOException(friendly(e)));
        }
    }

    static String friendly(Exception e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String hint = "\n\nربط Google Drive المباشر يحتاج أن يُجهّز مطوّر التطبيق مشروع Google Cloud مرة واحدة (Google Drive API وعميل OAuth لأندرويد ببصمة التوقيع). "
                + "إن لم يُنجز بعد فاستعمل الآن «خادمك» أو «مجلد مشترك» وانتقل لاحقًا دون فقد شيء. التفاصيل في «شرح مفصّل».";
        if (m.contains("10:") || m.contains("DEVELOPER_ERROR")) return "إعداد Google لهذا التطبيق غير مكتمل بعد." + hint + "\n(" + m + ")";
        if (m.contains("12501") || m.contains("16:") || m.toLowerCase().contains("cancel")) return "أُلغي الدخول إلى Google.";
        if (m.contains("7:") || m.toLowerCase().contains("network")) return "لا اتصال بالإنترنت. اتصل ثم أعد المحاولة.";
        return "تعذر الدخول إلى Google: " + m + hint;
    }
}
