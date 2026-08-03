package org.smssecure.smssecure.crypto;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.security.keystore.StrongBoxUnavailableException;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

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
    if (existing != null) return existing;

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
        context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)) {
      try {
        return generate(alias, true);
      } catch (StrongBoxUnavailableException error) {
        // Fall through to the ordinary hardware/software-backed Android Keystore.
      }
    }
    return generate(alias, false);
  }

  static SecretKey getExisting() throws GeneralSecurityException {
    return getExisting(ALIAS_A);
  }

  static SecretKey getExisting(String alias) throws GeneralSecurityException {
    KeyStore keyStore = loadKeyStore();
    return (SecretKey) keyStore.getKey(alias, null);
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

  private static SecretKey generate(String alias, boolean strongBox) throws GeneralSecurityException {
    KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
    KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
        alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .setRandomizedEncryptionRequired(true);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) builder.setIsStrongBoxBacked(strongBox);
    generator.init(builder.build());
    return generator.generateKey();
  }
}