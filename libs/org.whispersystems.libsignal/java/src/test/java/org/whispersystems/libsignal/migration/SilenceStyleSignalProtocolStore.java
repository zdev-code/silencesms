package org.whispersystems.libsignal.migration;

import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.InvalidKeyIdException;
import org.signal.libsignal.protocol.InvalidMessageException;
import org.signal.libsignal.protocol.NoSessionException;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord;
import org.signal.libsignal.protocol.state.KyberPreKeyRecord;
import org.signal.libsignal.protocol.state.PreKeyRecord;
import org.signal.libsignal.protocol.state.SessionRecord;
import org.signal.libsignal.protocol.state.SignalProtocolStore;
import org.signal.libsignal.protocol.state.SignedPreKeyRecord;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Store-adapter design proof.
 *
 * <p>This is a faithful, in-memory stand-in for the real {@code SilenceSignalProtocolStore} rewritten
 * against the maintained {@code org.signal.libsignal.protocol} API at 0.72.1. It deliberately mirrors
 * the production design decisions so they can be validated before any live code is touched:
 *
 * <ul>
 *   <li><strong>Sessions are persisted as serialized bytes</strong> and re-deserialized via
 *       {@code new SessionRecord(bytes)} on <em>every</em> {@link #loadSession} — exactly what the real
 *       {@code SilenceSessionStore} does through {@code MasterCipher} on disk (store
 *       {@code record.serialize()}, reload with {@code new SessionRecord(serialized)}). Driving a live
 *       multi-message exchange through this proves v3 ratchet state survives repeated serialize /
 *       deserialize cycles — the single biggest migration risk.</li>
 *   <li><strong>{@code KyberPreKeyStore} is a production no-op</strong> (Silence issues no Kyber
 *       prekeys; the v3 path provably never calls it — see
 *       {@code NewLibraryV3RoundTripTest#noOpKyberStoreKeepsV3SessionEndToEnd}).</li>
 *   <li><strong>{@code SenderKeyStore} is a no-op</strong> — sender keys are a group-messaging concept
 *       with no meaning for 1:1 SMS.</li>
 *   <li><strong>Identity store is trust-on-first-use</strong>, matching Silence's behaviour.</li>
 * </ul>
 *
 * <p>Not for production use: real persistence, locking, and {@code MasterCipher} encryption live in the
 * on-disk stores. This class exists only to lock down the new-API surface and semantics.
 */
final class SilenceStyleSignalProtocolStore implements SignalProtocolStore {

  private final IdentityKeyPair         identityKeyPair;
  private final int                     registrationId;
  private final Map<String, IdentityKey> trustedIdentities = new HashMap<>();

  // Sessions held as serialized bytes to force a serialize/deserialize round-trip on every access,
  // mirroring the MasterCipher-encrypted on-disk format of the real SilenceSessionStore.
  private final Map<String, byte[]>             sessions      = new HashMap<>();
  private final Map<Integer, PreKeyRecord>      preKeys       = new HashMap<>();
  private final Map<Integer, SignedPreKeyRecord> signedPreKeys = new HashMap<>();

  SilenceStyleSignalProtocolStore(IdentityKeyPair identityKeyPair, int registrationId) {
    this.identityKeyPair = identityKeyPair;
    this.registrationId  = registrationId;
  }

  // ---- IdentityKeyStore (trust-on-first-use) ----------------------------------------------------

  @Override
  public IdentityKeyPair getIdentityKeyPair() {
    return identityKeyPair;
  }

  @Override
  public int getLocalRegistrationId() {
    return registrationId;
  }

  @Override
  public IdentityChange saveIdentity(SignalProtocolAddress address, IdentityKey identityKey) {
    IdentityKey previous = trustedIdentities.put(address.toString(), identityKey);
    return (previous != null && !previous.equals(identityKey))
        ? IdentityChange.REPLACED_EXISTING
        : IdentityChange.NEW_OR_UNCHANGED;
  }

  @Override
  public boolean isTrustedIdentity(SignalProtocolAddress address, IdentityKey identityKey, Direction direction) {
    IdentityKey trusted = trustedIdentities.get(address.toString());
    return trusted == null || trusted.equals(identityKey);
  }

  @Override
  public IdentityKey getIdentity(SignalProtocolAddress address) {
    return trustedIdentities.get(address.toString());
  }

  // ---- SessionStore (serialized-bytes round-trip on every access) -------------------------------

  @Override
  public SessionRecord loadSession(SignalProtocolAddress address) {
    byte[] serialized = sessions.get(address.toString());
    if (serialized == null) {
      return new SessionRecord();
    }
    try {
      return new SessionRecord(serialized);
    } catch (InvalidMessageException e) {
      throw new AssertionError("stored v3 session failed to deserialize", e);
    }
  }

  @Override
  public List<SessionRecord> loadExistingSessions(List<SignalProtocolAddress> addresses) throws NoSessionException {
    List<SessionRecord> result = new ArrayList<>(addresses.size());
    for (SignalProtocolAddress address : addresses) {
      byte[] serialized = sessions.get(address.toString());
      if (serialized == null) {
        throw new NoSessionException("no session for " + address);
      }
      try {
        result.add(new SessionRecord(serialized));
      } catch (InvalidMessageException e) {
        throw new AssertionError(e);
      }
    }
    return result;
  }

  @Override
  public List<Integer> getSubDeviceSessions(String name) {
    List<Integer> devices = new LinkedList<>();
    String prefix = name + ".";
    for (String key : sessions.keySet()) {
      if (key.startsWith(prefix)) {
        try {
          int device = Integer.parseInt(key.substring(prefix.length()));
          if (device != 1) devices.add(device);
        } catch (NumberFormatException ignored) {
          // not a sub-device key
        }
      }
    }
    return devices;
  }

  @Override
  public void storeSession(SignalProtocolAddress address, SessionRecord record) {
    sessions.put(address.toString(), record.serialize());
  }

  @Override
  public boolean containsSession(SignalProtocolAddress address) {
    return sessions.containsKey(address.toString()) && loadSession(address).hasSenderChain();
  }

  @Override
  public void deleteSession(SignalProtocolAddress address) {
    sessions.remove(address.toString());
  }

  @Override
  public void deleteAllSessions(String name) {
    sessions.keySet().removeIf(key -> key.equals(name) || key.startsWith(name + "."));
  }

  // ---- PreKeyStore -------------------------------------------------------------------------------

  @Override
  public PreKeyRecord loadPreKey(int preKeyId) throws InvalidKeyIdException {
    PreKeyRecord record = preKeys.get(preKeyId);
    if (record == null) throw new InvalidKeyIdException("no prekey " + preKeyId);
    return record;
  }

  @Override
  public void storePreKey(int preKeyId, PreKeyRecord record) {
    preKeys.put(preKeyId, record);
  }

  @Override
  public boolean containsPreKey(int preKeyId) {
    return preKeys.containsKey(preKeyId);
  }

  @Override
  public void removePreKey(int preKeyId) {
    preKeys.remove(preKeyId);
  }

  // ---- SignedPreKeyStore -------------------------------------------------------------------------

  @Override
  public SignedPreKeyRecord loadSignedPreKey(int signedPreKeyId) throws InvalidKeyIdException {
    SignedPreKeyRecord record = signedPreKeys.get(signedPreKeyId);
    if (record == null) throw new InvalidKeyIdException("no signed prekey " + signedPreKeyId);
    return record;
  }

  @Override
  public List<SignedPreKeyRecord> loadSignedPreKeys() {
    return new ArrayList<>(signedPreKeys.values());
  }

  @Override
  public void storeSignedPreKey(int signedPreKeyId, SignedPreKeyRecord record) {
    signedPreKeys.put(signedPreKeyId, record);
  }

  @Override
  public boolean containsSignedPreKey(int signedPreKeyId) {
    return signedPreKeys.containsKey(signedPreKeyId);
  }

  @Override
  public void removeSignedPreKey(int signedPreKeyId) {
    signedPreKeys.remove(signedPreKeyId);
  }

  // ---- KyberPreKeyStore (production no-op: Silence never issues Kyber prekeys) -------------------

  @Override
  public KyberPreKeyRecord loadKyberPreKey(int kyberPreKeyId) throws InvalidKeyIdException {
    throw new InvalidKeyIdException("Silence stores no Kyber prekeys (v3 only)");
  }

  @Override
  public List<KyberPreKeyRecord> loadKyberPreKeys() {
    return new ArrayList<>();
  }

  @Override
  public void storeKyberPreKey(int kyberPreKeyId, KyberPreKeyRecord record) {
    // no-op: v3 sessions carry no Kyber prekey
  }

  @Override
  public boolean containsKyberPreKey(int kyberPreKeyId) {
    return false;
  }

  @Override
  public void markKyberPreKeyUsed(int kyberPreKeyId) {
    // no-op
  }

  // ---- SenderKeyStore (no-op: group messaging is not used over SMS) -----------------------------

  @Override
  public void storeSenderKey(SignalProtocolAddress sender, UUID distributionId, SenderKeyRecord record) {
    // no-op
  }

  @Override
  public SenderKeyRecord loadSenderKey(SignalProtocolAddress sender, UUID distributionId) {
    return null;
  }
}
