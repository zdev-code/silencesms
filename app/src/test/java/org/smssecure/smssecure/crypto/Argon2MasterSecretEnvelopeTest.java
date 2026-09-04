package org.smssecure.smssecure.crypto;

import org.junit.Test;

import java.security.GeneralSecurityException;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

public class Argon2MasterSecretEnvelopeTest {
  private static final byte[] MASTER_SECRET = sequence(36);
  private static final byte[] SALT = filled(Argon2id.SALT_LENGTH, (byte) 2);
  private static final byte[] NONCE = filled(12, (byte) 3);

  @Test
  public void roundTripsVersionedEnvelope() throws Exception {
    byte[] encrypted = encrypt(MASTER_SECRET, "passphrase", fixedDeriver((byte) 7));

    assertArrayEquals(Argon2MasterSecretEnvelope.MAGIC,
                      Arrays.copyOf(encrypted, Argon2MasterSecretEnvelope.MAGIC.length));
    assertArrayEquals(MASTER_SECRET,
                      Argon2MasterSecretEnvelope.decrypt(encrypted, "passphrase",
                                                         fixedDeriver((byte) 7)));
  }

  @Test
  public void roundTripsStoredKdfParameters() throws Exception {
    byte[] encrypted = Argon2MasterSecretEnvelope.encrypt(
        MASTER_SECRET, "passphrase", 40960, 2, 3, SALT, NONCE, fixedDeriver((byte) 7));

    byte[] decrypted = Argon2MasterSecretEnvelope.decrypt(
        encrypted, "passphrase", (passphrase, salt, memoryKiB, iterations, parallelism) -> {
          assertArrayEquals(SALT, salt);
          assertTrue(memoryKiB == 40960);
          assertTrue(iterations == 2);
          assertTrue(parallelism == 3);
          return filled(Argon2id.OUTPUT_LENGTH, (byte) 7);
        });

    assertArrayEquals(MASTER_SECRET, decrypted);
  }

  @Test(expected = InvalidPassphraseException.class)
  public void rejectsWrongPassphraseKey() throws Exception {
    byte[] encrypted = encrypt(MASTER_SECRET, "passphrase", fixedDeriver((byte) 7));

    Argon2MasterSecretEnvelope.decrypt(encrypted, "wrong", fixedDeriver((byte) 8));
  }

  @Test(expected = GeneralSecurityException.class)
  public void rejectsAuthenticatedHeaderTamper() throws Exception {
    byte[] encrypted = encrypt(MASTER_SECRET, "passphrase", fixedDeriver((byte) 7));
    encrypted[10] ^= 1;

    Argon2MasterSecretEnvelope.decrypt(encrypted, "passphrase", fixedDeriver((byte) 7));
  }

  @Test(expected = InvalidPassphraseException.class)
  public void rejectsCiphertextTamper() throws Exception {
    byte[] encrypted = encrypt(MASTER_SECRET, "passphrase", fixedDeriver((byte) 7));
    encrypted[encrypted.length - 1] ^= 1;

    Argon2MasterSecretEnvelope.decrypt(encrypted, "passphrase", fixedDeriver((byte) 7));
  }

  @Test(expected = GeneralSecurityException.class)
  public void rejectsUnsupportedVersion() throws Exception {
    byte[] encrypted = encrypt(MASTER_SECRET, "passphrase", fixedDeriver((byte) 7));
    encrypted[Argon2MasterSecretEnvelope.MAGIC.length] = 2;

    Argon2MasterSecretEnvelope.decrypt(encrypted, "passphrase", fixedDeriver((byte) 7));
  }

  @Test(expected = GeneralSecurityException.class)
  public void rejectsTruncatedEnvelope() throws Exception {
    Argon2MasterSecretEnvelope.decrypt(new byte[12], "passphrase", fixedDeriver((byte) 7));
  }

  @Test(expected = GeneralSecurityException.class)
  public void rejectsExcessiveMemoryBeforeDerivation() throws Exception {
    Argon2MasterSecretEnvelope.encrypt(MASTER_SECRET, "passphrase",
        Argon2id.MAX_MEMORY_KIB + 1, 1, 1, SALT, NONCE,
        (passphrase, salt, memoryKiB, iterations, parallelism) -> {
          throw new AssertionError("Derivation must not run");
        });
  }

  @Test(expected = GeneralSecurityException.class)
  public void rejectsMemoryBelowPolicyFloorBeforeDerivation() throws Exception {
    Argon2MasterSecretEnvelope.encrypt(MASTER_SECRET, "passphrase",
        Argon2MasterSecretEnvelope.MIN_MEMORY_KIB - 1, Argon2MasterSecretEnvelope.MIN_ITERATIONS,
        Argon2MasterSecretEnvelope.MIN_PARALLELISM, SALT, NONCE,
        (passphrase, salt, memoryKiB, iterations, parallelism) -> {
          throw new AssertionError("Derivation must not run");
        });
  }

  @Test(expected = GeneralSecurityException.class)
  public void rejectsIterationsBelowPolicyFloorBeforeDerivation() throws Exception {
    Argon2MasterSecretEnvelope.encrypt(MASTER_SECRET, "passphrase",
        Argon2MasterSecretEnvelope.MIN_MEMORY_KIB, Argon2MasterSecretEnvelope.MIN_ITERATIONS - 1,
        Argon2MasterSecretEnvelope.MIN_PARALLELISM, SALT, NONCE,
        (passphrase, salt, memoryKiB, iterations, parallelism) -> {
          throw new AssertionError("Derivation must not run");
        });
  }

  @Test(expected = GeneralSecurityException.class)
  public void rejectsSubFloorEnvelopeOnDecryptBeforeDerivation() throws Exception {
    byte[] encrypted = encrypt(MASTER_SECRET, "passphrase", fixedDeriver((byte) 7));
    // memoryKiB is the first int after MAGIC, version and kdf id.
    int memoryOffset = Argon2MasterSecretEnvelope.MAGIC.length + 2;
    java.nio.ByteBuffer.wrap(encrypted).putInt(memoryOffset, 8);

    Argon2MasterSecretEnvelope.decrypt(encrypted, "passphrase",
        (passphrase, salt, memoryKiB, iterations, parallelism) -> {
          throw new AssertionError("Derivation must not run");
        });
  }

  @Test
  public void productionDefaultsSatisfyPolicyFloor() {
    assertTrue(Argon2MasterSecretEnvelope.DEFAULT_MEMORY_KIB >= Argon2MasterSecretEnvelope.MIN_MEMORY_KIB);
    assertTrue(Argon2MasterSecretEnvelope.DEFAULT_ITERATIONS >= Argon2MasterSecretEnvelope.MIN_ITERATIONS);
    assertTrue(Argon2MasterSecretEnvelope.DEFAULT_PARALLELISM >= Argon2MasterSecretEnvelope.MIN_PARALLELISM);
  }

  @Test
  public void zeroizesDerivedKeyAfterEncryption() throws Exception {
    byte[] derivedKey = filled(Argon2id.OUTPUT_LENGTH, (byte) 7);

    encrypt(MASTER_SECRET, "passphrase",
            (passphrase, salt, memoryKiB, iterations, parallelism) -> derivedKey);

    assertTrue(isAllZero(derivedKey));
  }

  @Test
  public void characterPassphraseRoundTripsUnchangedEnvelopeAndWipesDerivedKeys()
      throws Exception
  {
    char[] passphrase = "passphrase".toCharArray();
    byte[] encryptionKey = filled(Argon2id.OUTPUT_LENGTH, (byte) 7);
    byte[] encrypted = Argon2MasterSecretEnvelope.encrypt(
        MASTER_SECRET, passphrase, Argon2MasterSecretEnvelope.MIN_MEMORY_KIB,
        Argon2MasterSecretEnvelope.MIN_ITERATIONS,
        Argon2MasterSecretEnvelope.MIN_PARALLELISM, SALT, NONCE,
        (characters, salt, memoryKiB, iterations, parallelism) -> encryptionKey);
    assertTrue(isAllZero(encryptionKey));

    byte[] decryptionKey = filled(Argon2id.OUTPUT_LENGTH, (byte) 7);
    byte[] decrypted = Argon2MasterSecretEnvelope.decrypt(
        encrypted, passphrase,
        (characters, salt, memoryKiB, iterations, parallelism) -> decryptionKey);

    assertArrayEquals(MASTER_SECRET, decrypted);
    assertArrayEquals(Argon2MasterSecretEnvelope.MAGIC,
                      Arrays.copyOf(encrypted, Argon2MasterSecretEnvelope.MAGIC.length));
    assertTrue(isAllZero(decryptionKey));
  }

  private static byte[] encrypt(byte[] masterSecret, String passphrase,
                                Argon2MasterSecretEnvelope.KeyDeriver keyDeriver)
      throws Exception
  {
    return Argon2MasterSecretEnvelope.encrypt(masterSecret, passphrase,
                                              Argon2MasterSecretEnvelope.MIN_MEMORY_KIB,
                                              Argon2MasterSecretEnvelope.MIN_ITERATIONS,
                                              Argon2MasterSecretEnvelope.MIN_PARALLELISM,
                                              SALT, NONCE, keyDeriver);
  }

  private static Argon2MasterSecretEnvelope.KeyDeriver fixedDeriver(byte value) {
    return (passphrase, salt, memoryKiB, iterations, parallelism) ->
        filled(Argon2id.OUTPUT_LENGTH, value);
  }

  private static byte[] sequence(int length) {
    byte[] result = new byte[length];
    for (int i = 0; i < length; i++) result[i] = (byte) i;
    return result;
  }

  private static byte[] filled(int length, byte value) {
    byte[] result = new byte[length];
    Arrays.fill(result, value);
    return result;
  }

  private static boolean isAllZero(byte[] value) {
    for (byte item : value) if (item != 0) return false;
    return true;
  }
}