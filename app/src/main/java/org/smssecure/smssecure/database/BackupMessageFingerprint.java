package org.smssecure.smssecure.database;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class BackupMessageFingerprint {

  private static final char[] HEX = "0123456789abcdef".toCharArray();

  private BackupMessageFingerprint() {}

  static String fromBackup(XmlBackup.XmlBackupItem item) {
    return create(normalize(item.getAddress()), item.getDate(), item.getType(), item.getProtocol(),
                  normalize(item.getSubject()), normalize(item.getBody()),
                  normalize(item.getServiceCenter()));
  }

  static String create(String address, long date, int type, int protocol, String subject,
                       String body, String serviceCenter)
  {
    MessageDigest digest;

    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }

    update(digest, address);
    digest.update(ByteBuffer.allocate(Long.BYTES).putLong(date).array());
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(type).array());
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(protocol).array());
    update(digest, subject);
    update(digest, body);
    update(digest, serviceCenter);

    StringBuilder encoded = new StringBuilder(64);
    for (byte value : digest.digest()) {
      encoded.append(HEX[(value >>> 4) & 0x0f]);
      encoded.append(HEX[value & 0x0f]);
    }
    return encoded.toString();
  }

  private static void update(MessageDigest digest, String value) {
    if (value == null) {
      digest.update((byte) 0);
      return;
    }

    byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
    digest.update((byte) 1);
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(encoded.length).array());
    digest.update(encoded);
  }

  private static String normalize(String value) {
    return value == null || value.equals("null") ? null : value;
  }
}