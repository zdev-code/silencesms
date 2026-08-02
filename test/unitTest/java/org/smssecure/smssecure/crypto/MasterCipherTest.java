package org.smssecure.smssecure.crypto;

import org.junit.Before;
import org.junit.Test;
import org.smssecure.smssecure.BaseUnitTest;
import org.signal.libsignal.protocol.InvalidMessageException;
import org.signal.libsignal.protocol.ecc.Curve;
import org.signal.libsignal.protocol.ecc.ECKeyPair;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MasterCipherTest extends BaseUnitTest {
  private MasterCipher masterCipher;

  @Before
  @Override
  public void setUp() throws Exception {
    super.setUp();
    masterCipher = new MasterCipher(masterSecret);
  }

  @Test(expected = InvalidMessageException.class)
  public void testEncryptBytesWithZeroBody() throws Exception {
    masterCipher.decryptBytes(new byte[]{});
  }

  @Test
  public void testVersionOneEnvelopeRoundTrip() throws Exception {
    byte[] plaintext = "versioned local ciphertext".getBytes(StandardCharsets.UTF_8);

    byte[] encrypted = masterCipher.encryptBytes(plaintext);

    assertTrue(MasterCipherEnvelope.hasMagic(encrypted));
    assertFalse(isLegacyShape(encrypted));
    assertArrayEquals(plaintext, masterCipher.decryptBytes(encrypted));
  }

  @Test
  public void testVersionOneBodyRoundTripAfterCipherRecreation() throws Exception {
    String encrypted = masterCipher.encryptBody("persisted body");

    MasterCipher restartedCipher = new MasterCipher(masterSecret);

    assertEquals("persisted body", restartedCipher.decryptBody(encrypted));
  }

  @Test
  public void testVersionOnePrivateKeyRoundTrip() throws Exception {
    ECKeyPair keyPair = Curve.generateKeyPair();

    byte[] encrypted = masterCipher.encryptKey(keyPair.getPrivateKey());

    assertArrayEquals(keyPair.getPrivateKey().serialize(), masterCipher.decryptKey(encrypted).serialize());
  }

  @Test
  public void testLegacyBlobRoundTrip() throws Exception {
    byte[] plaintext = "legacy local ciphertext".getBytes(StandardCharsets.UTF_8);
    byte[] encrypted = encryptLegacy(plaintext, new byte[16]);

    assertTrue(isLegacyShape(encrypted));
    assertArrayEquals(plaintext, masterCipher.decryptBytes(encrypted));
  }

  @Test
  public void testValidLegacyBlobStartingWithMagicRemainsLegacy() throws Exception {
    byte[] iv = new byte[16];
    System.arraycopy(MasterCipherEnvelope.MAGIC, 0, iv, 0, MasterCipherEnvelope.MAGIC.length);
    byte[] plaintext = "magic-prefixed legacy ciphertext".getBytes(StandardCharsets.UTF_8);
    byte[] encrypted = encryptLegacy(plaintext, iv);

    assertTrue(MasterCipherEnvelope.hasMagic(encrypted));
    assertTrue(isLegacyShape(encrypted));
    assertArrayEquals(plaintext, masterCipher.decryptBytes(encrypted));
  }

  @Test(expected = InvalidMessageException.class)
  public void testTruncatedEnvelopeIsRejected() throws Exception {
    byte[] encrypted = masterCipher.encryptBytes("truncated".getBytes(StandardCharsets.UTF_8));
    masterCipher.decryptBytes(Arrays.copyOf(encrypted, encrypted.length - 1));
  }

  @Test(expected = InvalidMessageException.class)
  public void testTamperedEnvelopeHeaderIsRejected() throws Exception {
    byte[] encrypted = masterCipher.encryptBytes("tampered".getBytes(StandardCharsets.UTF_8));
    encrypted[5] = 2;
    masterCipher.decryptBytes(encrypted);
  }

  @Test(expected = InvalidMessageException.class)
  public void testTamperedEnvelopeCiphertextIsRejected() throws Exception {
    byte[] encrypted = masterCipher.encryptBytes("tampered payload".getBytes(StandardCharsets.UTF_8));
    encrypted[encrypted.length - 21] ^= 0x01;
    masterCipher.decryptBytes(encrypted);
  }

  @Test(expected = InvalidMessageException.class)
  public void testUnsupportedEnvelopeVersionIsRejected() throws Exception {
    byte[] encrypted = masterCipher.encryptBytes("unsupported".getBytes(StandardCharsets.UTF_8));
    encrypted[4] = 2;
    masterCipher.decryptBytes(encrypted);
  }

  @Test(expected = InvalidMessageException.class)
  public void testWrongMasterSecretIsRejected() throws Exception {
    byte[] encrypted = masterCipher.encryptBytes("wrong key".getBytes(StandardCharsets.UTF_8));
    MasterSecret wrongSecret = new MasterSecret(new SecretKeySpec(filledBytes(16, (byte) 1), "AES"),
                                                new SecretKeySpec(filledBytes(20, (byte) 2), "HmacSHA1"));

    new MasterCipher(wrongSecret).decryptBytes(encrypted);
  }

  private byte[] encryptLegacy(byte[] plaintext, byte[] iv) throws Exception {
    Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
    cipher.init(Cipher.ENCRYPT_MODE, masterSecret.getEncryptionKey(), new IvParameterSpec(iv));
    byte[] ciphertext = cipher.doFinal(plaintext);
    byte[] encryptedBody = new byte[iv.length + ciphertext.length];
    System.arraycopy(iv, 0, encryptedBody, 0, iv.length);
    System.arraycopy(ciphertext, 0, encryptedBody, iv.length, ciphertext.length);

    Mac mac = Mac.getInstance("HmacSHA1");
    mac.init(masterSecret.getMacKey());
    byte[] authenticationTag = mac.doFinal(encryptedBody);
    byte[] result = Arrays.copyOf(encryptedBody, encryptedBody.length + authenticationTag.length);
    System.arraycopy(authenticationTag, 0, result, encryptedBody.length, authenticationTag.length);
    return result;
  }

  private boolean isLegacyShape(byte[] serialized) {
    return serialized.length >= 52 && (serialized.length - 36) % 16 == 0;
  }

  private byte[] filledBytes(int length, byte value) {
    byte[] result = new byte[length];
    Arrays.fill(result, value);
    return result;
  }
}
