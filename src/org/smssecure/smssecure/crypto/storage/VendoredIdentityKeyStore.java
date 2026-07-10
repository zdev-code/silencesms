package org.smssecure.smssecure.crypto.storage;

import android.content.Context;

import org.smssecure.smssecure.crypto.IdentityKeyUtil;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.database.DatabaseFactory;
import org.smssecure.smssecure.recipients.RecipientFactory;
import org.smssecure.smssecure.util.SilencePreferences;
import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.state.IdentityKeyStore;

public class VendoredIdentityKeyStore implements IdentityKeyStore {

  private static final Object LOCK = new Object();

  private final Context      context;
  private final MasterSecret masterSecret;
  private final int          subscriptionId;

  public VendoredIdentityKeyStore(Context context, MasterSecret masterSecret, int subscriptionId) {
    this.context        = context;
    this.masterSecret   = masterSecret;
    this.subscriptionId = subscriptionId;
  }

  @Override
  public IdentityKeyPair getIdentityKeyPair() {
    org.signal.libsignal.protocol.IdentityKeyPair current =
        IdentityKeyUtil.getIdentityKeyPair(context, masterSecret, subscriptionId);
    try {
      return new IdentityKeyPair(current.serialize());
    } catch (org.whispersystems.libsignal.InvalidKeyException e) {
      throw new AssertionError(e);
    }
  }

  @Override
  public int getLocalRegistrationId() {
    return SilencePreferences.getLocalRegistrationId(context);
  }

  @Override
  public boolean saveIdentity(SignalProtocolAddress address, IdentityKey identityKey) {
    synchronized (LOCK) {
      long recipientId = RecipientFactory.getRecipientsFromString(context, address.getName(), true).getPrimaryRecipient().getRecipientId();
      DatabaseFactory.getIdentityDatabase(context).saveIdentity(masterSecret, recipientId, toNew(identityKey));
      return true;
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

  public boolean isTrustedIdentity(SignalProtocolAddress address, IdentityKey identityKey) {
    long recipientId = RecipientFactory.getRecipientsFromString(context, address.getName(), true).getPrimaryRecipient().getRecipientId();
    return DatabaseFactory.getIdentityDatabase(context)
                          .isValidIdentity(masterSecret, recipientId, toNew(identityKey));
  }

  @Override
  public IdentityKey getIdentity(SignalProtocolAddress address) {
    return null;
  }

  // The app's IdentityDatabase is now maintained-library ({@code org.signal.libsignal.protocol})
  // typed; the vendored Key-Exchange path hands it identities in the vendored type, so convert here.
  private static org.signal.libsignal.protocol.IdentityKey toNew(IdentityKey identityKey) {
    try {
      return new org.signal.libsignal.protocol.IdentityKey(identityKey.serialize(), 0);
    } catch (org.signal.libsignal.protocol.InvalidKeyException e) {
      throw new AssertionError(e);
    }
  }
}
