package org.smssecure.smssecure.crypto;

import org.junit.Test;
import org.smssecure.smssecure.BaseUnitTest;
import org.signal.libsignal.protocol.ecc.Curve;
import org.signal.libsignal.protocol.ecc.ECKeyPair;

import static org.junit.Assert.assertEquals;

public class AsymmetricMasterCipherTest extends BaseUnitTest {

  @Test
  public void testLockedStateRoundTripUsesVersionedMasterCipher() throws Exception {
    ECKeyPair keyPair = Curve.generateKeyPair();
    AsymmetricMasterSecret asymmetricMasterSecret =
        new AsymmetricMasterSecret(keyPair.getPublicKey(), keyPair.getPrivateKey());
    AsymmetricMasterCipher cipher = new AsymmetricMasterCipher(asymmetricMasterSecret);

    String encrypted = cipher.encryptBody("locked-state message");

    assertEquals("locked-state message", cipher.decryptBody(encrypted));
  }
}