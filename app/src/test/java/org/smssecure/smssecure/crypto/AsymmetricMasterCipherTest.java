package org.smssecure.smssecure.crypto;

import org.junit.Test;
import org.smssecure.smssecure.BaseUnitTest;
import org.smssecure.smssecure.util.Base64;
import org.smssecure.smssecure.util.Util;
import org.signal.libsignal.protocol.ecc.Curve;
import org.signal.libsignal.protocol.ecc.ECKeyPair;

import static org.junit.Assert.assertEquals;

public class AsymmetricMasterCipherTest extends BaseUnitTest {

  @Test
  public void testLockedStateRoundTripUsesVersionOneMasterCipher() throws Exception {
    ECKeyPair keyPair = Curve.generateKeyPair();
    AsymmetricMasterSecret asymmetricMasterSecret =
        new AsymmetricMasterSecret(keyPair.getPublicKey(), keyPair.getPrivateKey());
    AsymmetricMasterCipher cipher = new AsymmetricMasterCipher(asymmetricMasterSecret);

    String encrypted = cipher.encryptBody("locked-state message");
    byte[][] parts = Util.split(Base64.decode(encrypted), PublicKey.KEY_SIZE,
                                Base64.decode(encrypted).length - PublicKey.KEY_SIZE);

    assertEquals(MasterCipherEnvelope.ALGORITHM_LEGACY_CBC_HMAC_SHA1,
                 MasterCipherEnvelope.getAlgorithm(parts[1]));
    assertEquals("locked-state message", cipher.decryptBody(encrypted));
  }
}