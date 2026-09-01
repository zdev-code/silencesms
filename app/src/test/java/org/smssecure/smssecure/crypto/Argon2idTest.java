package org.smssecure.smssecure.crypto;

import org.junit.Test;

import static org.junit.Assert.assertFalse;

public class Argon2idTest {
  private final byte[] password = new byte[8];
  private final byte[] salt     = new byte[Argon2id.SALT_LENGTH];

  @Test(expected = Argon2Exception.class)
  public void rejectsInvalidSaltLength() throws Exception {
    Argon2id.derive(password, new byte[Argon2id.SALT_LENGTH - 1], 32, 1, 1);
  }

  @Test(expected = Argon2Exception.class)
  public void rejectsExcessivePasswordLength() throws Exception {
    Argon2id.derive(new byte[Argon2id.MAX_PASSWORD_BYTES + 1], salt, 32, 1, 1);
  }

  @Test(expected = Argon2Exception.class)
  public void rejectsMemoryBelowLaneMinimum() throws Exception {
    Argon2id.derive(password, salt, 31, 1, 4);
  }

  @Test(expected = Argon2Exception.class)
  public void rejectsExcessiveMemoryBeforeNativeAllocation() throws Exception {
    Argon2id.derive(password, salt, Argon2id.MAX_MEMORY_KIB + 1, 1, 1);
  }

  @Test(expected = Argon2Exception.class)
  public void rejectsExcessiveIterations() throws Exception {
    Argon2id.derive(password, salt, 32, Argon2id.MAX_ITERATIONS + 1, 1);
  }

  @Test(expected = Argon2Exception.class)
  public void rejectsExcessiveParallelism() throws Exception {
    Argon2id.derive(password, salt, 32, 1, Argon2id.MAX_PARALLELISM + 1);
  }

  @Test(expected = Argon2Exception.class)
  public void reportsUnavailableAndroidLibraryOnHost() throws Exception {
    assertFalse(Argon2id.isAvailable());
    Argon2id.derive(password, salt, 32, 1, 1);
  }
}