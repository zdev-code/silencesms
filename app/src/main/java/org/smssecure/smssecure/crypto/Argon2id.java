package org.smssecure.smssecure.crypto;

import java.nio.charset.StandardCharsets;
import java.nio.CharBuffer;
import java.text.Normalizer;
import java.util.Arrays;

public final class Argon2id {
  public static final int SALT_LENGTH       = 16;
  public static final int OUTPUT_LENGTH     = 32;
  public static final int MAX_MEMORY_KIB    = 64 * 1024;
  public static final int MAX_ITERATIONS    = 10;
  public static final int MAX_PARALLELISM   = 4;
  public static final int MAX_PASSWORD_BYTES = 1024;

  private static final boolean AVAILABLE = loadLibrary();

  private Argon2id() {}

  public static boolean isAvailable() {
    return AVAILABLE;
  }

  public static byte[] derive(String password, byte[] salt, int memoryKiB,
                              int iterations, int parallelism)
      throws Argon2Exception
  {
    if (password == null) throw new Argon2Exception("Argon2 password is required");

    byte[] passwordBytes = Normalizer.normalize(password, Normalizer.Form.NFC)
                                     .getBytes(StandardCharsets.UTF_8);
    try {
      return derive(passwordBytes, salt, memoryKiB, iterations, parallelism);
    } finally {
      Arrays.fill(passwordBytes, (byte) 0);
    }
  }

  public static byte[] derive(char[] password, byte[] salt, int memoryKiB,
                              int iterations, int parallelism)
      throws Argon2Exception
  {
    if (password == null) throw new Argon2Exception("Argon2 password is required");

    // Normalizer exposes only an immutable String result. Keep that unavoidable value local and
    // wipe the mutable UTF-8 copy passed to native code.
    String normalized = Normalizer.normalize(CharBuffer.wrap(password), Normalizer.Form.NFC);
    byte[] passwordBytes = normalized.getBytes(StandardCharsets.UTF_8);
    try {
      return derive(passwordBytes, salt, memoryKiB, iterations, parallelism);
    } finally {
      Arrays.fill(passwordBytes, (byte) 0);
    }
  }

  public static byte[] derive(byte[] password, byte[] salt, int memoryKiB,
                              int iterations, int parallelism)
      throws Argon2Exception
  {
    validate(password, salt, memoryKiB, iterations, parallelism);
    if (!AVAILABLE) throw new Argon2Exception("Argon2 native library is unavailable");

    return deriveNative(password, salt, memoryKiB, iterations, parallelism, OUTPUT_LENGTH);
  }

  private static void validate(byte[] password, byte[] salt, int memoryKiB,
                               int iterations, int parallelism)
      throws Argon2Exception
  {
    if (password == null || password.length > MAX_PASSWORD_BYTES) {
      throw new Argon2Exception("Invalid Argon2 password length");
    }
    if (salt == null || salt.length != SALT_LENGTH) {
      throw new Argon2Exception("Argon2 salt must be 16 bytes");
    }
    if (parallelism < 1 || parallelism > MAX_PARALLELISM) {
      throw new Argon2Exception("Invalid Argon2 parallelism");
    }
    if (iterations < 1 || iterations > MAX_ITERATIONS) {
      throw new Argon2Exception("Invalid Argon2 iteration count");
    }
    if (memoryKiB < 8 * parallelism || memoryKiB > MAX_MEMORY_KIB) {
      throw new Argon2Exception("Invalid Argon2 memory cost");
    }
  }

  private static boolean loadLibrary() {
    try {
      System.loadLibrary("argon2");
      return true;
    } catch (UnsatisfiedLinkError error) {
      return false;
    }
  }

  private static native byte[] deriveNative(byte[] password, byte[] salt, int memoryKiB,
                                            int iterations, int parallelism, int outputLength)
      throws Argon2Exception;
}