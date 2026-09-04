package org.smssecure.smssecure.crypto;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.smssecure.smssecure.BaseUnitTest;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

public class DecryptingPartInputStreamTest extends BaseUnitTest {
  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void encryptedPartRoundTrips() throws Exception {
    byte[] plaintext = "encrypted attachment part".getBytes(StandardCharsets.UTF_8);
    File encryptedPart = encrypt(plaintext);

    assertArrayEquals(plaintext, decrypt(encryptedPart));
  }

  @Test
  public void tamperedMacIsRejected() throws Exception {
    File encryptedPart = encrypt("tamper check".getBytes(StandardCharsets.UTF_8));
    try (RandomAccessFile file = new RandomAccessFile(encryptedPart, "rw")) {
      file.seek(file.length() - 1);
      file.write(file.read() ^ 1);
    }

    assertThrows(IOException.class, () -> decrypt(encryptedPart));
  }

  private File encrypt(byte[] plaintext) throws Exception {
    File encryptedPart = temporaryFolder.newFile();
    try (EncryptingPartOutputStream output = new EncryptingPartOutputStream(encryptedPart, masterSecret)) {
      output.write(plaintext);
    }
    return encryptedPart;
  }

  private byte[] decrypt(File encryptedPart) throws Exception {
    try (DecryptingPartInputStream input = new DecryptingPartInputStream(encryptedPart, masterSecret);
         ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[4096];
      int count;
      while ((count = input.read(buffer)) != -1) {
        output.write(buffer, 0, count);
      }
      return output.toByteArray();
    }
  }
}