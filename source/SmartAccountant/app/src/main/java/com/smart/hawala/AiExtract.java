package com.smart.hawala;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * فهم رسالة الحوالة بالذكاء الاصطناعي (اختياري ومطفأ افتراضيًا).
 * يُرسل نص الرسالة فقط (مع رموز عملات المكتب) إلى الخدمة التي اختارها المستخدم وبمفتاحه هو؛ لا أرصدة ولا بيانات مكاتب.
 * يدعم Claude (Anthropic) وأي خدمة متوافقة مع OpenAI. منطق بحت بلا أندرويد حتى يُختبر على الكمبيوتر.
 */
public final class AiExtract {
    private AiExtract() {}

    public static final String CLAUDE = "claude", OPENAI = "openai", GEMINI = "gemini", CUSTOM = "custom";
    public static final String CLAUDE_URL = "https://api.anthropic.com/v1/messages";
    public static final String CLAUDE_MODEL = "claude-haiku-5-5";
    public static final String OPENAI_URL = "https://api.openai.com/v1";
    /** 12.7: Gemini بصيغة OpenAI نفسها. */
    public static final String GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/openai";

    /** 12.7: الاسم كما يراه المستخدم (لا يخص خدمة بعينها). */
    public static final String NAME = "القراءة الذكية السحابية المدفوعة الخاصة";
    public static final String BUTTON = "☁️ قراءة ذكية سحابية مدفوعة خاصة";
    public static final String EXPLAIN = "التطبيق يفهم معظم الرسائل وحده دون إنترنت؛ هذا الخيار يرسل نص الرسالة إلى خدمة ذكاء اصطناعي تختارها (عبر الإنترنت) لقراءة أدق.";
    /** القوالب الجاهزة بترتيب العرض: الخدمة، اسمها، عنوانها الافتراضي. «مخصّص» = أي خدمة حالية أو مستقبلية بصيغة OpenAI. */
    public static final String[] PROVIDERS = {CLAUDE, OPENAI, GEMINI, CUSTOM};
    public static final String[] PROVIDER_NAMES = {"Claude (Anthropic)", "OpenAI", "Gemini (Google)", "مخصّص (أي خدمة متوافقة)"};

    public static int providerIndex(String p) {
        for (int i = 0; i < PROVIDERS.length; i++) if (PROVIDERS[i].equals(p)) return i;
        return 0;
    }

    public static final class Config {
        public String provider = CLAUDE;
        public String key = "";
        public String model = "";
        /** عنوان الخدمة المتوافقة مع OpenAI (أو بديل لعنوان Claude عند الحاجة). */
        public String baseUrl = "";

        public boolean claude() { return provider == null || provider.isEmpty() || CLAUDE.equals(provider); }

        public String model() {
            String m = model == null ? "" : model.trim();
            return m.isEmpty() && claude() ? CLAUDE_MODEL : m;
        }

        public String endpoint() {
            String b = baseUrl == null ? "" : baseUrl.trim();
            if (claude()) return b.isEmpty() ? CLAUDE_URL : b;
            if (b.isEmpty()) b = GEMINI.equals(provider) ? GEMINI_URL : CUSTOM.equals(provider) ? "" : OPENAI_URL;
            if (b.isEmpty()) return "";
            while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
            return b.endsWith("/chat/completions") ? b : b + "/chat/completions";
        }

        public String problem() {
            if (key == null || key.trim().isEmpty()) return "أضف مفتاح الخدمة من الإعدادات ← " + NAME + ".";
            if (!claude() && model().isEmpty()) return "اكتب اسم النموذج كما في موقع الخدمة (الإعدادات ← " + NAME + ").";
            String e = endpoint();
            if (e.isEmpty()) return "اكتب عنوان الخدمة المخصّصة (مثل https://…/v1) في الإعدادات.";
            // 10.11 (AUDIT-013): المفتاح يُرسل مع كل طلب، فلا http بلا تشفير إلا لخدمة على هذا الجهاز نفسه
            if (!e.startsWith("https://") && !(e.startsWith("http://") && Model.localUrl(e))) return "عنوان الخدمة يجب أن يبدأ بـ https://";
            return null;
        }
    }

    // ===================================================================================
    // الطلب
    // ===================================================================================

    static String systemPrompt(List<MessageParser.Cur> curs, Map<String, List<String>> aliases) {
        StringBuilder c = new StringBuilder();
        for (MessageParser.Cur x : curs) {
            c.append("\n- ").append(x.code);
            if (!x.name.isEmpty()) c.append(" = ").append(x.name);
            List<String> w = aliases == null ? null : aliases.get(x.code);
            if (w != null && !w.isEmpty()) c.append(" (also written: ").append(String.join(", ", w)).append(")");
        }
        return "You read money-transfer (hawala) instructions copied from WhatsApp for a remittance office's accountant. "
                + "The message may be in any language or a mix (Arabic incl. Levantine dialect, English, Turkish, Romanian, French, German, Spanish, "
                + "Russian, Persian, Kurdish, ...), with amounts in digits or written in words, and with lines in any order.\n"
                + "Extract only what the message states:\n"
                + "- person: the beneficiary who receives the money, full name exactly as written (keep its script); drop titles such as Mr, Bay, "
                + "Domnul, Herr, السيد. Empty if not stated.\n"
                + "- amount: the amount to deliver as a plain number: digits only, '.' for decimals, no thousands separators "
                + "(e.g. 'two thousand five hundred' -> 2500, '5 bin' -> 5000, 'خمسمية' -> 500, 'الفين ونص' -> 2500, '1.500' -> 1500). Never the fee.\n"
                + "- currency: the code of the transfer currency, chosen ONLY from the known currencies below. If the message names another "
                + "currency, leave currency empty and put that currency's usual code or word in currency_word.\n"
                + "- currency_word: the word or symbol the message uses for the currency.\n"
                + "- phone, city (delivery city or country), sender (who sends, if stated), fee (fee or commission as a plain number, if stated; in hawala slang 'راجع' is a fee the office pays us: "
                + "'راجع 1%' or 'راجع ١ بالمية' is 1 percent of the amount, so return fee = amount x 1 / 100; 'راجع 5 بالالف' is 5 per thousand), "
                + "net (true when the amount is net / صافي, with no fee deducted).\n"
                + "Never guess or invent: use an empty string when a value is not clearly in the message.\n"
                + "Known currencies:" + c;
    }

    static final String[] FIELDS = {"person", "amount", "currency", "currency_word", "phone", "city", "sender", "fee"};

    static JSONObject schema() throws JSONException {
        JSONObject props = new JSONObject();
        for (String f : FIELDS) props.put(f, new JSONObject().put("type", "string"));
        props.put("net", new JSONObject().put("type", "boolean"));
        JSONArray req = new JSONArray();
        for (String f : FIELDS) req.put(f);
        req.put("net");
        return new JSONObject().put("type", "object").put("properties", props).put("required", req).put("additionalProperties", false);
    }

    static String userText(String message) { return "WhatsApp message:\n<<<\n" + message.trim() + "\n>>>"; }

    public static JSONObject claudeBody(Config c, String message, List<MessageParser.Cur> curs, Map<String, List<String>> aliases) throws JSONException {
        JSONObject body = new JSONObject();
        body.put("model", c.model());
        // 10.11 (AUDIT-014): النماذج الحديثة تفكّر قبل الرد افتراضيًا ويُحتسب تفكيرها من max_tokens؛ 600 قد تنفد قبل نص الرد
        body.put("max_tokens", 4096);
        body.put("system", systemPrompt(curs, aliases));
        body.put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", userText(message))));
        body.put("output_config", new JSONObject().put("format", new JSONObject().put("type", "json_schema").put("schema", schema())));
        return body;
    }

    public static JSONObject openAiBody(Config c, String message, List<MessageParser.Cur> curs, Map<String, List<String>> aliases) throws JSONException {
        String sys = systemPrompt(curs, aliases) + "\nReply with one JSON object only, with exactly these keys: "
                + String.join(", ", FIELDS) + " (strings) and net (boolean).";
        JSONObject body = new JSONObject();
        body.put("model", c.model());
        body.put("temperature", 0);
        body.put("messages", new JSONArray()
                .put(new JSONObject().put("role", "system").put("content", sys))
                .put(new JSONObject().put("role", "user").put("content", userText(message))));
        return body;
    }

    // ===================================================================================
    // الرد
    // ===================================================================================

    /** يستخرج كائن JSON من نص الرد (قد يحيط به كلام أو ```json). */
    static JSONObject jsonIn(String text) throws Exception {
        if (text == null) throw new Exception("رد فارغ من خدمة الذكاء الاصطناعي.");
        int a = text.indexOf('{'), b = text.lastIndexOf('}');
        if (a < 0 || b <= a) throw new Exception("رد خدمة الذكاء الاصطناعي ليس بالصيغة المتوقعة.");
        return new JSONObject(text.substring(a, b + 1));
    }

    public static MessageParser.Result parseResponse(Config c, String body, List<MessageParser.Cur> curs) throws Exception {
        try {
            return parseResponse0(c, body, curs);
        } catch (JSONException e) {
            throw new Exception("رد خدمة الذكاء الاصطناعي ليس بالصيغة المتوقعة.");
        }
    }

    private static MessageParser.Result parseResponse0(Config c, String body, List<MessageParser.Cur> curs) throws Exception {
        JSONObject root = new JSONObject(body);
        String text = null;
        if (c.claude()) {
            if ("refusal".equals(root.optString("stop_reason"))) throw new Exception("رفضت الخدمة قراءة هذه الرسالة.");
            boolean cut = "max_tokens".equals(root.optString("stop_reason"));
            JSONArray content = root.optJSONArray("content");
            if (content != null) {
                for (int i = 0; i < content.length(); i++) {
                    JSONObject b = content.getJSONObject(i);
                    if ("text".equals(b.optString("type"))) {
                        text = b.optString("text");
                        break;
                    }
                }
            }
            if (cut && (text == null || text.indexOf('}') < 0)) throw new Exception("انقطع رد خدمة الذكاء الاصطناعي قبل اكتماله؛ أعد المحاولة أو أدخل المعلومات بيدك.");
        } else {
            JSONArray ch = root.optJSONArray("choices");
            if (ch != null && ch.length() > 0) {
                JSONObject m = ch.getJSONObject(0).optJSONObject("message");
                if (m != null) text = m.optString("content", null);
            }
        }
        return fromJson(jsonIn(text), curs);
    }

    private static String str(JSONObject o, String k, int max) {
        Object v = o.opt(k);
        if (v == null || v == JSONObject.NULL) return "";
        String s = MessageParser.clean(String.valueOf(v)).trim();
        if (s.equalsIgnoreCase("null") || s.equalsIgnoreCase("none") || s.equals("-")) return "";
        return s.length() > max ? s.substring(0, max).trim() : s;
    }

    /** رقم المبلغ كما يقبله النموذج: «2500»، «1,500.50»، «1.500»؛ وإلا فارغ. */
    static String amount(String raw) {
        String s = MessageParser.digits(raw == null ? "" : raw).replaceAll("[\\s'’_]", "");
        if (s.isEmpty()) return "";
        BigDecimal v = MessageParser.number(s);
        if (v == null) {
            try {
                v = new BigDecimal(s.replace(",", ""));
            } catch (NumberFormatException e) {
                return "";
            }
        }
        return v.signum() > 0 ? v.stripTrailingZeros().toPlainString() : "";
    }

    static MessageParser.Result fromJson(JSONObject o, List<MessageParser.Cur> curs) {
        MessageParser.Result r = new MessageParser.Result();
        r.person = MessageParser.titleCase(str(o, "person", 80));
        r.amount = amount(str(o, "amount", 40));
        r.phone = str(o, "phone", 40);
        r.city = str(o, "city", 60);
        r.sender = str(o, "sender", 80);
        r.fee = amount(str(o, "fee", 40));
        r.net = o.optBoolean("net", false);
        String code = str(o, "currency", 20).toUpperCase(Locale.ROOT);
        String word = str(o, "currency_word", 30);
        Set<String> codes = new HashSet<>();
        for (MessageParser.Cur c : curs) codes.add(c.code);
        if (!code.isEmpty() && codes.contains(code)) {
            r.currency = code;
            r.currencyWord = word.isEmpty() ? code : word;
        } else {
            r.currency = null;
            r.currencyWord = !code.isEmpty() ? code : word;
        }
        return r;
    }

    // ===================================================================================
    // الاتصال
    // ===================================================================================

    static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream i = in; ByteArrayOutputStream o = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192];
            int n;
            while ((n = i.read(b)) > 0) o.write(b, 0, n);
            return new String(o.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** رسالة خطأ مفهومة بالعربية، مع رمز HTTP ورسالة الخدمة المختصرة للتشخيص. */
    static String friendlyError(int code, String body) {
        String detail = "";
        try {
            JSONObject e = new JSONObject(body).optJSONObject("error");
            if (e != null) detail = e.optString("message", "");
        } catch (Exception ignored) {
            detail = body == null ? "" : body;
        }
        detail = detail.replaceAll("\\s+", " ").trim();
        if (detail.length() > 160) detail = detail.substring(0, 160) + "…";
        String low = detail.toLowerCase(Locale.ROOT);
        String m;
        if (code == 401) m = "مفتاح الخدمة غير صحيح أو منتهٍ. تحقق منه في الإعدادات.";
        else if (code == 403) m = "رفضت الخدمة الطلب. قد لا تكون متاحة في بلدك، أو لا يملك المفتاح صلاحية.";
        else if (code == 404) m = "اسم النموذج أو عنوان الخدمة غير صحيح.";
        else if (code == 429) m = "تجاوزت حد الاستخدام أو نفد الرصيد. انتظر قليلًا أو اشحن حسابك لدى الخدمة.";
        else if (low.contains("credit") || low.contains("balance") || low.contains("quota") || low.contains("billing"))
            m = "رصيد حسابك لدى خدمة الذكاء الاصطناعي لا يكفي.";
        else if (code == 529 || code >= 500) m = "خدمة الذكاء الاصطناعي مشغولة الآن. أعد المحاولة بعد قليل.";
        else if (code == 400) m = "لم تقبل الخدمة الطلب. تحقق من اسم النموذج.";
        else m = "تعذرت القراءة الذكية السحابية لهذه الرسالة.";
        return m + " (HTTP " + code + (detail.isEmpty() ? "" : ": " + detail) + ")";
    }

    /** يرسل الرسالة ويعيد النتيجة. يعمل في الخلفية فقط. */
    public static MessageParser.Result call(Config c, String message, List<MessageParser.Cur> curs, Map<String, List<String>> aliases) throws Exception {
        String p = c.problem();
        if (p != null) throw new Exception(p);
        JSONObject body = c.claude() ? claudeBody(c, message, curs, aliases) : openAiBody(c, message, curs, aliases);
        String[] res = post(c, body);
        int code = Integer.parseInt(res[0]);
        if (code == 400 && c.claude() && !friendlyError(code, res[1]).startsWith("رصيد")) {
            // احتياط: إن لم يقبل النموذج «output_config» نطلب JSON بالتعليمات فقط
            body.remove("output_config");
            body.put("system", body.getString("system") + "\nReply with one JSON object only, with exactly these keys: "
                    + String.join(", ", FIELDS) + " (strings) and net (boolean).");
            res = post(c, body);
            code = Integer.parseInt(res[0]);
        }
        if (code != 200) throw new Exception(friendlyError(code, res[1]));
        return parseResponse(c, res[1], curs);
    }

    /** {رمز HTTP، نص الرد}. */
    private static String[] post(Config c, JSONObject body) throws Exception {
        HttpURLConnection h;
        try {
            h = (HttpURLConnection) new URL(c.endpoint()).openConnection();
        } catch (Exception e) {
            throw new Exception("عنوان خدمة الذكاء الاصطناعي غير صحيح.");
        }
        try {
            h.setConnectTimeout(15000);
            h.setReadTimeout(60000);
            h.setRequestMethod("POST");
            h.setDoOutput(true);
            h.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            h.setRequestProperty("Accept", "application/json");
            if (c.claude()) {
                h.setRequestProperty("x-api-key", c.key.trim());
                h.setRequestProperty("anthropic-version", "2023-06-01");
            } else {
                h.setRequestProperty("Authorization", "Bearer " + c.key.trim());
            }
            byte[] out = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream o = h.getOutputStream()) {
                o.write(out);
            }
            int code = h.getResponseCode();
            String resp = read(code >= 400 ? h.getErrorStream() : h.getInputStream());
            return new String[]{String.valueOf(code), resp};
        } catch (UnknownHostException e) {
            throw new Exception("لا يوجد اتصال بالإنترنت، أو عنوان الخدمة غير صحيح.");
        } catch (SocketTimeoutException e) {
            throw new Exception("انتهت مهلة الاتصال بخدمة الذكاء الاصطناعي. أعد المحاولة.");
        } catch (IOException e) {
            throw new Exception("تعذر الاتصال بخدمة الذكاء الاصطناعي: " + e.getMessage());
        } finally {
            h.disconnect();
        }
    }
}
