package com.smart.hawala;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * «المزيد ← الترخيص»: حالة النسخة واستهلاك حدود المجاني، والتفعيل دون إنترنت (طلب ← رمز)، ونقل الرخصة،
 * وعنوان خادم الترخيص الاختياري. لا شيء هنا يحجز بيانات أو يغلق التطبيق.
 */
final class LicenseScreen {
    /** 12.7: شاشة خادم الترخيص مخفية؛ الكود باقٍ. */
    static final boolean SHOW_SERVER = false;

    private final MainActivity a;

    LicenseScreen(MainActivity a) { this.a = a; }

    void show() {
        if (!a.require(Auth.Perm.LICENSE)) return;   // 12.0
        a.here(this::show);
        a.tabbedScreen("الترخيص", a.moreScreen::show, "more");
        Entitlements e = License.current;

        // ----- الحالة -----
        LinearLayout st = a.card();
        st.addView(a.text(e.edition(), 18, e.pro ? a.primaryInk : a.ink, true));
        if (e.pro) {
            st.addView(a.text("رقم الرخصة: \u2066" + e.lid + "\u2069", 14, a.ink, false));
            st.addView(a.text("التحديثات مشمولة حتى: " + e.updatesUntil(), 14, a.muted, false));
            if (!e.expires().isEmpty()) st.addView(a.text("الاشتراك ساري حتى: " + e.expires() + " (بعده يعمل بالمجانية دون أن يُقفل شيء)", 14, a.muted, false));
        } else {
            st.addView(a.text(AboutText.FREE_LINE, 15, a.ink, false));
            st.addView(a.text("حدود المجانية: " + Entitlements.FREE_OFFICES + " مكتبًا، و" + Entitlements.FREE_TX_PER_OFFICE + " حركة لكل مكتب، و"
                    + Entitlements.FREE_WA_PER_DAY + " رسالة واتساب تُقرأ تلقائيًا كل يوم (ومعها الالتقاط والتجميع والقص)، وصندوق واحد، و"
                    + Entitlements.FREE_CLIENTS + " عميلًا، ومستخدم واحد (المالك). عند بلوغ حدٍّ تتوقف الإضافة فقط: "
                    + "تبقى بياناتك كلها، وتعمل الكشوف والتقارير والتصحيح، وحذف مكتب لا تحتاجه يعيد مكانه.", 13, a.muted, false));
            st.addView(a.text(AboutText.BUY_LINE, 15, a.goldInk, true));
        }
        if (!e.note.isEmpty()) a.notice(R.drawable.ic_info, e.note, true, null, null);
        if (!License.signatureOk) {
            a.notice(R.drawable.ic_shield, "هذه النسخة من التطبيق ليست موقّعة من مطوّره. ثبّت النسخة الأصلية وانقل بياناتك إليها بنسخة احتياطية.", true, null, null);
        } else if (License.signatureUnknown) {
            // 11.5: التعذّر لا يُعدّ إثبات أصالة؛ تبقى الرخصة عاملة ويُذكر ذلك صراحة
            a.notice(R.drawable.ic_shield, "تعذّر التحقق من أن هذه النسخة موقّعة من المطوّر (خطأ في النظام أثناء الفحص). الرخصة تعمل؛ إن تكررت الرسالة فثبّت النسخة الأصلية من البائع.", true, null, null);
        }
        String risk = a.db.setting("risk_note", "");
        if (!risk.isEmpty()) a.notice(R.drawable.ic_info, risk, false, null, null);

        usage(e);

        boolean admin = a.can(Auth.Perm.SETTINGS);
        if (!admin) {
            a.hint("التفعيل ونقل الرخصة للمدير وحده.");
            return;
        }

        // ----- التفعيل أو النقل -----
        if (!e.pro) activation();
        else {
            upgrade();
            transfer();
        }

        // ----- 12.7: هوية المكتب وتأكيد الوقت (بلا خادم) -----
        a.section("هوية المكتب والوقت", null, null);
        LinearLayout g = a.group();
        String uidNow = LicenseGuard.officeUid(a.db);
        a.groupRow(g, R.drawable.ic_key, a.primaryInk, "هوية بيانات هذا المكتب", (uidNow.isEmpty() ? "لم تصل من الجهاز الرئيسي بعد (مزامنة الآن)" : uidNow)
                + "\nالرخصة تعمل على بيانات مكتبها فقط. على جهاز ينضم للمزامنة: انضم أولًا ثم اطلب التفعيل.", null, null);
        long lt = LicenseGuard.lastTrusted(a.db);
        a.groupRow(g, R.drawable.ic_clock, a.primaryInk, "آخر تأكيد للوقت من الإنترنت", lt <= 0 ? "لم يُؤكَّد بعد" : MainActivity.ago(lt),
                "تأكيد الآن", v -> a.busy("جارٍ تأكيد الوقت…", p -> LicenseGuard.fetchTime(a.db), res -> {
                    long t = (Long) res;
                    License.load(a, a.db);
                    a.toast(t > 0 ? "تأكّد الوقت ✓" : "تعذّر الاتصال الآن. لا يتوقف شيء؛ يُعاد تلقائيًا.");
                    show();
                }));
        if (!LicenseGuard.timeNote.isEmpty()) a.hint("⚠ " + LicenseGuard.timeNote);
        // 12.7: خادم الترخيص الاختياري مخفي (الكود باقٍ للاستعمال لاحقًا)
        if (SHOW_SERVER) {
        // ----- الخادم الاختياري -----
            a.section("خادم الترخيص (اختياري)", null, null);
            a.hint("يعمل التطبيق والترخيص بلا إنترنت. إن جهّزت خادمًا (المرجع مرفق مع أدوات البائع) اكتب عنوانه هنا: يُفحص كل 7 أيام "
                    + "لمعرفة الرخص الملغاة. فشل الاتصال لا يوقف شيئًا.");
            final EditText url = a.field("https://license.example.com");
            url.setText(a.db.setting("license_server", ""));
            url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            url.setTextDirection(View.TEXT_DIRECTION_LTR);
            a.body.addView(url, a.lp(-1, 52, 4));
            long last = a.db.settingLong("license_last_check", 0);
            if (last > 0) a.hint("آخر فحص: " + MainActivity.ago(last));
            a.button("حفظ العنوان", a.primary, v -> {
                String u = url.getText().toString().trim();
                if (!u.isEmpty() && !u.startsWith("https://")) {
                    a.toast("العنوان يجب أن يبدأ بـ https://");
                    return;
                }
                a.db.setSetting("license_server", u);
                a.toast(u.isEmpty() ? "أُزيل العنوان: لا اتصال بخادم الترخيص." : "حُفظ العنوان.");
            });
            if (e.pro && !a.db.setting("license_server", "").isEmpty()) {
                a.button("فحص الآن", a.accent, v -> a.busy("جارٍ الاتصال بخادم الترخيص…", p -> License.checkOnline(a, a.db), res -> {
                    a.toast(String.valueOf(res));
                    show();
                }));
            }
        }
        if (!License.signals.isEmpty()) a.hint("ملاحظات البيئة: " + License.signals);
    }

    /** الاستهلاك: المكاتب، والمكاتب الأقرب إلى سقف الحركات، والمستخدمون. */
    private void usage(Entitlements e) {
        QuotaState q;
        try {
            q = a.db.quotaNow();
        } catch (Exception ex) {
            a.hint("تعذر حساب الاستهلاك الآن: " + Ui.friendly(ex));
            return;
        }
        a.section("الاستهلاك", null, null);
        LinearLayout g = a.group();
        long mo = e.maxOffices(), mt = e.maxTxPerOffice();
        a.groupRow(g, R.drawable.ic_users, a.primaryInk, "المكاتب", mo == Entitlements.UNLIMITED ? "بلا حد" : "حذف أي مكتب يعيد مكانه فارغًا",
                mo == Entitlements.UNLIMITED ? "" + q.offices : q.offices + " من " + mo, null);
        if (mt != Entitlements.UNLIMITED) {
            List<long[]> top = new ArrayList<>();
            for (Map.Entry<Long, Long> x : q.tx.entrySet()) top.add(new long[]{x.getKey(), x.getValue()});
            Collections.sort(top, (p1, p2) -> Long.compare(p2[1], p1[1]));
            int shown = 0;
            for (long[] t : top) {
                if (shown >= 5 || t[1] < mt * 6 / 10) break;
                Model.Office o = a.db.office(t[0]);
                if (o == null) continue;
                long left = QuotaState.remaining(t[1], mt);
                a.groupRow(g, R.drawable.ic_scale, left == 0 ? a.red : a.primaryInk, Model.nz(o.name),
                        left == 0 ? "بلغ السقف: التصحيح والكشف والتقارير تعمل" : "بقي " + left + " حركة", t[1] + " من " + mt, null);
                shown++;
            }
            if (shown == 0) a.groupRow(g, R.drawable.ic_scale, a.primaryInk, "الحركات لكل مكتب", "لا مكتب قريب من السقف", "حتى " + mt, null);
        }
        long mu = e.maxUsers();
        int users = 0;
        for (Model.User u : a.db.users(false)) if (u.active) users++;
        a.groupRow(g, R.drawable.ic_user, a.primaryInk, "المستخدمون", mu == 1 ? "المالك برمز مدير" : "بأدوار وصلاحيات",
                mu == Entitlements.UNLIMITED ? (users > 0 ? users + "" : "بلا حد") : Math.max(users, 0) + " من " + mu, null);
        // 12.6: الصناديق والعملاء
        long mb = e.maxCashBoxes(), mc = e.maxClients();
        int boxes = 0, clients = 0;
        for (CashBox.Config b : a.db.cashBoxes()) if (b.on && b.active) boxes++;
        for (ClientBook.Client k : a.db.clients()) if (k.active) clients++;
        a.groupRow(g, R.drawable.ic_coins, a.primaryInk, "الصناديق", mb == Entitlements.UNLIMITED ? "بلا حد" : "المفعّلة",
                mb == Entitlements.UNLIMITED ? "" + boxes : boxes + " من " + mb, null);
        a.groupRow(g, R.drawable.ic_users, a.primaryInk, "حسابات العملاء", mc == Entitlements.UNLIMITED ? "بلا حد" : "النشطة (الأرشفة تعيد المكان)",
                mc == Entitlements.UNLIMITED ? "" + clients : clients + " من " + mc, null);
        a.groupRow(g, R.drawable.ic_doc, a.primaryInk, "التصدير PDF و Excel", e.watermark() ? "متاح مع سطر «نسخة مجانية»" : "بلا علامة، وببصمة رقم الرخصة", null, null);
        a.groupRow(g, R.drawable.ic_shield, a.primaryInk, "النسخ التلقائي", e.autoBackupEveryTx() > 0
                ? "كل " + e.autoBackupMinHours() + " ساعات أو بعد كل " + e.autoBackupEveryTx() + " حركة" : "مرة يوميًا حتى", null, null);
        long[] wa = a.db.waUsage();
        a.groupRow(g, R.drawable.ic_chat, a.primaryInk, "استخراج معلومات رسائل واتساب", wa[1] == Entitlements.UNLIMITED ? "بلا حد"
                : "يوميًا، ثم الإدخال اليدوي حتى الغد", wa[1] == Entitlements.UNLIMITED ? "" : wa[0] + " من " + wa[1], null);
        a.groupRow(g, R.drawable.ic_sparkle, a.primaryInk, AiExtract.NAME, e.ai() ? "متاح بمفتاحك" : "موقوف في المجانية — في النسخة الكاملة", null, null);
    }

    private void activation() {
        a.section("تفعيل النسخة الكاملة", null, null);
        LinearLayout c = a.card();
        c.addView(a.text("التفعيل بلا إنترنت في ثلاث خطوات", 15, a.ink, true));
        c.addView(a.text("1. احصل من البائع على «رقم الطلب».\n2. اكتبه هنا واضغط «إنشاء طلب التفعيل» ثم أرسل النص للبائع (واتساب أو بريد).\n"
                + "3. يرسل لك البائع «رمز الترخيص»: الصقه هنا واضغط «تفعيل».", 13, a.muted, false));
        c.addView(a.text("الرمز مربوط بمفتاح أمني داخل هذا الهاتف لا يمكن نسخه، فلا يعمل على هاتف آخر.", 12, a.muted, false));

        a.add(a.label("رقم الطلب"));
        final EditText order = a.field("مثال: 2026-0153");
        order.setTextDirection(View.TEXT_DIRECTION_LTR);
        a.body.addView(order, a.lp(-1, 52, 4));
        a.button("إنشاء طلب التفعيل", a.accent, v -> {
            final String o = order.getText().toString();
            a.busy("جارٍ تجهيز مفتاح الهاتف وطلب التفعيل…", p -> License.requestText(a, a.db, o), res -> showText("طلب التفعيل",
                    "الطلب طويل لأنه يحمل إثبات الهاتف الأمني. الأسهل: «إرسال كملف» (مرفق واحد لا ينقسم). أو انسخه كاملًا وأرسله في رسالة واحدة. لا يحوي أي بيانات من حساباتك.",
                    String.valueOf(res), "طلب-تفعيل-" + o.replaceAll("[^A-Za-z0-9_\\-]", "") + ".sareq"));
        });

        a.add(a.label("رمز الترخيص"));
        final EditText token = a.field("الصق الرمز الذي أرسله البائع");
        token.setTextDirection(View.TEXT_DIRECTION_LTR);
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        token.setMinLines(3);
        a.body.addView(token, a.lp(-1, -2, 4));
        // 11.5: ملف المفتاح من البائع (.sakey) بدل نسخ رمز طويل قد ينقسم في عدة رسائل
        a.button("التفعيل من ملف المفتاح (.sakey)", a.accent, v -> {
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            a.startForResult(i, MainActivity.LICENSE_FILE);
        });
        // 11.6: صورة رمز QR من البائع (لقطة شاشة أو صورة وصلت في واتساب) تُقرأ هنا مباشرة
        a.button("التفعيل من صورة رمز QR", a.accent, v -> {
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            a.startForResult(i, MainActivity.LICENSE_QR);
        });
        a.button("تفعيل", a.primary, v -> {
            final String t = token.getText().toString();
            if (t.trim().isEmpty()) {
                a.toast("الصق رمز الترخيص أولًا");
                return;
            }
            a.busy("جارٍ التحقق من الرمز…", p -> License.activate(a, a.db, t), res -> {
                Entitlements n = (Entitlements) res;
                new AlertDialog.Builder(a).setTitle("تم التفعيل ✓")
                        .setMessage(n.edition() + "\nرقم الرخصة: " + n.lid + "\nالتحديثات حتى: " + n.updatesUntil() + (n.note.isEmpty() ? "" : "\n\n" + n.note))
                        .setPositiveButton("حسنًا", null).show();
                show();
            });
        });
    }

    /** 12.6: رمز جديد من البائع لنفس الهاتف (مستخدم إضافي، مكاتب أكثر، تجديد الاشتراك…) يحل محل الحالي فورًا. */
    private void upgrade() {
        a.section("تحديث الرخصة (رمز جديد من البائع)", null, null);
        a.hint("إن طلبت مستخدمًا إضافيًا أو مكاتب أكثر أو جددت الاشتراك، يرسل لك البائع رمزًا جديدًا لهذا الهاتف نفسه: الصقه هنا أو افتح ملفه. "
                + "لا تحتاج طلب تفعيل جديدًا.");
        final EditText token = a.field("الصق الرمز الجديد");
        token.setTextDirection(View.TEXT_DIRECTION_LTR);
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        token.setMinLines(2);
        a.body.addView(token, a.lp(-1, -2, 4));
        LinearLayout r = a.row();
        r.addView(a.smallButton("من ملف (.sakey)", a.accent, v -> {
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            a.startForResult(i, MainActivity.LICENSE_FILE);
        }), a.weight(44, 1, 3));
        r.addView(a.smallButton("تحديث الرخصة", a.primary, v -> {
            final String t = token.getText().toString();
            if (t.trim().isEmpty()) {
                a.toast("الصق الرمز الجديد أولًا");
                return;
            }
            a.busy("جارٍ التحقق من الرمز…", p -> License.activate(a, a.db, t), res -> {
                Entitlements n = (Entitlements) res;
                new AlertDialog.Builder(a).setTitle("تم تحديث الرخصة ✓")
                        .setMessage(n.edition() + "\nرقم الرخصة: " + n.lid + "\nالتحديثات حتى: " + n.updatesUntil()
                                + (n.expires().isEmpty() ? "" : "\nالاشتراك حتى: " + n.expires()) + (n.note.isEmpty() ? "" : "\n\n" + n.note))
                        .setPositiveButton("حسنًا", null).show();
                show();
            });
        }), a.weight(44, 1, 3));
        a.body.addView(r, a.lp(-1, -2, 2));
    }

    private void transfer() {
        a.section("نقل الرخصة إلى هاتف آخر", null, null);
        a.hint("انقل بياناتك أولًا بنسخة احتياطية، ثم اضغط «إلغاء التفعيل على هذا الهاتف» وأرسل الإيصال للبائع مع طلب تفعيل من الهاتف الجديد. "
                + "النقل مرتان في السنة؛ وإن فُقد الهاتف القديم يُنقل بعد 7 أيام من طلبك.");
        a.button("إلغاء التفعيل على هذا الهاتف", a.red, v -> a.confirm("إلغاء التفعيل",
                "سيعود هذا الهاتف إلى النسخة المجانية فورًا (بياناتك كلها تبقى) ويُعطى إيصال تحرير موقّع ترسله للبائع لينقل الرخصة. متابعة؟",
                "إلغاء التفعيل", () -> a.busy("جارٍ إنشاء إيصال التحرير…", p -> License.release(a, a.db), res -> {
                    showText("إيصال التحرير", "أرسله للبائع مع طلب التفعيل من الهاتف الجديد.", String.valueOf(res));
                })));
    }

    /** نافذة نص قابل للنسخ والمشاركة (طلب أو إيصال). */
    /** 11.5: نص ملف المفتاح أو رابط QR: يُفعَّل مباشرة. */
    void activateText(final String text) {
        a.busy("جارٍ التحقق من الرمز…", p -> License.activate(a, a.db, text), res -> {
            Entitlements n = (Entitlements) res;
            new AlertDialog.Builder(a).setTitle("تم التفعيل ✓")
                    .setMessage(n.edition() + "\nرقم الرخصة: " + n.lid + "\nالتحديثات حتى: " + n.updatesUntil() + (n.note.isEmpty() ? "" : "\n\n" + n.note))
                    .setPositiveButton("حسنًا", null).show();
            show();
        });
    }

    /** 11.6: نتيجة اختيار صورة رمز QR: تُقرأ ثم يُفعَّل بما فيها. */
    void onQrImage(final android.net.Uri uri) {
        if (uri == null) return;
        a.busy("جارٍ قراءة رمز QR…", p -> {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (java.io.InputStream in = a.getContentResolver().openInputStream(uri)) {
                android.graphics.BitmapFactory.decodeStream(in, null, o);
            }
            int big = Math.max(o.outWidth, o.outHeight), sample = 1;
            while (big / sample > 2400) sample *= 2;
            android.graphics.BitmapFactory.Options o2 = new android.graphics.BitmapFactory.Options();
            o2.inSampleSize = sample;
            android.graphics.Bitmap bm;
            try (java.io.InputStream in = a.getContentResolver().openInputStream(uri)) {
                bm = android.graphics.BitmapFactory.decodeStream(in, null, o2);
            }
            if (bm == null) throw new java.io.IOException("تعذر فتح الصورة");
            String txt = QrRead.decode(bm);
            return txt == null ? "" : txt;
        }, res -> {
            String txt = String.valueOf(res);
            if (txt.isEmpty() || LicenseToken.extract(txt).indexOf('.') < 0) {
                a.toast("لم يُقرأ رمز ترخيص في الصورة. قرّب الصورة على الرمز أو استعمل ملف المفتاح.");
                return;
            }
            activateText(txt);
        });
    }

    /** نتيجة اختيار ملف المفتاح. */
    void onKeyFile(android.net.Uri uri) {
        if (uri == null) return;
        try (java.io.InputStream in = a.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new java.io.IOException("تعذر فتح الملف");
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n, total = 0;
            while ((n = in.read(b)) > 0 && (total += n) < 64 * 1024) bo.write(b, 0, n);
            activateText(new String(bo.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            a.error(e);
        }
    }

    private void showText(String title, String note, final String body) { showText(title, note, body, null); }

    private void showText(String title, String note, final String body, final String fileName) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(a.dp(18), a.dp(8), a.dp(18), a.dp(4));
        l.addView(a.text(note, 13, a.muted, false));
        TextView t = a.text(body, 11, a.ink, false);
        t.setTextIsSelectable(true);
        t.setTextDirection(View.TEXT_DIRECTION_LTR);
        t.setMaxLines(8);
        t.setPadding(a.dp(8), a.dp(8), a.dp(8), a.dp(8));
        t.setBackground(a.round(a.night ? Color.rgb(30, 40, 37) : Color.rgb(244, 246, 245), a.muted, 8));
        l.addView(t, new LinearLayout.LayoutParams(-1, -2));
        l.addView(a.text(body.length() + " حرفًا", 11, a.muted, false));
        if (fileName != null) l.addView(a.smallButton("إرسال كملف (مرفق واحد)", a.primary, v -> {
            try {
                java.io.File dir = new java.io.File(a.getCacheDir(), "shared");
                dir.mkdirs();
                java.io.File f = new java.io.File(dir, fileName);
                try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) {
                    o.write((body + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                a.shareFiles(java.util.Collections.singletonList(f), "application/octet-stream", title);
            } catch (Exception e) {
                a.error(e);
            }
        }), a.lp(-1, 44, 6));
        new AlertDialog.Builder(a).setTitle(title).setView(l)
                .setPositiveButton("مشاركة", (d, w) -> a.shareText(body))
                .setNeutralButton("نسخ", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText(title, body));
                    a.toast("نُسخ النص");
                })
                .setNegativeButton("إغلاق", null).show();
    }
}
