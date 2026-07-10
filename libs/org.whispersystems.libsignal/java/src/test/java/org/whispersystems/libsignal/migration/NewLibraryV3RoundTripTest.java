package org.whispersystems.libsignal.migration;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.InvalidKeyIdException;
import org.signal.libsignal.protocol.SessionBuilder;
import org.signal.libsignal.protocol.SessionCipher;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.ecc.Curve;
import org.signal.libsignal.protocol.ecc.ECKeyPair;
import org.signal.libsignal.protocol.ecc.ECPrivateKey;
import org.signal.libsignal.protocol.message.CiphertextMessage;
import org.signal.libsignal.protocol.message.PreKeySignalMessage;
import org.signal.libsignal.protocol.message.SignalMessage;
import org.signal.libsignal.protocol.state.KyberPreKeyRecord;
import org.signal.libsignal.protocol.state.PreKeyBundle;
import org.signal.libsignal.protocol.state.PreKeyRecord;
import org.signal.libsignal.protocol.state.SignedPreKeyRecord;
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore;
import org.signal.libsignal.protocol.util.KeyHelper;

import java.util.List;

/**
 * Cross-validation test: proves the maintained
 * libsignal-client <strong>pinned to 0.72.1</strong> (Maven Central) loads its host-native core on
 * this machine and that {@link SessionBuilder}/{@link SessionCipher} round-trip encrypt/decrypt in
 * both directions (PREKEY_TYPE → WHISPER_TYPE) while negotiating a classic <strong>X3DH session
 * version 3</strong> — a deliberate constraint that keeps SMS payloads small and interoperable with
 * un-upgraded peers.
 *
 * <p>0.72.1 is the last release whose Java API still exposes the no-Kyber {@link PreKeyBundle}
 * constructor (removed in 0.73.0) and whose Rust core still speaks X3DH (removed in 0.75.0).
 * Building a bundle <em>without</em> a Kyber prekey therefore yields a v3 session here. This test
 * asserts that strictly: if a future bump silently upgraded the handshake to PQXDH/v4 the version
 * assertion would fail.
 */
public class NewLibraryV3RoundTripTest {

  private static final SignalProtocolAddress ALICE_ADDRESS = new SignalProtocolAddress("+14151111111", 1);
  private static final SignalProtocolAddress BOB_ADDRESS   = new SignalProtocolAddress("+14152222222", 1);

  @Test
  public void newLibraryRoundTripsAtSessionVersionThree() throws Exception {
    InMemorySignalProtocolStore aliceStore = newStore();
    InMemorySignalProtocolStore bobStore   = newStore();

    PreKeyBundle bobPreKey = buildBobPreKeyBundle(bobStore, 31337, 22);

    // 0.72.1 ctors take only (store, remoteAddress); there is no UsePqRatchet caller switch.
    SessionBuilder aliceSessionBuilder = new SessionBuilder(aliceStore, BOB_ADDRESS);
    aliceSessionBuilder.process(bobPreKey);

    SessionCipher aliceCipher         = new SessionCipher(aliceStore, BOB_ADDRESS);
    int           aliceSessionVersion = aliceCipher.getSessionVersion();
    System.out.println("[spike] 0.72.1 no-Kyber handshake session version = " + aliceSessionVersion);
    assertEquals("expected classic X3DH session v3", 3, aliceSessionVersion);

    // Outbound: first message after a bundle is PREKEY_TYPE (mirrors SmsCipher.encrypt).
    String            originalMessage = "Silence v3 spike message";
    CiphertextMessage outgoing        = aliceCipher.encrypt(originalMessage.getBytes());
    assertEquals(CiphertextMessage.PREKEY_TYPE, outgoing.getType());

    // Inbound on Bob: reconstruct from serialized bytes (SmsCipher.decrypt path).
    PreKeySignalMessage incoming  = new PreKeySignalMessage(outgoing.serialize());
    SessionCipher       bobCipher = new SessionCipher(bobStore, ALICE_ADDRESS);
    byte[]              decrypted = bobCipher.decrypt(incoming);
    assertArrayEquals(originalMessage.getBytes(), decrypted);

    // Reply over an established session is WHISPER_TYPE.
    CiphertextMessage reply = bobCipher.encrypt("reply".getBytes());
    assertEquals(CiphertextMessage.WHISPER_TYPE, reply.getType());

    byte[] aliceReceived = aliceCipher.decrypt(new SignalMessage(reply.serialize()));
    assertArrayEquals("reply".getBytes(), aliceReceived);

    // Both sides must agree on the same negotiated version, and it must be v3.
    assertEquals(aliceSessionVersion, bobCipher.getSessionVersion());
    assertEquals(3, bobCipher.getSessionVersion());
  }

  /**
   * Store design proof: Silence backs the new
   * store surface with its existing on-disk identity/prekey/signed-prekey/session stores plus a
   * <em>no-op</em> {@code KyberPreKeyStore} (Silence never issues Kyber prekeys). {@link NoKyberStore}
   * makes every {@code KyberPreKeyStore} method throw, so if the v3 handshake or decrypt path ever
   * touched Kyber this test would fail. It must not — proving a no-op Kyber store is safe for v3.
   */
  @Test
  public void noOpKyberStoreKeepsV3SessionEndToEnd() throws Exception {
    NoKyberStore aliceStore = new NoKyberStore();
    NoKyberStore bobStore   = new NoKyberStore();

    PreKeyBundle bobPreKey = buildBobPreKeyBundle(bobStore, 41337, 23);

    new SessionBuilder(aliceStore, BOB_ADDRESS).process(bobPreKey);

    SessionCipher aliceCipher = new SessionCipher(aliceStore, BOB_ADDRESS);
    assertEquals(3, aliceCipher.getSessionVersion());

    CiphertextMessage outgoing = aliceCipher.encrypt("hello".getBytes());
    assertEquals(CiphertextMessage.PREKEY_TYPE, outgoing.getType());

    SessionCipher bobCipher = new SessionCipher(bobStore, ALICE_ADDRESS);
    assertArrayEquals("hello".getBytes(), bobCipher.decrypt(new PreKeySignalMessage(outgoing.serialize())));

    CiphertextMessage reply = bobCipher.encrypt("world".getBytes());
    assertEquals(CiphertextMessage.WHISPER_TYPE, reply.getType());
    assertArrayEquals("world".getBytes(), aliceCipher.decrypt(new SignalMessage(reply.serialize())));

    assertEquals(3, bobCipher.getSessionVersion());
    System.out.println("[spike] no-op KyberPreKeyStore: full v3 round-trip never touched Kyber");
  }

  private static InMemorySignalProtocolStore newStore() {
    return new InMemorySignalProtocolStore(IdentityKeyPair.generate(), KeyHelper.generateRegistrationId(false));
  }

  private PreKeyBundle buildBobPreKeyBundle(InMemorySignalProtocolStore bobStore,
                                            int preKeyId, int signedPreKeyId) throws Exception {
    ECPrivateKey identityPrivate     = bobStore.getIdentityKeyPair().getPrivateKey();
    ECKeyPair    bobPreKeyPair       = Curve.generateKeyPair();
    ECKeyPair    bobSignedPreKeyPair = Curve.generateKeyPair();
    byte[]       bobSignedSignature  = identityPrivate.calculateSignature(bobSignedPreKeyPair.getPublicKey().serialize());

    bobStore.storePreKey(preKeyId, new PreKeyRecord(preKeyId, bobPreKeyPair));
    bobStore.storeSignedPreKey(signedPreKeyId,
                               new SignedPreKeyRecord(signedPreKeyId, System.currentTimeMillis(),
                                                      bobSignedPreKeyPair, bobSignedSignature));

    // No-Kyber 8-arg ctor → classic v3 bundle. This overload was removed in 0.73.0.
    return new PreKeyBundle(bobStore.getLocalRegistrationId(), 1,
                            preKeyId, bobPreKeyPair.getPublicKey(),
                            signedPreKeyId, bobSignedPreKeyPair.getPublicKey(), bobSignedSignature,
                            bobStore.getIdentityKeyPair().getPublicKey());
  }

  /**
   * Mirrors the production no-op {@code KyberPreKeyStore}. Silence issues no Kyber prekeys, so the v3
   * code path must never invoke any of these methods; each throws so an accidental Kyber call fails
   * loudly instead of silently upgrading behaviour.
   */
  private static final class NoKyberStore extends InMemorySignalProtocolStore {
    NoKyberStore() {
      super(IdentityKeyPair.generate(), KeyHelper.generateRegistrationId(false));
    }

    @Override public KyberPreKeyRecord loadKyberPreKey(int kyberPreKeyId) throws InvalidKeyIdException {
      throw new AssertionError("v3 path loaded a Kyber prekey (id=" + kyberPreKeyId + ")");
    }

    @Override public List<KyberPreKeyRecord> loadKyberPreKeys() {
      throw new AssertionError("v3 path enumerated Kyber prekeys");
    }

    @Override public void storeKyberPreKey(int kyberPreKeyId, KyberPreKeyRecord record) {
      throw new AssertionError("v3 path stored a Kyber prekey (id=" + kyberPreKeyId + ")");
    }

    @Override public boolean containsKyberPreKey(int kyberPreKeyId) {
      throw new AssertionError("v3 path queried a Kyber prekey (id=" + kyberPreKeyId + ")");
    }

    @Override public void markKyberPreKeyUsed(int kyberPreKeyId) {
      throw new AssertionError("v3 path marked a Kyber prekey used (id=" + kyberPreKeyId + ")");
    }
  }
}
