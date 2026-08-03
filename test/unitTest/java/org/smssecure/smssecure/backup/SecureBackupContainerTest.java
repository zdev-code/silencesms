package org.smssecure.smssecure.backup;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class SecureBackupContainerTest {
  @Test
  public void recoveryKeyRoundTrips() throws Exception {
    byte[] key = BackupRecoveryKey.generate();
    assertArrayEquals(key, BackupRecoveryKey.decode(BackupRecoveryKey.encode(key)));
  }

  @Test
  public void encryptedContainerRoundTrips() throws Exception {
    byte[] key = BackupRecoveryKey.generate();
    byte[] plaintext = "portable secure backup".getBytes(StandardCharsets.UTF_8);
    ByteArrayOutputStream encrypted = new ByteArrayOutputStream();

    try (OutputStream output = SecureBackupContainer.encrypt(encrypted, key)) {
      output.write(plaintext);
    }

    try (InputStream input = SecureBackupContainer.decrypt(
        new ByteArrayInputStream(encrypted.toByteArray()), key)) {
      assertArrayEquals(plaintext, readAll(input));
    }
  }

  @Test(expected = IOException.class)
  public void wrongKeyIsRejected() throws Exception {
    byte[] encrypted = encrypt("secret", BackupRecoveryKey.generate());
    try (InputStream input = SecureBackupContainer.decrypt(
        new ByteArrayInputStream(encrypted), BackupRecoveryKey.generate())) {
      readAll(input);
    }
  }

  @Test(expected = IOException.class)
  public void tamperingIsRejected() throws Exception {
    byte[] key = BackupRecoveryKey.generate();
    byte[] encrypted = encrypt("secret", key);
    encrypted[encrypted.length - 1] ^= 1;
    try (InputStream input = SecureBackupContainer.decrypt(new ByteArrayInputStream(encrypted), key)) {
      readAll(input);
    }
  }

  @Test
  public void recoveryKeyAllowsPasswordManagerSeparators() throws Exception {
    byte[] key = BackupRecoveryKey.generate();
    String encoded = BackupRecoveryKey.encode(key);
    assertEquals(encoded.length(), 43);
    assertArrayEquals(key, BackupRecoveryKey.decode(encoded.substring(0, 20) + " " + encoded.substring(20)));
  }

  private byte[] encrypt(String value, byte[] key) throws Exception {
    ByteArrayOutputStream encrypted = new ByteArrayOutputStream();
    try (OutputStream output = SecureBackupContainer.encrypt(encrypted, key)) {
      output.write(value.getBytes(StandardCharsets.UTF_8));
    }
    return encrypted.toByteArray();
  }

  private byte[] readAll(InputStream input) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[256];
    int read;
    while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
    return output.toByteArray();
  }
}