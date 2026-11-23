package com.smart.hawala;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.json.JSONObject;

/** جلب أسعار الصرف العالمية (لا تُرسل أي بيانات من التطبيق). الأسعار اليدوية لا تُستبدل. */
public final class ExchangeRates {
    private ExchangeRates() {}

    public static final String DEFAULT_URL = "https://open.er-api.com/v6/latest/USD";

    /**
     * 8.0: المصدر قابل للتغيير (مثلًا خادمك بأسعار السوق المحلية) بالصيغة نفسها: {"rates": {"SYP": 13000, ...}}
     * = عدد وحدات العملة لكل دولار، و time_last_update_unix اختياري.
     */
    public static String source(Db db) {
        String u = db.setting("rates_url", "").trim();
        return u.startsWith("https://") ? u : DEFAULT_URL;
    }

    public static int refresh(Db db) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(source(db)).openConnection();
        try {
            h.setConnectTimeout(12000);
            h.setReadTimeout(12000);
            h.setRequestProperty("Accept", "application/json");
            if (h.getResponseCode() != 200) throw new IOException("خادم الأسعار لم يستجب (HTTP " + h.getResponseCode() + ")");
            String s;
            try (InputStream i = h.getInputStream(); ByteArrayOutputStream o = new ByteArrayOutputStream()) {
                byte[] b = new byte[4096];
                int n;
                while ((n = i.read(b)) > 0) o.write(b, 0, n);
                s = new String(o.toByteArray(), StandardCharsets.UTF_8);
            }
            JSONObject root = new JSONObject(s);
            if (!"success".equalsIgnoreCase(root.optString("result", "success"))) throw new IOException("خادم الأسعار أرجع خطأ");
            JSONObject rates = root.getJSONObject("rates");
            Map<String, BigDecimal> out = new HashMap<>();
            Iterator<String> it = rates.keys();
            while (it.hasNext()) {
                String code = it.next();
                BigDecimal units = new BigDecimal(rates.get(code).toString());
                if (units.signum() <= 0) continue;
                out.put(code, BigDecimal.ONE.divide(units, 16, RoundingMode.HALF_EVEN));
            }
            long at = root.optLong("time_last_update_unix", 0) * 1000L;
            db.saveOnlineRates(out, at > 0 ? at : Clock.now());
            return out.size();
        } finally {
            h.disconnect();
        }
    }
}
