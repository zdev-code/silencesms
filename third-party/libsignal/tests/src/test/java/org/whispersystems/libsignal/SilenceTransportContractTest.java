package org.whispersystems.libsignal;

import junit.framework.TestCase;

import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECKeyPair;
import org.whispersystems.libsignal.protocol.CiphertextMessage;
import org.whispersystems.libsignal.protocol.PreKeySignalMessage;
import org.whispersystems.libsignal.protocol.SignalMessage;
import org.whispersystems.libsignal.state.PreKeyBundle;
import org.whispersystems.libsignal.state.PreKeyRecord;
import org.whispersystems.libsignal.state.SessionRecord;
import org.whispersystems.libsignal.state.SignalProtocolStore;
import org.whispersystems.libsignal.state.SignedPreKeyRecord;

import java.util.Arrays;

/**
 * Characterization / contract tests for the *exact* Signal Protocol behaviors that
 * Silence's transport layer relies on (see {@code SmsCipher}, {@code SessionUtil}).
 *
 * <p>These are written to be a portable equivalence harness for the planned
 * libsignal-client (Rust) migration: the assertions capture observable, library-independent
 * contract (session version == 3, message type ordering, plaintext round-trips, and stored
 * {@link SessionRecord} serialization survival). When the new library is adopted, port this
 * file by changing only the imports ({@code org.whispersystems.libsignal.*} →
 * {@code org.signal.libsignal.protocol.*}) and re-run — identical assertions must hold to
 * prove behavioral parity.
 *
 * <p>Migration risk reference: {@code docs/libsignal-client-v3-upgrade-plan.md} §3.2
 * (stored session records are the migration risk). {@link #testStoredSessionRecordSurvivesSerializationRoundTrip}
 * is the direct guard for that risk.
 */
public class SilenceTransportContractTest extends TestCase {

  private static final SignalProtocolAddress ALICE_ADDRESS = new SignalProtocolAddress("+14151111111", 1);
  private static final SignalProtocolAddress BOB_ADDRESS   = new SignalProtocolAddress("+14152222222", 1);

  /**
   * Mirrors the Silence outbound→inbound flow in {@code SmsCipher.encrypt} /
   * {@code SmsCipher.decrypt}: a v3 PreKey handshake produces a PREKEY_TYPE ciphertext,
   * the reply is a WHISPER_TYPE ciphertext, both sides agree on session version 3, and
   * plaintext round-trips in both directions.
   */
  public void testFullPreKeyHandshakeIsVersion3AndRoundTrips() throws Exception {
    SignalProtocolStore aliceStore = new TestInMemorySignalProtocolStore();
    SignalProtocolStore bobStore   = new TestInMemorySignalProtocolStore();

    PreKeyBundle bobPreKey = buildBobPreKeyBundle(bobStore, 31337, 22);

    SessionBuilder aliceSessionBuilder = new SessionBuilder(aliceStore, BOB_ADDRESS);
    aliceSessionBuilder.process(bobPreKey);

    assertTrue(aliceStore.containsSession(BOB_ADDRESS));
    assertEquals(3, aliceStore.loadSession(BOB_ADDRESS).getSessionState().getSessionVersion());

    // Outbound: SmsCipher.encrypt path. First message after a prekey bundle is a PREKEY_TYPE.
    String            originalMessage = "Silence transport contract message";
    SessionCipher     aliceCipher     = new SessionCipher(aliceStore, BOB_ADDRESS);
    CiphertextMessage outgoing        = aliceCipher.encrypt(originalMessage.getBytes());
    assertEquals(CiphertextMessage.PREKEY_TYPE, outgoing.getType());

    // Inbound on Bob's side: SmsCipher.decrypt(IncomingPreKeyBundleMessage) path.
    // SmsCipher reconstructs the message from serialized bytes — exercise that exact path.
    PreKeySignalMessage incoming = new PreKeySignalMessage(outgoing.serialize());
    SessionCipher       bobCipher = new SessionCipher(bobStore, ALICE_ADDRESS);
    byte[]              decrypted = bobCipher.decrypt(incoming);

    assertTrue(Arrays.equals(originalMessage.getBytes(), decrypted));
    assertEquals(3, bobStore.loadSession(ALICE_ADDRESS).getSessionState().getSessionVersion());

    // Reply: now an established session produces a WHISPER_TYPE message.
    CiphertextMessage reply = bobCipher.encrypt("reply".getBytes());
    assertEquals(CiphertextMessage.WHISPER_TYPE, reply.getType());

    byte[] aliceReceived = aliceCipher.decrypt(new SignalMessage(reply.serialize()));
    assertTrue(Arrays.equals("reply".getBytes(), aliceReceived));
  }

  /**
   * Guards the documented #1 migration risk: an on-device {@link SessionRecord} serialized
   * by the current library must be loadable and usable later. Establishes a session, persists
   * the record as bytes, reloads it into a *fresh* store, and proves a subsequent message still
   * decrypts. The new library must satisfy the same round-trip (via
   * {@code SessionRecord.deserialize(byte[])}) for in-place upgrade to be lossless.
   */
  public void testStoredSessionRecordSurvivesSerializationRoundTrip() throws Exception {
    SignalProtocolStore aliceStore = new TestInMemorySignalProtocolStore();
    SignalProtocolStore bobStore   = new TestInMemorySignalProtocolStore();

    SessionCipher aliceCipher = new SessionCipher(aliceStore, BOB_ADDRESS);
    SessionCipher bobCipher   = new SessionCipher(bobStore, ALICE_ADDRESS);
    establishBidirectionalSession(aliceStore, bobStore, aliceCipher, bobCipher, 41337, 23);

    // Persist Alice's session exactly as on-device storage would: as a serialized byte[].
    byte[] persisted = aliceStore.loadSession(BOB_ADDRESS).serialize();
    assertTrue(persisted.length > 0);

    // Simulate an upgrade / reload: reconstruct the record into a brand-new store.
    SignalProtocolStore reloadedAliceStore = new TestInMemorySignalProtocolStore();
    reloadedAliceStore.storeSession(BOB_ADDRESS, new SessionRecord(persisted));

    assertTrue(reloadedAliceStore.containsSession(BOB_ADDRESS));
    assertEquals(3, reloadedAliceStore.loadSession(BOB_ADDRESS).getSessionState().getSessionVersion());

    // The reloaded session must continue to function: Bob can still read Alice's next message.
    SessionCipher     reloadedAliceCipher = new SessionCipher(reloadedAliceStore, BOB_ADDRESS);
    CiphertextMessage afterReload         = reloadedAliceCipher.encrypt("after reload".getBytes());
    byte[]            bobReceived         = bobCipher.decrypt(new SignalMessage(afterReload.serialize()));

    assertTrue(Arrays.equals("after reload".getBytes(), bobReceived));
  }

  /**
   * Pins the wire-format reconstruction contract that {@code SmsCipher} depends on:
   * a ciphertext serialized to bytes and rebuilt via {@code new SignalMessage(bytes)} must
   * decrypt to the identical plaintext. Establishes parity for the transport encoding boundary.
   */
  public void testSerializedWireMessageReconstructsAndDecrypts() throws Exception {
    SignalProtocolStore aliceStore = new TestInMemorySignalProtocolStore();
    SignalProtocolStore bobStore   = new TestInMemorySignalProtocolStore();

    SessionCipher aliceCipher = new SessionCipher(aliceStore, BOB_ADDRESS);
    SessionCipher bobCipher   = new SessionCipher(bobStore, ALICE_ADDRESS);
    establishBidirectionalSession(aliceStore, bobStore, aliceCipher, bobCipher, 51337, 24);

    // Established-session message: serialize → bytes → reconstruct → decrypt (SmsCipher.decrypt path).
    CiphertextMessage outgoing     = aliceCipher.encrypt("over the wire".getBytes());
    byte[]            wireBytes    = outgoing.serialize();
    SignalMessage     reconstructed = new SignalMessage(wireBytes);
    byte[]            decrypted    = bobCipher.decrypt(reconstructed);

    assertTrue(Arrays.equals("over the wire".getBytes(), decrypted));
  }

  /**
   * Drives a full bidirectional v3 handshake: Alice processes Bob's PreKeyBundle and sends the
   * initial PREKEY_TYPE message; Bob decrypts it and replies; Alice decrypts the reply. After this
   * both ciphers are in the established state where outbound messages are WHISPER_TYPE.
   */
  private void establishBidirectionalSession(SignalProtocolStore aliceStore, SignalProtocolStore bobStore,
                                             SessionCipher aliceCipher, SessionCipher bobCipher,
                                             int preKeyId, int signedPreKeyId) throws Exception {
    PreKeyBundle bobPreKey = buildBobPreKeyBundle(bobStore, preKeyId, signedPreKeyId);
    new SessionBuilder(aliceStore, BOB_ADDRESS).process(bobPreKey);

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
}
