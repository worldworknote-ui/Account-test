package com.smart.hawala;

import java.util.LinkedHashMap;
import java.util.Map;

/** 9.0.1: أسماء عربية ومنازل عشرية للعملات الشائعة، لإضافة عملة من قائمة أسعار الإنترنت بضغطة. */
public final class WorldCurrencies {
    private WorldCurrencies() {}

    /** الرمز ← {الاسم، المنازل}. */
    public static final Map<String, Object[]> ALL = new LinkedHashMap<>();

    private static void put(String code, String name, int dec) { ALL.put(code, new Object[]{name, dec}); }

    static {
        put("USD", "دولار أمريكي", 2);
        put("EUR", "يورو", 2);
        put("TRY", "ليرة تركية", 2);
        put("SYP", "ليرة سورية", 0);
        put("SAR", "ريال سعودي", 2);
        put("AED", "درهم إماراتي", 2);
        put("JOD", "دينار أردني", 3);
        put("IQD", "دينار عراقي", 0);
        put("LBP", "ليرة لبنانية", 0);
        put("EGP", "جنيه مصري", 2);
        put("KWD", "دينار كويتي", 3);
        put("QAR", "ريال قطري", 2);
        put("BHD", "دينار بحريني", 3);
        put("OMR", "ريال عماني", 3);
        put("YER", "ريال يمني", 0);
        put("LYD", "دينار ليبي", 3);
        put("TND", "دينار تونسي", 3);
        put("DZD", "دينار جزائري", 2);
        put("MAD", "درهم مغربي", 2);
        put("SDG", "جنيه سوداني", 2);
        put("GBP", "جنيه إسترليني", 2);
        put("CHF", "فرنك سويسري", 2);
        put("SEK", "كرونة سويدية", 2);
        put("NOK", "كرونة نرويجية", 2);
        put("DKK", "كرونة دنماركية", 2);
        put("PLN", "زلوتي بولندي", 2);
        put("RON", "ليو روماني", 2);
        put("CZK", "كرونة تشيكية", 2);
        put("HUF", "فورنت مجري", 2);
        put("BGN", "ليف بلغاري", 2);
        put("RUB", "روبل روسي", 2);
        put("UAH", "هريفنا أوكرانية", 2);
        put("CAD", "دولار كندي", 2);
        put("AUD", "دولار أسترالي", 2);
        put("NZD", "دولار نيوزيلندي", 2);
        put("JPY", "ين ياباني", 0);
        put("CNY", "يوان صيني", 2);
        put("HKD", "دولار هونغ كونغ", 2);
        put("KRW", "وون كوري", 0);
        put("INR", "روبية هندية", 2);
        put("PKR", "روبية باكستانية", 2);
        put("IRR", "ريال إيراني", 0);
        put("AFN", "أفغاني", 2);
        put("AZN", "مانات أذربيجاني", 2);
        put("GEL", "لاري جورجي", 2);
        put("AMD", "درام أرمني", 2);
        put("KZT", "تينغ كازاخستاني", 2);
        put("MYR", "رينغيت ماليزي", 2);
        put("IDR", "روبية إندونيسية", 0);
        put("SGD", "دولار سنغافوري", 2);
        put("THB", "بات تايلندي", 2);
        put("BRL", "ريال برازيلي", 2);
        put("MXN", "بيزو مكسيكي", 2);
        put("ZAR", "راند جنوب أفريقي", 2);
        put("NGN", "نايرا نيجيرية", 2);
        put("ETB", "بير إثيوبي", 2);
        put("KES", "شلن كيني", 2);
        put("SOS", "شلن صومالي", 2);
        put("MRU", "أوقية موريتانية", 2);
        put("ILS", "شيكل", 2);
        put("XAU", "ذهب (أونصة)", 3);
    }

    public static String name(String code) {
        Object[] o = ALL.get(code);
        return o == null ? code : (String) o[0];
    }

    public static int decimals(String code) {
        Object[] o = ALL.get(code);
        return o == null ? 2 : (Integer) o[1];
    }
}
