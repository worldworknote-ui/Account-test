package com.smart.hawala;

/** 12.7: إعدادات «القراءة الذكية السحابية» من القاعدة (مشتركة بين الهاتف والكمبيوتر؛ AiExtract يبقى منطقًا بحتًا). */
public final class AiPrefs {
    private AiPrefs() {}

    public static AiExtract.Config config(Db db) {
        AiExtract.Config c = new AiExtract.Config();
        String p = db.setting("ai_provider", AiExtract.CLAUDE);
        c.provider = java.util.Arrays.asList(AiExtract.PROVIDERS).contains(p) ? p : AiExtract.CLAUDE;
        String k = db.setting("ai_key", "");
        String plain = k.isEmpty() ? "" : Crypto.dec(k);
        c.key = plain == null || Crypto.PLACEHOLDER.equals(plain) ? "" : plain;
        c.model = db.setting("ai_model", "");
        c.baseUrl = db.setting("ai_url", "");
        return c;
    }

}
