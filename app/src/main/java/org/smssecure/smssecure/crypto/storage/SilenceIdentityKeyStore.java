package org.smssecure.smssecure.crypto.storage;

import android.content.Context;

import org.smssecure.smssecure.crypto.IdentityKeyUtil;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.database.DatabaseFactory;
import org.smssecure.smssecure.database.IdentityDatabase;
import org.smssecure.smssecure.recipients.RecipientFactory;
import org.smssecure.smssecure.util.SilencePreferences;
import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.state.IdentityKeyStore;

/**
 * New-API ({@code org.signal.libsignal.protocol}) identity key store for the hybrid crypto setup
 * (maintained libsignal for message encrypt/decrypt, vendored library for Key Exchange). The app's
 * identity storage ({@link IdentityKeyUtil} + {@code IdentityDatabase}) is now maintained-library
 * typed, so this store passes identities straight through with no conversion.
 */
public class SilenceIdentityKeyStore implements IdentityKeyStore {

  private static final Object LOCK = new Object();

  private final Context      context;
  private final MasterSecret masterSecret;
  private final int          subscriptionId;

  public SilenceIdentityKeyStore(Context context, MasterSecret masterSecret, int subscriptionId) {
    this.context        = context;
    this.masterSecret   = masterSecret;
    this.subscriptionId = subscriptionId;
  }

  @Override
  public IdentityKeyPair getIdentityKeyPair() {
    return IdentityKeyUtil.getIdentityKeyPair(context, masterSecret, subscriptionId);
  }

  @Override
  public int getLocalRegistrationId() {
    return SilencePreferences.getLocalRegistrationId(context);
  }

  @Override
  public IdentityChange saveIdentity(SignalProtocolAddress address, IdentityKey identityKey) {
    synchronized (LOCK) {
      long             recipientId      = RecipientFactory.getRecipientsFromString(context, address.getName(), true).getPrimaryRecipient().getRecipientId();
      IdentityDatabase identityDatabase = DatabaseFactory.getIdentityDatabase(context);

      IdentityDatabase.StoredIdentity existing = identityDatabase.getStoredIdentity(masterSecret, recipientId);
      identityDatabase.saveIdentity(masterSecret, recipientId, identityKey);

      // Libsignal 0.72.1 requires REPLACED_EXISTING whenever a previously stored identity was
      // overwritten with a different key; returning NEW_OR_UNCHANGED in that case suppresses the
      // library's identity-change handling and can leave session state inconsistent after a peer
      // changes keys. First use and an unchanged re-save are both NEW_OR_UNCHANGED.
      switch (existing.getStatus()) {
        case PRESENT_VALID:
          return existing.getIdentityKey().equals(identityKey) ? IdentityChange.NEW_OR_UNCHANGED
                                                               : IdentityChange.REPLACED_EXISTING;
        case PRESENT_UNREADABLE:
          // A prior identity exists but cannot be verified; we cannot prove it is unchanged, so
          // surface a replacement rather than silently suppressing the identity-change signal.
          return IdentityChange.REPLACED_EXISTING;
        case NOT_PRESENT:
        default:
          return IdentityChange.NEW_OR_UNCHANGED;
      }
    }
  }

  @Override
  public boolean isTrustedIdentity(SignalProtocolAddress address, IdentityKey identityKey, Direction direction) {
    synchronized (LOCK) {
      switch (direction) {
        case SENDING:   return isTrustedIdentity(address, identityKey);
        case RECEIVING: return true;
        default:        throw new AssertionError("Unknown direction: " + direction);
      }
    }
  }

  private boolean isTrustedIdentity(SignalProtocolAddress address, IdentityKey identityKey) {
    long recipientId = RecipientFactory.getRecipientsFromString(context, address.getName(), true).getPrimaryRecipient().getRecipientId();
    return DatabaseFactory.getIdentityDatabase(context)
                          .isValidIdentity(masterSecret, recipientId, identityKey);
  }

  @Override
  public IdentityKey getIdentity(SignalProtocolAddress address) {
    return null;
  }
}
