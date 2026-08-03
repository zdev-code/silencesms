package org.whispersystems.libsignal.migration;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.SessionBuilder;
import org.signal.libsignal.protocol.SessionCipher;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.ecc.Curve;
import org.signal.libsignal.protocol.ecc.ECKeyPair;
import org.signal.libsignal.protocol.ecc.ECPrivateKey;
import org.signal.libsignal.protocol.message.CiphertextMessage;
import org.signal.libsignal.protocol.message.PreKeySignalMessage;
import org.signal.libsignal.protocol.message.SignalMessage;
import org.signal.libsignal.protocol.state.PreKeyBundle;
import org.signal.libsignal.protocol.state.PreKeyRecord;
import org.signal.libsignal.protocol.state.SignedPreKeyRecord;
import org.signal.libsignal.protocol.util.KeyHelper;

/**
 * Store-adapter proof: drives a
 * full, multi-round v3 conversation through {@link SilenceStyleSignalProtocolStore} — the in-memory
 * stand-in for the real {@code SilenceSignalProtocolStore} rewritten against the 0.72.1 API.
 *
 * <p>Because that store persists each session as {@code record.serialize()} bytes and rebuilds it with
 * {@code new SessionRecord(bytes)} on <em>every</em> load (exactly the on-disk {@code MasterCipher}
 * round-trip the real store performs), a passing multi-message exchange proves the v3 Double Ratchet
 * state survives repeated serialize/deserialize cycles through the new library — the central
 * migration risk. The no-op {@code KyberPreKeyStore} / {@code SenderKeyStore} are exercised in their
 * production (non-throwing) shape throughout.
 */
public class SilenceStyleStoreV3Test {

  private static final SignalProtocolAddress ALICE_ADDRESS = new SignalProtocolAddress("+14151111111", 1);
  private static final SignalProtocolAddress BOB_ADDRESS   = new SignalProtocolAddress("+14152222222", 1);

  @Test
  public void v3ConversationSurvivesRepeatedSerializeCyclesThroughAdapter() throws Exception {
    SilenceStyleSignalProtocolStore aliceStore = newStore();
    SilenceStyleSignalProtocolStore bobStore   = newStore();

    // 1. Alice builds a v3 session from Bob's no-Kyber bundle.
    PreKeyBundle bobPreKey = buildNoKyberPreKeyBundle(bobStore, 51337, 24);
    new SessionBuilder(aliceStore, BOB_ADDRESS).process(bobPreKey);

    SessionCipher aliceCipher = new SessionCipher(aliceStore, BOB_ADDRESS);
    SessionCipher bobCipher   = new SessionCipher(bobStore, ALICE_ADDRESS);

    assertEquals("handshake must be classic X3DH v3", 3, aliceCipher.getSessionVersion());

    // 2. First message is a PREKEY message; it bootstraps Bob's side of the session.
    byte[]            firstPlaintext = "msg-0 (prekey)".getBytes();
    CiphertextMessage first          = aliceCipher.encrypt(firstPlaintext);
    assertEquals(CiphertextMessage.PREKEY_TYPE, first.getType());
    assertArrayEquals(firstPlaintext, bobCipher.decrypt(new PreKeySignalMessage(first.serialize())));

    // 3. Many back-and-forth WHISPER messages. Every encrypt/decrypt forces the adapter to reload the
    //    session from serialized bytes and re-persist it — exercising the ratchet across disk-like
    //    round-trips. The version must stay 3 the whole time.
    for (int i = 1; i <= 10; i++) {
      byte[]            bobToAlice = ("bob->alice #" + i).getBytes();
      CiphertextMessage fromBob    = bobCipher.encrypt(bobToAlice);
      assertEquals(CiphertextMessage.WHISPER_TYPE, fromBob.getType());
      assertArrayEquals(bobToAlice, aliceCipher.decrypt(new SignalMessage(fromBob.serialize())));

      byte[]            aliceToBob = ("alice->bob #" + i).getBytes();
      CiphertextMessage fromAlice  = aliceCipher.encrypt(aliceToBob);
      assertEquals(CiphertextMessage.WHISPER_TYPE, fromAlice.getType());
      assertArrayEquals(aliceToBob, bobCipher.decrypt(new SignalMessage(fromAlice.serialize())));

      assertEquals(3, aliceCipher.getSessionVersion());
      assertEquals(3, bobCipher.getSessionVersion());
    }

    // 4. Both sides still hold a usable, committed session.
    assertTrue(aliceStore.containsSession(BOB_ADDRESS));
    assertTrue(bobStore.containsSession(ALICE_ADDRESS));
    System.out.println("[spike] Silence-style adapter: 21 v3 messages across serialize round-trips, version held at 3");
  }

  private static SilenceStyleSignalProtocolStore newStore() {
    return new SilenceStyleSignalProtocolStore(IdentityKeyPair.generate(), KeyHelper.generateRegistrationId(false));
  }

  private PreKeyBundle buildNoKyberPreKeyBundle(SilenceStyleSignalProtocolStore bobStore,
                                                int preKeyId, int signedPreKeyId) throws Exception {
    ECPrivateKey identityPrivate     = bobStore.getIdentityKeyPair().getPrivateKey();
    ECKeyPair    bobPreKeyPair       = Curve.generateKeyPair();
    ECKeyPair    bobSignedPreKeyPair = Curve.generateKeyPair();
    byte[]       bobSignedSignature  = identityPrivate.calculateSignature(bobSignedPreKeyPair.getPublicKey().serialize());

    bobStore.storePreKey(preKeyId, new PreKeyRecord(preKeyId, bobPreKeyPair));
    bobStore.storeSignedPreKey(signedPreKeyId,
                               new SignedPreKeyRecord(signedPreKeyId, System.currentTimeMillis(),
                                                      bobSignedPreKeyPair, bobSignedSignature));

    // No-Kyber 8-arg ctor → classic v3 bundle.
    return new PreKeyBundle(bobStore.getLocalRegistrationId(), 1,
                            preKeyId, bobPreKeyPair.getPublicKey(),
                            signedPreKeyId, bobSignedPreKeyPair.getPublicKey(), bobSignedSignature,
                            bobStore.getIdentityKeyPair().getPublicKey());
  }
}
