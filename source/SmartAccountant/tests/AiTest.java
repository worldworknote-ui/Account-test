import com.smart.hawala.*;
import com.sun.net.httpserver.*;
import org.json.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
/** 6.2: طلب الذكاء الاصطناعي ورده (Claude وخدمة متوافقة مع OpenAI) عبر خادم وهمي محلي، بلا إنترنت ولا مفتاح حقيقي. */
public class AiTest {
  static int fails = 0;
  static void check(boolean c, String m) { System.out.println((c ? "  PASS " : "  FAIL ") + m); if (!c) fails++; }
  static volatile String lastPath, lastBody, lastKey, lastVersion, lastAuth;
  static volatile int status = 200;
  static volatile String reply = "";
  public static void main(String[] a) throws Exception {
    HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    srv.createContext("/", ex -> {
      lastPath = ex.getRequestURI().getPath();
      lastKey = ex.getRequestHeaders().getFirst("x-api-key");
      lastVersion = ex.getRequestHeaders().getFirst("anthropic-version");
      lastAuth = ex.getRequestHeaders().getFirst("Authorization");
      try (InputStream in = ex.getRequestBody()) { lastBody = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
      byte[] b = reply.getBytes(StandardCharsets.UTF_8);
      ex.sendResponseHeaders(status, b.length);
      try (OutputStream o = ex.getResponseBody()) { o.write(b); }
    });
    srv.start();
    String base = "http://127.0.0.1:" + srv.getAddress().getPort();
    List<MessageParser.Cur> curs = Arrays.asList(new MessageParser.Cur("USD", "دولار امريكي"), new MessageParser.Cur("EUR", "يورو"), new MessageParser.Cur("USDT", "تيثر"));
    Map<String, List<String>> al = MessageParser.aliasesFrom("USDT=يوز");

    // ---- Claude
    AiExtract.Config c = new AiExtract.Config();
    c.provider = AiExtract.CLAUDE; c.key = "sk-ant-test"; c.baseUrl = base + "/v1/messages";
    String out = new JSONObject().put("person", "Tony").put("amount", "500").put("currency", "").put("currency_word", "RON")
        .put("phone", "").put("city", "Syria").put("sender", "").put("fee", "").put("net", false).toString();
    reply = new JSONObject().put("type", "message").put("stop_reason", "end_turn")
        .put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", out))).toString();
    MessageParser.Result r = AiExtract.call(c, "need five hundred ron to syria for mr tony", curs, al);
    check(lastPath.equals("/v1/messages"), "Claude endpoint");
    check("sk-ant-test".equals(lastKey) && "2023-06-01".equals(lastVersion), "Claude headers x-api-key + anthropic-version");
    JSONObject sent = new JSONObject(lastBody);
    check(sent.getString("model").equals("claude-haiku-5-5"), "default model claude-haiku-5-5");
    check(sent.getJSONObject("output_config").getJSONObject("format").getString("type").equals("json_schema"), "structured output json_schema");
    check(sent.getJSONObject("output_config").getJSONObject("format").getJSONObject("schema").getBoolean("additionalProperties") == false, "schema closed");
    check(sent.getString("system").contains("USDT = تيثر (also written: يوز)"), "currencies + user words in the prompt");
    check(sent.getJSONArray("messages").getJSONObject(0).getString("content").contains("need five hundred ron"), "message text sent");
    check(r.person.equals("Tony") && r.amount.equals("500") && r.currency == null && r.currencyWord.equals("RON") && r.city.equals("Syria"),
        "Claude result parsed; RON not an office currency -> warning word");

    out = new JSONObject().put("person", "حمدي احمد محمد").put("amount", "2,500.50").put("currency", "usd").put("currency_word", "دولار")
        .put("phone", "+963 933 974").put("city", "حماة").put("sender", "").put("fee", "10").put("net", true).toString();
    reply = new JSONObject().put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", out))).toString();
    r = AiExtract.call(c, "x", curs, al);
    check(r.amount.equals("2500.5") && "USD".equals(r.currency) && r.fee.equals("10") && r.net && r.phone.equals("+963 933 974"), "amount with separators, code case, fee, net");

    // ---- errors
    status = 401; reply = "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}";
    try { AiExtract.call(c, "x", curs, al); check(false, "401 should fail"); }
    catch (Exception e) { check(e.getMessage().startsWith("مفتاح الخدمة غير صحيح") && e.getMessage().contains("HTTP 401"), "401 -> wrong key message"); }
    status = 400; reply = "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"Your credit balance is too low\"}}";
    try { AiExtract.call(c, "x", curs, al); check(false, "400 should fail"); }
    catch (Exception e) { check(e.getMessage().startsWith("رصيد حسابك"), "credit balance -> balance message"); }
    // a 400 that is not about credit: retried once without output_config
    final int[] calls = {0};
    srv.removeContext("/");
    srv.createContext("/", ex -> {
      calls[0]++;
      lastPath = ex.getRequestURI().getPath();
      try (InputStream in = ex.getRequestBody()) { lastBody = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
      boolean structured = lastBody.contains("output_config");
      String body;
      try {
        body = structured ? "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"output_config: unsupported\"}}"
            : new JSONObject().put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", "{\"person\":\"Lina\",\"amount\":\"150\",\"currency\":\"EUR\",\"currency_word\":\"euro\",\"phone\":\"\",\"city\":\"\",\"sender\":\"\",\"fee\":\"\",\"net\":false}"))).toString();
      } catch (Exception e) { body = "{}"; }
      byte[] b = body.getBytes(StandardCharsets.UTF_8);
      ex.sendResponseHeaders(structured ? 400 : 200, b.length);
      try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    });
    r = AiExtract.call(c, "a hundred and fifty euros for Lina", curs, al);
    check(calls[0] == 2 && r.person.equals("Lina") && "EUR".equals(r.currency) && !lastBody.contains("output_config"), "400 on output_config -> one retry with plain JSON instructions");
    srv.removeContext("/");
    srv.createContext("/", ex -> {
      lastPath = ex.getRequestURI().getPath();
      lastKey = ex.getRequestHeaders().getFirst("x-api-key");
      lastAuth = ex.getRequestHeaders().getFirst("Authorization");
      try (InputStream in = ex.getRequestBody()) { lastBody = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
      byte[] b = reply.getBytes(StandardCharsets.UTF_8);
      ex.sendResponseHeaders(status, b.length);
      try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    });
    status = 529; reply = "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}";
    try { AiExtract.call(c, "x", curs, al); check(false, "529 should fail"); }
    catch (Exception e) { check(e.getMessage().startsWith("خدمة الذكاء الاصطناعي مشغولة"), "529 -> busy message"); }
    status = 200; reply = "{\"content\":[{\"type\":\"text\",\"text\":\"sorry\"}]}";
    try { AiExtract.call(c, "x", curs, al); check(false, "bad reply should fail"); }
    catch (Exception e) { check(e.getMessage().contains("ليس بالصيغة المتوقعة"), "non-JSON reply -> clear message"); }

    // ---- OpenAI-compatible
    AiExtract.Config o = new AiExtract.Config();
    o.provider = AiExtract.OPENAI; o.key = "sk-test"; o.model = "some-model"; o.baseUrl = base + "/v1/";
    out = "```json\n" + new JSONObject().put("person", "Ahmet Yılmaz").put("amount", "2000").put("currency", "USD").put("currency_word", "dolar")
        .put("phone", "").put("city", "Gaziantep").put("sender", "").put("fee", "").put("net", false) + "\n```";
    reply = new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("message", new JSONObject().put("role", "assistant").put("content", out)))).toString();
    r = AiExtract.call(o, "Ahmet Yılmaz için 2 bin dolar", curs, al);
    check(lastPath.equals("/v1/chat/completions"), "OpenAI-compatible endpoint built from base URL");
    check("Bearer sk-test".equals(lastAuth), "Bearer key");
    sent = new JSONObject(lastBody);
    check(sent.getString("model").equals("some-model") && sent.getJSONArray("messages").length() == 2, "model + system/user messages");
    check(r.person.equals("Ahmet Yılmaz") && r.amount.equals("2000") && "USD".equals(r.currency) && r.city.equals("Gaziantep"), "OpenAI reply in ```json``` parsed");

    // ---- configuration problems (no network)
    AiExtract.Config bad = new AiExtract.Config();
    check(bad.problem() != null && bad.problem().contains("مفتاح"), "missing key reported");
    bad.key = "k"; bad.provider = AiExtract.OPENAI;
    check(bad.problem() != null && bad.problem().contains("النموذج"), "OpenAI-compatible needs a model name");
    check(new AiExtract.Config().endpoint().equals("https://api.anthropic.com/v1/messages"), "default Claude endpoint");
    srv.stop(0);
    System.out.println(fails == 0 ? "ALL AI TESTS PASSED" : fails + " FAILED");
    System.exit(fails == 0 ? 0 : 1);
  }
}
