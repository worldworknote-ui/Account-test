package com.smart.hawala;

import java.lang.reflect.Field;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

public class TestApp extends SmartApp {
    public void onCreate() {
        try {
            KeyGenerator kg = KeyGenerator.getInstance("AES");
            kg.init(256);
            SecretKey k = kg.generateKey();
            Field f = Crypto.class.getDeclaredField("cachedKey");
            f.setAccessible(true);
            f.set(null, k);
            super.onCreate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
