package org.smssecure.smssecure.crypto.storage;

import android.content.Context;

import org.smssecure.smssecure.crypto.MasterSecret;
import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.InvalidKeyIdException;
import org.signal.libsignal.protocol.NoSessionException;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord;
import org.signal.libsignal.protocol.state.KyberPreKeyRecord;
import org.signal.libsignal.protocol.state.PreKeyRecord;
import org.signal.libsignal.protocol.state.SessionRecord;
import org.signal.libsignal.protocol.state.SignalProtocolStore;
import org.signal.libsignal.protocol.state.SignedPreKeyRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * New-API ({@code org.signal.libsignal.protocol}) aggregate protocol store for the hybrid crypto
 * setup (maintained libsignal for message encrypt/decrypt, vendored library for Key Exchange).
 * Delegates identity / prekey /
 * signed-prekey / session to the file-backed {@code *V2} sub-stores (same on-disk data as the vendored
 * stores) and supplies a production <strong>no-op</strong> {@code KyberPreKeyStore} (Silence issues no
 * Kyber prekeys; the v3 path never touches it — proven by the migration tests) and a no-op {@code SenderKeyStore}
 * (no group messaging over SMS).
 */
public class SilenceSignalProtocolStore implements SignalProtocolStore {

  private final SilencePreKeyStore       preKeyStore;
  private final SilenceIdentityKeyStore  identityKeyStore;
  private final SilenceSessionStore      sessionStore;

  public SilenceSignalProtocolStore(Context context, MasterSecret masterSecret, int subscriptionId) {
    this.preKeyStore      = new SilencePreKeyStore(context, masterSecret, subscriptionId);
    this.identityKeyStore = new SilenceIdentityKeyStore(context, masterSecret, subscriptionId);
    this.sessionStore     = new SilenceSessionStore(context, masterSecret, subscriptionId);
  }

  // ---- IdentityKeyStore -------------------------------------------------------------------------

  @Override
  public IdentityKeyPair getIdentityKeyPair() {
    return identityKeyStore.getIdentityKeyPair();
  }

  @Override
  public int getLocalRegistrationId() {
    return identityKeyStore.getLocalRegistrationId();
  }

  @Override
  public IdentityChange saveIdentity(SignalProtocolAddress address, IdentityKey identityKey) {
    return identityKeyStore.saveIdentity(address, identityKey);
  }

  @Override
  public boolean isTrustedIdentity(SignalProtocolAddress address, IdentityKey identityKey, Direction direction) {
    return identityKeyStore.isTrustedIdentity(address, identityKey, direction);
  }

  @Override
  public IdentityKey getIdentity(SignalProtocolAddress address) {
    return identityKeyStore.getIdentity(address);
  }

  // ---- PreKeyStore ------------------------------------------------------------------------------

  @Override
  public PreKeyRecord loadPreKey(int preKeyId) throws InvalidKeyIdException {
    return preKeyStore.loadPreKey(preKeyId);
  }

  @Override
  public void storePreKey(int preKeyId, PreKeyRecord record) {
    preKeyStore.storePreKey(preKeyId, record);
  }

  @Override
  public boolean containsPreKey(int preKeyId) {
    return preKeyStore.containsPreKey(preKeyId);
  }

  @Override
  public void removePreKey(int preKeyId) {
    preKeyStore.removePreKey(preKeyId);
  }

  // ---- SessionStore -----------------------------------------------------------------------------

  @Override
  public SessionRecord loadSession(SignalProtocolAddress address) {
    return sessionStore.loadSession(address);
  }

  @Override
  public List<SessionRecord> loadExistingSessions(List<SignalProtocolAddress> addresses) throws NoSessionException {
    return sessionStore.loadExistingSessions(addresses);
  }

  @Override
  public List<Integer> getSubDeviceSessions(String name) {
    return sessionStore.getSubDeviceSessions(name);
  }

  @Override
  public void storeSession(SignalProtocolAddress address, SessionRecord record) {
    sessionStore.storeSession(address, record);
  }

  @Override
  public boolean containsSession(SignalProtocolAddress address) {
    return sessionStore.containsSession(address);
  }

  @Override
  public void deleteSession(SignalProtocolAddress address) {
    sessionStore.deleteSession(address);
  }

  @Override
  public void deleteAllSessions(String name) {
    sessionStore.deleteAllSessions(name);
  }

  // ---- SignedPreKeyStore ------------------------------------------------------------------------

  @Override
  public SignedPreKeyRecord loadSignedPreKey(int signedPreKeyId) throws InvalidKeyIdException {
    return preKeyStore.loadSignedPreKey(signedPreKeyId);
  }

  @Override
  public List<SignedPreKeyRecord> loadSignedPreKeys() {
    return preKeyStore.loadSignedPreKeys();
  }

  @Override
  public void storeSignedPreKey(int signedPreKeyId, SignedPreKeyRecord record) {
    preKeyStore.storeSignedPreKey(signedPreKeyId, record);
  }

  @Override
  public boolean containsSignedPreKey(int signedPreKeyId) {
    return preKeyStore.containsSignedPreKey(signedPreKeyId);
  }

  @Override
  public void removeSignedPreKey(int signedPreKeyId) {
    preKeyStore.removeSignedPreKey(signedPreKeyId);
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
