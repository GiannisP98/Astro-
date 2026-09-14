package com.bridgeastro.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class TokenStore {
    private static final String PREFS = "bridge_astro_secure";
    private static final String VALUE = "sealagom_token_v1";
    private static final String ALIAS = "BridgeAstroSeaLagomKey";
    private final Context context;

    TokenStore(Context context) { this.context = context.getApplicationContext(); }

    boolean hasToken() {
        String v = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(VALUE, "");
        return v != null && !v.isEmpty();
    }

    void clear() { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(VALUE).apply(); }

    void setToken(String token) throws Exception {
        token = token == null ? "" : token.trim();
        if (token.isEmpty()) { clear(); return; }
        SecretKey key = key();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
        String packed = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" +
                Base64.encodeToString(encrypted, Base64.NO_WRAP);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(VALUE, packed).apply();
    }

    String getToken() {
        try {
            SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String packed = p.getString(VALUE, "");
            if (packed == null || packed.isEmpty()) return "";
            String[] parts = packed.split(":", 2);
            if (parts.length != 2) return "";
            byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
            byte[] encrypted = Base64.decode(parts[1], Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) { return ""; }
    }

    private SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) return (SecretKey) ks.getKey(ALIAS, null);
        KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
        return gen.generateKey();
    }
}
