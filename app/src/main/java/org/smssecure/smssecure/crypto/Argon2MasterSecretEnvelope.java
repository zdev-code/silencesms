package org.smssecure.smssecure.crypto;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class Argon2MasterSecretEnvelope {
  static final byte[] MAGIC = new byte[] {'S', 'M', 'S', 'K'};
  static final int VERSION_1 = 1;
  static final int KDF_ARGON2ID = 1;

  static final int DEFAULT_MEMORY_KIB  = 64 * 1024;
  static final int DEFAULT_ITERATIONS  = 3;
  static final int DEFAULT_PARALLELISM = 4;

  // Policy floor, below the defaults so future low-RAM tuning stays possible. The header is
  // authenticated, so this cannot be reached by editing an envelope; it rejects envelopes that
  // were deliberately generated weak.
  static final int MIN_MEMORY_KIB  = 32 * 1024;
  static final int MIN_ITERATIONS  = 2;
  static final int MIN_PARALLELISM = 1;

  private static final int NONCE_LENGTH = 12;
  private static final int TAG_LENGTH_BITS = 128;
  private static final int MASTER_SECRET_LENGTH = 36;
  private static final int FIXED_HEADER_LENGTH = MAGIC.length + 1 + 1 + Integer.BYTES +
                                                  Integer.BYTES + 1 + 1 + 1 + Integer.BYTES;

  interface KeyDeriver {
    byte[] derive(String passphrase, byte[] salt, int memoryKiB,
                  int iterations, int parallelism) throws GeneralSecurityException;
  }

  private Argon2MasterSecretEnvelope() {}

  static byte[] encrypt(byte[] masterSecret, String passphrase) throws GeneralSecurityException {
    byte[] salt = new byte[Argon2id.SALT_LENGTH];
    byte[] nonce = new byte[NONCE_LENGTH];
    SecureRandom random = new SecureRandom();
    random.nextBytes(salt);
    random.nextBytes(nonce);
    return encrypt(masterSecret, passphrase, DEFAULT_MEMORY_KIB, DEFAULT_ITERATIONS,
                   DEFAULT_PARALLELISM, salt, nonce, Argon2id::derive);
  }

  static byte[] decrypt(byte[] serialized, String passphrase)
      throws GeneralSecurityException, InvalidPassphraseException
  {
    return decrypt(serialized, passphrase, Argon2id::derive);
  }

  static byte[] encrypt(byte[] masterSecret, String passphrase, int memoryKiB, int iterations,
                        int parallelism, byte[] salt, byte[] nonce, KeyDeriver keyDeriver)
      throws GeneralSecurityException
  {
    if (masterSecret == null || masterSecret.length != MASTER_SECRET_LENGTH) {
      throw new GeneralSecurityException("Invalid master secret length");
    }
    validateParameters(memoryKiB, iterations, parallelism, salt, nonce);

    int ciphertextLength = masterSecret.length + TAG_LENGTH_BITS / Byte.SIZE;
    byte[] header = serializeHeader(memoryKiB, iterations, parallelism, salt, nonce,
                                    ciphertextLength);
    byte[] key = keyDeriver.derive(passphrase, salt, memoryKiB, iterations, parallelism);
    try {
      if (key == null || key.length != Argon2id.OUTPUT_LENGTH) {
        throw new GeneralSecurityException("Invalid Argon2id key length");
      }
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                  new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
      cipher.updateAAD(header);
      byte[] ciphertext = cipher.doFinal(masterSecret);
      return ByteBuffer.allocate(header.length + ciphertext.length)
                       .put(header)
                       .put(ciphertext)
                       .array();
    } finally {
      if (key != null) Arrays.fill(key, (byte) 0);
    }
  }

  static byte[] decrypt(byte[] serialized, String passphrase, KeyDeriver keyDeriver)
      throws GeneralSecurityException, InvalidPassphraseException
  {
    Parsed parsed = parse(serialized);
    byte[] key = keyDeriver.derive(passphrase, parsed.salt, parsed.memoryKiB,
                                   parsed.iterations, parsed.parallelism);
    try {
      if (key == null || key.length != Argon2id.OUTPUT_LENGTH) {
        throw new GeneralSecurityException("Invalid Argon2id key length");
      }
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                  new GCMParameterSpec(TAG_LENGTH_BITS, parsed.nonce));
      cipher.updateAAD(parsed.header);
      byte[] masterSecret = cipher.doFinal(parsed.ciphertext);
      if (masterSecret.length != MASTER_SECRET_LENGTH) {
        Arrays.fill(masterSecret, (byte) 0);
        throw new GeneralSecurityException("Invalid decrypted master secret length");
      }
      return masterSecret;
    } catch (AEADBadTagException error) {
      throw new InvalidPassphraseException(error);
    } finally {
      if (key != null) Arrays.fill(key, (byte) 0);
    }
  }

  private static Parsed parse(byte[] serialized) throws GeneralSecurityException {
    if (serialized == null || serialized.length < FIXED_HEADER_LENGTH + TAG_LENGTH_BITS / Byte.SIZE) {
      throw new GeneralSecurityException("Truncated Argon2 master-secret envelope");
    }

    ByteBuffer buffer = ByteBuffer.wrap(serialized);
    byte[] magic = new byte[MAGIC.length];
    buffer.get(magic);
    int version = Byte.toUnsignedInt(buffer.get());
    int kdf = Byte.toUnsignedInt(buffer.get());
    int memoryKiB = buffer.getInt();
    int iterations = buffer.getInt();
    int parallelism = Byte.toUnsignedInt(buffer.get());
    int saltLength = Byte.toUnsignedInt(buffer.get());
    int nonceLength = Byte.toUnsignedInt(buffer.get());
    int ciphertextLength = buffer.getInt();

    if (!Arrays.equals(MAGIC, magic) || version != VERSION_1 || kdf != KDF_ARGON2ID ||
        saltLength != Argon2id.SALT_LENGTH || nonceLength != NONCE_LENGTH) {
      throw new GeneralSecurityException("Unsupported Argon2 master-secret envelope");
    }
    if (buffer.remaining() != saltLength + nonceLength + ciphertextLength ||
        ciphertextLength != MASTER_SECRET_LENGTH + TAG_LENGTH_BITS / Byte.SIZE) {
      throw new GeneralSecurityException("Invalid Argon2 master-secret envelope length");
    }

    byte[] salt = new byte[saltLength];
    byte[] nonce = new byte[nonceLength];
    buffer.get(salt);
    buffer.get(nonce);
    validateParameters(memoryKiB, iterations, parallelism, salt, nonce);
    byte[] header = Arrays.copyOf(serialized, FIXED_HEADER_LENGTH + saltLength + nonceLength);
    byte[] ciphertext = new byte[ciphertextLength];
    buffer.get(ciphertext);
    return new Parsed(memoryKiB, iterations, parallelism, salt, nonce, header, ciphertext);
  }

  static void validate(byte[] serialized) throws GeneralSecurityException {
    parse(serialized);
  }

  private static byte[] serializeHeader(int memoryKiB, int iterations, int parallelism,
                                        byte[] salt, byte[] nonce, int ciphertextLength) {
    return ByteBuffer.allocate(FIXED_HEADER_LENGTH + salt.length + nonce.length)
                     .put(MAGIC)
                     .put((byte) VERSION_1)
                     .put((byte) KDF_ARGON2ID)
                     .putInt(memoryKiB)
                     .putInt(iterations)
                     .put((byte) parallelism)
                     .put((byte) salt.length)
                     .put((byte) nonce.length)
                     .putInt(ciphertextLength)
                     .put(salt)
                     .put(nonce)
                     .array();
  }

  private static void validateParameters(int memoryKiB, int iterations, int parallelism,
                                         byte[] salt, byte[] nonce)
      throws GeneralSecurityException
  {
    if (parallelism < MIN_PARALLELISM || parallelism > Argon2id.MAX_PARALLELISM ||
        iterations < MIN_ITERATIONS || iterations > Argon2id.MAX_ITERATIONS ||
        memoryKiB < MIN_MEMORY_KIB || memoryKiB > Argon2id.MAX_MEMORY_KIB ||
        memoryKiB < 8 * parallelism ||
        salt == null || salt.length != Argon2id.SALT_LENGTH ||
        nonce == null || nonce.length != NONCE_LENGTH) {
      throw new GeneralSecurityException("Invalid Argon2 master-secret parameters");
    }
  }

  private static final class Parsed {
    final int memoryKiB;
    final int iterations;
    final int parallelism;
    final byte[] salt;
    final byte[] nonce;
    final byte[] header;
    final byte[] ciphertext;

    Parsed(int memoryKiB, int iterations, int parallelism, byte[] salt, byte[] nonce,
           byte[] header, byte[] ciphertext) {
      this.memoryKiB = memoryKiB;
      this.iterations = iterations;
      this.parallelism = parallelism;
      this.salt = salt;
      this.nonce = nonce;
      this.header = header;
      this.ciphertext = ciphertext;
    }
  }
}