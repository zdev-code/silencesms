package org.smssecure.smssecure.crypto;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class Argon2idDeviceTest {
  @Test
  public void derivesDeterministicallyThroughNativeLibrary() throws Exception {
    byte[] salt = new byte[Argon2id.SALT_LENGTH];
    Arrays.fill(salt, (byte) 2);

    assertTrue(Argon2id.isAvailable());
    byte[] first = Argon2id.derive("device-test", salt, 32, 1, 1);
    byte[] second = Argon2id.derive("device-test", salt, 32, 1, 1);

    assertEquals(Argon2id.OUTPUT_LENGTH, first.length);
    assertArrayEquals(first, second);
  }

  @Test
  public void roundTripsProductionCostMasterSecretEnvelope() throws Exception {
    byte[] masterSecret = new byte[36];
    Arrays.fill(masterSecret, (byte) 7);

    byte[] encrypted = Argon2MasterSecretEnvelope.encrypt(masterSecret, "device-test");

    assertArrayEquals(masterSecret,
                      Argon2MasterSecretEnvelope.decrypt(encrypted, "device-test"));
  }
}