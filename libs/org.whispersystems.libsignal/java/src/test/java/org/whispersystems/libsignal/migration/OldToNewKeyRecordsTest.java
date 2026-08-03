package org.whispersystems.libsignal.migration;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Store-rewrite prerequisite: the live store rewrite
 * bridges to the new library by reading the EXISTING on-disk records (vendored
 * {@code org.whispersystems.libsignal} serialized bytes, MasterCipher-decrypted) and reconstructing
 * the maintained {@code org.signal.libsignal.protocol} equivalents. That only works if the serialized
 * forms are byte-compatible across the two libraries.
 *
 * <p>{@code OldToNewSessionRecordTest} already proved this for {@code SessionRecord}. This test extends
 * the proof to the remaining record types the stores must bridge: {@code PreKeyRecord},
 * {@code SignedPreKeyRecord}, {@code IdentityKeyPair}, and {@code IdentityKey}. If any of these failed
 * to deserialize, the corresponding store would need to REGENERATE rather than bridge.
 */
public class OldToNewKeyRecordsTest {

  @Test
  public void oldPreKeyRecordDeserializesUnderNewLibrary() throws Exception {
    org.whispersystems.libsignal.ecc.ECKeyPair oldPair = org.whispersystems.libsignal.ecc.Curve.generateKeyPair();
    org.whispersystems.libsignal.state.PreKeyRecord oldRecord =
        new org.whispersystems.libsignal.state.PreKeyRecord(5, oldPair);
    byte[] serialized = oldRecord.serialize();

    org.signal.libsignal.protocol.state.PreKeyRecord newRecord =
        new org.signal.libsignal.protocol.state.PreKeyRecord(serialized);

    assertEquals(oldRecord.getId(), newRecord.getId());
    assertArrayEquals(oldPair.getPublicKey().serialize(), newRecord.getKeyPair().getPublicKey().serialize());
  }

  @Test
  public void oldSignedPreKeyRecordDeserializesUnderNewLibrary() throws Exception {
    org.whispersystems.libsignal.IdentityKeyPair oldIdentity = generateOldIdentity();
    org.whispersystems.libsignal.ecc.ECKeyPair   oldSignedPair = org.whispersystems.libsignal.ecc.Curve.generateKeyPair();
    byte[] signature = org.whispersystems.libsignal.ecc.Curve.calculateSignature(
        oldIdentity.getPrivateKey(), oldSignedPair.getPublicKey().serialize());

    org.whispersystems.libsignal.state.SignedPreKeyRecord oldRecord =
        new org.whispersystems.libsignal.state.SignedPreKeyRecord(7, System.currentTimeMillis(), oldSignedPair, signature);
    byte[] serialized = oldRecord.serialize();

    org.signal.libsignal.protocol.state.SignedPreKeyRecord newRecord =
        new org.signal.libsignal.protocol.state.SignedPreKeyRecord(serialized);

    assertEquals(oldRecord.getId(), newRecord.getId());
    assertEquals(oldRecord.getTimestamp(), newRecord.getTimestamp());
    assertArrayEquals(oldSignedPair.getPublicKey().serialize(), newRecord.getKeyPair().getPublicKey().serialize());
    assertArrayEquals(signature, newRecord.getSignature());
  }

  @Test
  public void oldIdentityKeyPairAndKeyDeserializeUnderNewLibrary() throws Exception {
    org.whispersystems.libsignal.IdentityKeyPair oldIdentity = generateOldIdentity();

    // IdentityKeyPair round-trip (the long-term key that safety numbers + existing sessions depend on).
    org.signal.libsignal.protocol.IdentityKeyPair newIdentity =
        new org.signal.libsignal.protocol.IdentityKeyPair(oldIdentity.serialize());
    assertArrayEquals(oldIdentity.serialize(), newIdentity.serialize());

    // IdentityKey (public) round-trip in both directions, since the store bridges both ways
    // (session layer -> new IdentityKey; identity DB -> vendored IdentityKey).
    byte[] oldPublic = oldIdentity.getPublicKey().serialize();
    org.signal.libsignal.protocol.IdentityKey newPublic =
        new org.signal.libsignal.protocol.IdentityKey(oldPublic, 0);
    assertArrayEquals(oldPublic, newPublic.serialize());

    org.whispersystems.libsignal.IdentityKey backToOld =
        new org.whispersystems.libsignal.IdentityKey(newPublic.serialize(), 0);
    assertArrayEquals(oldPublic, backToOld.serialize());
  }

  private static org.whispersystems.libsignal.IdentityKeyPair generateOldIdentity() {
    org.whispersystems.libsignal.ecc.ECKeyPair keyPair = org.whispersystems.libsignal.ecc.Curve.generateKeyPair();
    return new org.whispersystems.libsignal.IdentityKeyPair(
        new org.whispersystems.libsignal.IdentityKey(keyPair.getPublicKey()), keyPair.getPrivateKey());
  }
}
