package org.smssecure.smssecure.crypto.storage;

import android.content.Context;

import org.smssecure.smssecure.crypto.IdentityKeyUtil;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.database.DatabaseFactory;
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
      long recipientId = RecipientFactory.getRecipientsFromString(context, address.getName(), true).getPrimaryRecipient().getRecipientId();
      DatabaseFactory.getIdentityDatabase(context).saveIdentity(masterSecret, recipientId, identityKey);
      // Silence tracks identity changes via IdentityDatabase / isTrustedIdentity, not this return value.
      return IdentityChange.NEW_OR_UNCHANGED;
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
