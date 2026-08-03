package org.smssecure.smssecure.backup;

import org.smssecure.smssecure.util.Base64;

import java.io.IOException;
import java.security.SecureRandom;

public final class BackupRecoveryKey {
  static final int KEY_LENGTH = 32;

  private BackupRecoveryKey() {}

  public static byte[] generate() {
    byte[] key = new byte[KEY_LENGTH];
    new SecureRandom().nextBytes(key);
    return key;
  }

  public static String encode(byte[] key) {
    requireValid(key);
    return Base64.encodeBytes(key).replace('+', '-').replace('/', '_').replace("=", "");
  }

  public static byte[] decode(String encoded) throws IOException {
    if (encoded == null) throw new IOException("Backup recovery key is required");

    String normalized = encoded.replaceAll("\\s", "")
                   .replace('_', '/').replace('-', '+');
    while (normalized.length() % 4 != 0) normalized += "=";

    byte[] key = Base64.decode(normalized);
    if (key.length != KEY_LENGTH) throw new IOException("Invalid backup recovery key");
    return key;
  }

  private static void requireValid(byte[] key) {
    if (key == null || key.length != KEY_LENGTH) {
      throw new IllegalArgumentException("Backup recovery key must be 256 bits");
    }
  }
}