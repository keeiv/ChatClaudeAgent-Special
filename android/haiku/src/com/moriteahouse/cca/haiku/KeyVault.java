// API keys encrypted with an AES-GCM key held by the Android Keystore. Only the ciphertext is stored, backups
// are off (AndroidManifest allowBackup=false), and the plain key is only ever handed to ModelClient.
package com.moriteahouse.cca.haiku;

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

final class KeyVault {
    private static final String ALIAS = "cca-haiku-api-keys";
    private final SharedPreferences prefs;

    KeyVault(Context context) {
        prefs = context.getSharedPreferences("api-keys", Context.MODE_PRIVATE);
    }

    private SecretKey secret() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return ((KeyStore.SecretKeyEntry) store.getEntry(ALIAS, null)).getSecretKey();
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                           .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                           .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                           .setKeySize(256)
                           .build());
        return generator.generateKey();
    }

    void put(String provider, String key) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, secret());
        byte[] sealed = cipher.doFinal(key.getBytes(StandardCharsets.UTF_8));
        String value = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" +
                       Base64.encodeToString(sealed, Base64.NO_WRAP);
        if (!prefs.edit().putString(provider, value).commit()) throw new IllegalStateException("金鑰沒有存成功");
    }

    String get(String provider) throws Exception {
        String value = prefs.getString(provider, null);
        if (value == null) return null;
        String[] parts = value.split(":", 2);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, secret(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }

    boolean has(String provider) {
        return prefs.contains(provider);
    }

    void clear(String provider) {
        prefs.edit().remove(provider).commit();
    }
}
