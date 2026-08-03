package org.smssecure.smssecure.crypto;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class DeviceKeyEnvelope {
  static final byte[] MAGIC = new byte[] {'S', 'D', 'K', 'W'};
  private static final int VERSION = 1;
  private static final int NONCE_LENGTH = 12;
  private static final int TAG_LENGTH_BITS = 128;
  private static final int HEADER_LENGTH = MAGIC.length + 1 + 1 + Integer.BYTES + NONCE_LENGTH;

  private DeviceKeyEnvelope() {}

  static byte[] encrypt(byte[] plaintext, SecretKey key) throws GeneralSecurityException {
    if (plaintext == null || plaintext.length == 0) {
      throw new GeneralSecurityException("Device envelope plaintext is required");
    }
    byte[] nonce = new byte[NONCE_LENGTH];
    new SecureRandom().nextBytes(nonce);
    byte[] header = header(nonce, plaintext.length + TAG_LENGTH_BITS / Byte.SIZE);

    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
    cipher.updateAAD(header);
    byte[] ciphertext = cipher.doFinal(plaintext);
    return ByteBuffer.allocate(header.length + ciphertext.length)
                     .put(header).put(ciphertext).array();
  }

  static byte[] decrypt(byte[] serialized, SecretKey key) throws GeneralSecurityException {
    if (serialized == null || serialized.length < HEADER_LENGTH + TAG_LENGTH_BITS / Byte.SIZE) {
      throw new GeneralSecurityException("Truncated device envelope");
    }
    ByteBuffer buffer = ByteBuffer.wrap(serialized);
    byte[] magic = new byte[MAGIC.length];
    buffer.get(magic);
    int version = Byte.toUnsignedInt(buffer.get());
    int nonceLength = Byte.toUnsignedInt(buffer.get());
    int ciphertextLength = buffer.getInt();
    if (!Arrays.equals(magic, MAGIC) || version != VERSION || nonceLength != NONCE_LENGTH ||
        ciphertextLength != buffer.remaining() - NONCE_LENGTH ||
        ciphertextLength < TAG_LENGTH_BITS / Byte.SIZE) {
      throw new GeneralSecurityException("Unsupported device envelope");
    }

    byte[] nonce = new byte[nonceLength];
    buffer.get(nonce);
    byte[] header = Arrays.copyOf(serialized, HEADER_LENGTH);
    byte[] ciphertext = new byte[ciphertextLength];
    buffer.get(ciphertext);

    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
    cipher.updateAAD(header);
    return cipher.doFinal(ciphertext);
  }

  private static byte[] header(byte[] nonce, int ciphertextLength) {
    return ByteBuffer.allocate(HEADER_LENGTH)
                     .put(MAGIC)
                     .put((byte) VERSION)
                     .put((byte) nonce.length)
                     .putInt(ciphertextLength)
                     .put(nonce)
                     .array();
  }
}