package org.smssecure.smssecure.backup;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class SecureBackupContainer {
  private static final byte[] MAGIC = new byte[] {'S', 'B', 'K', 'P'};
  private static final int VERSION = 1;
  private static final int NONCE_LENGTH = 12;
  private static final int TAG_LENGTH_BITS = 128;

  private SecureBackupContainer() {}

  public static OutputStream encrypt(OutputStream output, byte[] recoveryKey)
      throws IOException
  {
    requireKey(recoveryKey);
    byte[] nonce = new byte[NONCE_LENGTH];
    new SecureRandom().nextBytes(nonce);
    byte[] header = header(nonce);

    output.write(header);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(recoveryKey, "AES"),
                  new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
      cipher.updateAAD(header);
      return new CipherOutputStream(output, cipher);
    } catch (GeneralSecurityException error) {
      throw new IOException("Unable to initialize backup encryption", error);
    } finally {
      Arrays.fill(nonce, (byte) 0);
    }
  }

  public static AuthenticatedInputStream decrypt(InputStream input, byte[] recoveryKey)
      throws IOException
  {
    requireKey(recoveryKey);
    DataInputStream data = new DataInputStream(input);
    byte[] magic = new byte[MAGIC.length];
    data.readFully(magic);
    int version = data.readUnsignedByte();
    int nonceLength = data.readUnsignedByte();
    if (!Arrays.equals(magic, MAGIC) || version != VERSION || nonceLength != NONCE_LENGTH) {
      throw new IOException("Unsupported secure backup format");
    }

    byte[] nonce = new byte[nonceLength];
    data.readFully(nonce);
    byte[] header = header(nonce);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(recoveryKey, "AES"),
                  new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
      cipher.updateAAD(header);
      return new AuthenticatedInputStream(data, cipher);
    } catch (GeneralSecurityException error) {
      throw new IOException("Unable to initialize backup decryption", error);
    } finally {
      Arrays.fill(nonce, (byte) 0);
    }
  }

  private static byte[] header(byte[] nonce) throws IOException {
    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
    DataOutputStream data = new DataOutputStream(bytes);
    data.write(MAGIC);
    data.writeByte(VERSION);
    data.writeByte(nonce.length);
    data.write(nonce);
    data.flush();
    return bytes.toByteArray();
  }

  private static void requireKey(byte[] recoveryKey) {
    if (recoveryKey == null || recoveryKey.length != BackupRecoveryKey.KEY_LENGTH) {
      throw new IllegalArgumentException("Backup recovery key must be 256 bits");
    }
  }

  /**
   * Streams GCM plaintext without {@link javax.crypto.CipherInputStream}, whose {@code close()}
   * swallows tag failures. Readers that stop before EOF (a {@code ZipInputStream} stops at the
   * central directory) must call {@link #verify()} before trusting anything they read.
   */
  public static final class AuthenticatedInputStream extends InputStream {
    private static final byte[] EMPTY = new byte[0];

    private final InputStream source;
    private final Cipher      cipher;
    private final byte[]      inputBuffer = new byte[8192];

    private byte[]  plaintext = EMPTY;
    private int     plaintextOffset;
    private boolean finished;
    private boolean authenticated;

    private AuthenticatedInputStream(InputStream source, Cipher cipher) {
      this.source = source;
      this.cipher = cipher;
    }

    @Override
    public int read() throws IOException {
      byte[] single = new byte[1];
      return read(single, 0, 1) == -1 ? -1 : single[0] & 0xFF;
    }

    @Override
    public int read(byte[] destination, int offset, int length) throws IOException {
      if (destination == null) throw new NullPointerException();
      if (offset < 0 || length < 0 || length > destination.length - offset) {
        throw new IndexOutOfBoundsException();
      }
      if (length == 0) return 0;

      while (plaintextOffset >= plaintext.length) {
        if (!fill()) return -1;
      }
      int available = Math.min(length, plaintext.length - plaintextOffset);
      System.arraycopy(plaintext, plaintextOffset, destination, offset, available);
      plaintextOffset += available;
      return available;
    }

    /** Consumes any remaining ciphertext and throws unless the GCM tag verifies. */
    public void verify() throws IOException {
      byte[] discard = new byte[inputBuffer.length];
      while (read(discard, 0, discard.length) != -1) ;
      Arrays.fill(discard, (byte) 0);
      if (!authenticated) throw new IOException("Backup authentication did not complete");
    }

    @Override
    public void close() throws IOException {
      Arrays.fill(plaintext, (byte) 0);
      plaintext = EMPTY;
      plaintextOffset = 0;
      Arrays.fill(inputBuffer, (byte) 0);
      source.close();
    }

    private boolean fill() throws IOException {
      if (finished) return false;

      int read = source.read(inputBuffer);
      byte[] produced;
      try {
        if (read == -1) {
          finished  = true;
          produced  = cipher.doFinal();
          authenticated = true;
        } else {
          produced = cipher.update(inputBuffer, 0, read);
        }
      } catch (GeneralSecurityException error) {
        finished = true;
        throw new IOException("Backup authentication failed", error);
      }

      Arrays.fill(plaintext, (byte) 0);
      plaintext = produced == null ? EMPTY : produced;
      plaintextOffset = 0;
      return plaintext.length > 0 || !finished;
    }
  }
}