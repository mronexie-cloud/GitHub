package com.zlc.wallet;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;

/**
 * The autofill copy of the accounts and passwords. The page sends it over after every change;
 * it is stored in the app's private folder, encrypted with a key kept in the Android Keystore.
 * Format: {"lock":{"salt","hash","iter"} or null, "apps":[{"name","pkgs":[],"domains":[],"creds":[{"label","user","pass"}]}]}
 */
final class Vault {
    private static final String ALIAS = "wallet-vault";
    private static final String FILE = "vault.bin";

    private Vault() {
    }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            kg.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
            return kg.generateKey();
        }
        return ((KeyStore.SecretKeyEntry) ks.getEntry(ALIAS, null)).getSecretKey();
    }

    static synchronized void save(Context c, String json) throws Exception {
        Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
        ci.init(Cipher.ENCRYPT_MODE, key());
        byte[] ct = ci.doFinal(json.getBytes(StandardCharsets.UTF_8));
        String out = Base64.encodeToString(ci.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP);
        File tmp = new File(c.getFilesDir(), FILE + ".tmp");
        try (FileOutputStream fo = new FileOutputStream(tmp)) {
            fo.write(out.getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(new File(c.getFilesDir(), FILE))) throw new Exception("rename failed");
    }

    static synchronized JSONObject load(Context c) {
        try {
            File f = new File(c.getFilesDir(), FILE);
            if (!f.exists()) return null;
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            String[] p = s.split(":");
            Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
            ci.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(p[0], Base64.NO_WRAP)));
            return new JSONObject(new String(ci.doFinal(Base64.decode(p[1], Base64.NO_WRAP)), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    /** Checks a 6-digit PIN against the PBKDF2 hash the page made when the PIN was set. */
    static boolean checkPin(JSONObject lock, String pin) {
        try {
            byte[] salt = Base64.decode(lock.getString("salt"), Base64.NO_WRAP);
            int iter = lock.optInt("iter", 100000);
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] h = f.generateSecret(new PBEKeySpec(pin.toCharArray(), salt, iter, 256)).getEncoded();
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString().equalsIgnoreCase(lock.getString("hash"));
        } catch (Exception e) {
            return false;
        }
    }
}
