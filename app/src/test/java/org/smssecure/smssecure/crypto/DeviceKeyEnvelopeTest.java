package org.smssecure.smssecure.crypto;

import org.junit.Test;

import java.security.GeneralSecurityException;
import java.util.Arrays;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import static org.junit.Assert.assertArrayEquals;

public class DeviceKeyEnvelopeTest {
  @Test
  public void roundTrips() throws Exception {
    SecretKey key = key();
    byte[] plaintext = sequence(73);
    assertArrayEquals(plaintext, DeviceKeyEnvelope.decrypt(DeviceKeyEnvelope.encrypt(plaintext, key), key));
  }

  @Test(expected = GeneralSecurityException.class)
  public void rejectsWrongKey() throws Exception {
    DeviceKeyEnvelope.decrypt(DeviceKeyEnvelope.encrypt(sequence(36), key()), key());
  }

  @Test(expected = GeneralSecurityException.class)
  public void rejectsHeaderTamper() throws Exception {
    SecretKey key = key();
    byte[] envelope = DeviceKeyEnvelope.encrypt(sequence(36), key);
    envelope[4] ^= 1;
    DeviceKeyEnvelope.decrypt(envelope, key);
  }

  @Test(expected = GeneralSecurityException.class)
  public void rejectsCiphertextTamper() throws Exception {
    SecretKey key = key();
    byte[] envelope = DeviceKeyEnvelope.encrypt(sequence(36), key);
    envelope[envelope.length - 1] ^= 1;
    DeviceKeyEnvelope.decrypt(envelope, key);
  }

  private SecretKey key() throws Exception {
    KeyGenerator generator = KeyGenerator.getInstance("AES");
    generator.init(256);
    return generator.generateKey();
  }

  private byte[] sequence(int length) {
    byte[] value = new byte[length];
    for (int i = 0; i < value.length; i++) value[i] = (byte) i;
    return value;
  }
}