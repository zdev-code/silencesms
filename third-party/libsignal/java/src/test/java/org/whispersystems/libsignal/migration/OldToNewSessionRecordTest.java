package org.whispersystems.libsignal.migration;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.SessionBuilder;
import org.whispersystems.libsignal.SessionCipher;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.TestInMemorySignalProtocolStore;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECKeyPair;
import org.whispersystems.libsignal.protocol.CiphertextMessage;
import org.whispersystems.libsignal.protocol.PreKeySignalMessage;
import org.whispersystems.libsignal.protocol.SignalMessage;
import org.whispersystems.libsignal.state.PreKeyBundle;
import org.whispersystems.libsignal.state.PreKeyRecord;
import org.whispersystems.libsignal.state.SignalProtocolStore;
import org.whispersystems.libsignal.state.SignedPreKeyRecord;

/**
 * Cross-validation test: proves the single scariest
 * unknown of the migration — that a session record serialized by the CURRENT (vendored
 * {@code org.whispersystems.libsignal}) library deserializes natively under the NEW maintained
 * library ({@code org.signal.libsignal.protocol}) and still reports session version 3.
 *
 * <p>If this holds, an explicit record converter is unnecessary and an in-place upgrade is a byte
 * pass-through (a re-handshake fallback remains available if a record ever fails to parse). Both
 * libraries sit on the test classpath simultaneously because their packages differ.
 */
public class OldToNewSessionRecordTest {

  private static final SignalProtocolAddress BOB_ADDRESS   = new SignalProtocolAddress("+14152222222", 1);
  private static final SignalProtocolAddress ALICE_ADDRESS = new SignalProtocolAddress("+14151111111", 1);

  @Test
  public void oldSerializedV3SessionRecordDeserializesUnderNewLibrary() throws Exception {
    // 1. Establish a real v3 session with the CURRENT library, exactly as on-device.
    SignalProtocolStore aliceStore = new TestInMemorySignalProtocolStore();
    SignalProtocolStore bobStore   = new TestInMemorySignalProtocolStore();
    establishOldBidirectionalSession(aliceStore, bobStore, 41337, 23);

    byte[] oldSerializedRecord = aliceStore.loadSession(BOB_ADDRESS).serialize();
    assertTrue(oldSerializedRecord.length > 0);
    assertEquals(3, aliceStore.loadSession(BOB_ADDRESS).getSessionState().getSessionVersion());

    // 2. Load those exact bytes under the NEW library (its ctor IS the deserializer).
    org.signal.libsignal.protocol.state.SessionRecord migrated =
        new org.signal.libsignal.protocol.state.SessionRecord(oldSerializedRecord);

    // 3. The new library must agree it is a v3 session (proves wire/record compatibility).
    assertEquals(3, migrated.getSessionVersion());

    // 4. And re-serializing under the new library must preserve the version (record survives a
    //    full round-trip through the new container).
    org.signal.libsignal.protocol.state.SessionRecord reloaded =
        new org.signal.libsignal.protocol.state.SessionRecord(migrated.serialize());
    assertEquals(3, reloaded.getSessionVersion());
  }

  private void establishOldBidirectionalSession(SignalProtocolStore aliceStore, SignalProtocolStore bobStore,
                                                int preKeyId, int signedPreKeyId) throws Exception {
    PreKeyBundle bobPreKey = buildBobPreKeyBundle(bobStore, preKeyId, signedPreKeyId);
    new SessionBuilder(aliceStore, BOB_ADDRESS).process(bobPreKey);

    SessionCipher aliceCipher = new SessionCipher(aliceStore, BOB_ADDRESS);
    SessionCipher bobCipher   = new SessionCipher(bobStore, ALICE_ADDRESS);

    CiphertextMessage prekey = aliceCipher.encrypt("handshake".getBytes());
    assertEquals(CiphertextMessage.PREKEY_TYPE, prekey.getType());
    bobCipher.decrypt(new PreKeySignalMessage(prekey.serialize()));

    CiphertextMessage bobReply = bobCipher.encrypt("handshake-ack".getBytes());
    assertEquals(CiphertextMessage.WHISPER_TYPE, bobReply.getType());
    aliceCipher.decrypt(new SignalMessage(bobReply.serialize()));
  }

  private PreKeyBundle buildBobPreKeyBundle(SignalProtocolStore bobStore, int preKeyId, int signedPreKeyId)
      throws InvalidKeyException {
    ECKeyPair bobPreKeyPair       = Curve.generateKeyPair();
    ECKeyPair bobSignedPreKeyPair = Curve.generateKeyPair();
    byte[]    bobSignedSignature  = Curve.calculateSignature(bobStore.getIdentityKeyPair().getPrivateKey(),
                                                             bobSignedPreKeyPair.getPublicKey().serialize());

    bobStore.storePreKey(preKeyId, new PreKeyRecord(preKeyId, bobPreKeyPair));
    bobStore.storeSignedPreKey(signedPreKeyId,
                               new SignedPreKeyRecord(signedPreKeyId, System.currentTimeMillis(),
                                                      bobSignedPreKeyPair, bobSignedSignature));

    return new PreKeyBundle(bobStore.getLocalRegistrationId(), 1,
                            preKeyId, bobPreKeyPair.getPublicKey(),
                            signedPreKeyId, bobSignedPreKeyPair.getPublicKey(), bobSignedSignature,
                            bobStore.getIdentityKeyPair().getPublicKey());
  }

  /**
   * Hybrid hand-off proof: a session
   * established entirely by the CURRENT vendored library (as Silence's Key-Exchange path does) is
   * handed to the NEW {@code SessionCipher}, which must continue the Double Ratchet — encrypt and
   * decrypt in both directions, staying at v3 — across the library boundary. This is the one new
   * cryptographic hand-off the hybrid introduces.
   */
  @Test
  public void vendoredEstablishedSessionCiphersUnderNewLibrary() throws Exception {
    SignalProtocolStore aliceOld = new TestInMemorySignalProtocolStore();
    SignalProtocolStore bobOld   = new TestInMemorySignalProtocolStore();
    establishOldBidirectionalSession(aliceOld, bobOld, 43337, 25);

    byte[] aliceSessionBytes = aliceOld.loadSession(BOB_ADDRESS).serialize();
    byte[] bobSessionBytes   = bobOld.loadSession(ALICE_ADDRESS).serialize();

    org.signal.libsignal.protocol.SignalProtocolAddress bobNewAddr =
        new org.signal.libsignal.protocol.SignalProtocolAddress("+14152222222", 1);
    org.signal.libsignal.protocol.SignalProtocolAddress aliceNewAddr =
        new org.signal.libsignal.protocol.SignalProtocolAddress("+14151111111", 1);

    org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore aliceNew =
        new org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore(
            org.signal.libsignal.protocol.IdentityKeyPair.generate(),
            org.signal.libsignal.protocol.util.KeyHelper.generateRegistrationId(false));
    org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore bobNew =
        new org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore(
            org.signal.libsignal.protocol.IdentityKeyPair.generate(),
            org.signal.libsignal.protocol.util.KeyHelper.generateRegistrationId(false));

    aliceNew.storeSession(bobNewAddr, new org.signal.libsignal.protocol.state.SessionRecord(aliceSessionBytes));
    bobNew.storeSession(aliceNewAddr, new org.signal.libsignal.protocol.state.SessionRecord(bobSessionBytes));

    org.signal.libsignal.protocol.SessionCipher aliceCipher =
        new org.signal.libsignal.protocol.SessionCipher(aliceNew, bobNewAddr);
    org.signal.libsignal.protocol.SessionCipher bobCipher =
        new org.signal.libsignal.protocol.SessionCipher(bobNew, aliceNewAddr);

    assertEquals(3, aliceCipher.getSessionVersion());
    assertEquals(3, bobCipher.getSessionVersion());

    for (int i = 0; i < 5; i++) {
      byte[] aMsg = ("alice->bob #" + i).getBytes();
      org.signal.libsignal.protocol.message.CiphertextMessage a = aliceCipher.encrypt(aMsg);
      assertArrayEquals(aMsg,
          bobCipher.decrypt(new org.signal.libsignal.protocol.message.SignalMessage(a.serialize())));

      byte[] bMsg = ("bob->alice #" + i).getBytes();
      org.signal.libsignal.protocol.message.CiphertextMessage b = bobCipher.encrypt(bMsg);
      assertArrayEquals(bMsg,
          aliceCipher.decrypt(new org.signal.libsignal.protocol.message.SignalMessage(b.serialize())));
    }

    System.out.println("[spike] vendored-established session ciphered 10 messages under new SessionCipher at v3");
  }
}
