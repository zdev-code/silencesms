package org.smssecure.smssecure.crypto;

import android.content.Context;
import android.os.Build;
import android.security.keystore.KeyInfo;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;

final class AndroidKeystoreKeyManager {
  private static final String KEYSTORE = "AndroidKeyStore";
  static final String ALIAS_A = "silence.master-secret.device.v1.a";
  static final String ALIAS_B = "silence.master-secret.device.v1.b";

  private AndroidKeystoreKeyManager() {}

  static SecretKey getOrCreate(Context context) throws GeneralSecurityException {
    return getOrCreate(context, ALIAS_A);
  }

  static SecretKey getOrCreate(Context context, String alias) throws GeneralSecurityException {
    KeyStore keyStore = loadKeyStore();
    SecretKey existing = (SecretKey) keyStore.getKey(alias, null);
    if (existing != null) return requireSecureHardware(existing);
    return requireSecureHardware(generate(alias));
  }

  static SecretKey getExisting() throws GeneralSecurityException {
    return getExisting(ALIAS_A);
  }

  static SecretKey getExisting(String alias) throws GeneralSecurityException {
    KeyStore keyStore = loadKeyStore();
    SecretKey existing = (SecretKey) keyStore.getKey(alias, null);
    return existing == null ? null : requireSecureHardware(existing);
  }

  static void delete() throws GeneralSecurityException {
    delete(ALIAS_A);
  }

  static void delete(String alias) throws GeneralSecurityException {
    KeyStore keyStore = loadKeyStore();
    keyStore.deleteEntry(alias);
  }

  private static KeyStore loadKeyStore() throws GeneralSecurityException {
    try {
      KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
      keyStore.load(null);
      return keyStore;
    } catch (IOException error) {
      throw new GeneralSecurityException("Unable to load Android Keystore", error);
    }
  }

  private static SecretKey generate(String alias) throws GeneralSecurityException {
    KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
    KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
        alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .setRandomizedEncryptionRequired(true);
    generator.init(builder.build());
    return generator.generateKey();
  }

  private static SecretKey requireSecureHardware(SecretKey key)
      throws GeneralSecurityException {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return key;

    SecretKeyFactory factory = SecretKeyFactory.getInstance(key.getAlgorithm(), KEYSTORE);
    KeyInfo keyInfo = (KeyInfo) factory.getKeySpec(key, KeyInfo.class);
    int securityLevel = keyInfo.getSecurityLevel();
    if (securityLevel != KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT) {
      throw new GeneralSecurityException("Device protection key is not TEE-backed");
    }
    Log.i("AndroidKeystoreKeyManager", "Device key security level: TRUSTED_ENVIRONMENT");
    return key;
  }
}