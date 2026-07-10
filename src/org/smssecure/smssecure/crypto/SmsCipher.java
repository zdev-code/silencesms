package org.smssecure.smssecure.crypto;

import android.content.Context;

import org.smssecure.smssecure.recipients.Recipient;
import org.smssecure.smssecure.recipients.RecipientFactory;
import org.smssecure.smssecure.recipients.RecipientFormattingException;
import org.smssecure.smssecure.recipients.Recipients;
import org.smssecure.smssecure.sms.IncomingEncryptedMessage;
import org.smssecure.smssecure.sms.IncomingKeyExchangeMessage;
import org.smssecure.smssecure.sms.IncomingPreKeyBundleMessage;
import org.smssecure.smssecure.sms.IncomingTextMessage;
import org.smssecure.smssecure.sms.OutgoingKeyExchangeMessage;
import org.smssecure.smssecure.sms.OutgoingPrekeyBundleMessage;
import org.smssecure.smssecure.sms.OutgoingTextMessage;
import org.smssecure.smssecure.sms.SmsTransportDetails;
import org.smssecure.smssecure.crypto.SessionBuilder;
import org.smssecure.smssecure.crypto.storage.KeyExchangeSessionStore;
import org.smssecure.smssecure.crypto.storage.VendoredSessionStore;
import org.smssecure.smssecure.crypto.storage.VendoredSignalProtocolStore;
import org.smssecure.smssecure.crypto.storage.SilenceSignalProtocolStore;
import org.smssecure.smssecure.protocol.KeyExchangeMessage;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.DuplicateMessageException;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.InvalidKeyIdException;
import org.whispersystems.libsignal.InvalidMessageException;
import org.whispersystems.libsignal.InvalidVersionException;
import org.whispersystems.libsignal.LegacyMessageException;
import org.whispersystems.libsignal.NoSessionException;
import org.whispersystems.libsignal.StaleKeyExchangeException;
import org.whispersystems.libsignal.UntrustedIdentityException;
import org.whispersystems.libsignal.state.SignalProtocolStore;

import java.io.IOException;
import java.lang.IllegalArgumentException;
import java.lang.NullPointerException;

public class SmsCipher {

  private final SmsTransportDetails transportDetails = new SmsTransportDetails();

  // Maintained-library store for message encrypt/decrypt (new SessionCipher).
  private final SilenceSignalProtocolStore store;
  // Vendored store retained only for the Key-Exchange establishment path.
  private final SignalProtocolStore          vendoredStore;

  // Retained so the Key-Exchange path can build an isolated, transitional session store and
  // publish completed sessions into the shared sessions-v2 directory.
  private final Context      context;
  private final MasterSecret masterSecret;
  private final int          subscriptionId;

  public SmsCipher(Context context, MasterSecret masterSecret, int subscriptionId) {
    this.context        = context.getApplicationContext();
    this.masterSecret   = masterSecret;
    this.subscriptionId = subscriptionId;
    this.store          = new SilenceSignalProtocolStore(context, masterSecret, subscriptionId);
    this.vendoredStore  = new VendoredSignalProtocolStore(context, masterSecret, subscriptionId);
  }

  public IncomingTextMessage decrypt(Context context, IncomingTextMessage message)
      throws LegacyMessageException, InvalidMessageException, DuplicateMessageException,
             NoSessionException, UntrustedIdentityException
  {
    try {
      byte[]                                              decoded       = transportDetails.getDecodedMessage(message.getMessageBody().getBytes());
      org.signal.libsignal.protocol.message.SignalMessage signalMessage = new org.signal.libsignal.protocol.message.SignalMessage(decoded);
      org.signal.libsignal.protocol.SessionCipher         sessionCipher = new org.signal.libsignal.protocol.SessionCipher(store, new org.signal.libsignal.protocol.SignalProtocolAddress(message.getSender(), 1));
      byte[]                                              padded        = sessionCipher.decrypt(signalMessage);
      byte[]                                              plaintext     = transportDetails.getStrippedPaddingMessageBody(padded);

      if (message.isEndSession() && "TERMINATE".equals(new String(plaintext))) {
        store.deleteSession(new org.signal.libsignal.protocol.SignalProtocolAddress(message.getSender(), 1));
      }

      return message.withMessageBody(new String(plaintext));
    } catch (org.signal.libsignal.protocol.NoSessionException e) {
      throw new NoSessionException(e);
    } catch (org.signal.libsignal.protocol.DuplicateMessageException e) {
      throw new DuplicateMessageException(e.getMessage());
    } catch (org.signal.libsignal.protocol.LegacyMessageException e) {
      throw new LegacyMessageException(e.getMessage());
    } catch (org.signal.libsignal.protocol.UntrustedIdentityException e) {
      throw toVendored(e);
    } catch (org.signal.libsignal.protocol.InvalidVersionException | org.signal.libsignal.protocol.InvalidKeyException e) {
      throw new InvalidMessageException(e);
    } catch (org.signal.libsignal.protocol.InvalidMessageException e) {
      throw new InvalidMessageException(e);
    } catch (IOException | IllegalArgumentException | NullPointerException e) {
      throw new InvalidMessageException(e);
    }
  }

  public IncomingEncryptedMessage decrypt(Context context, IncomingPreKeyBundleMessage message)
      throws InvalidVersionException, InvalidMessageException, DuplicateMessageException,
             UntrustedIdentityException, LegacyMessageException
  {
    try {
      byte[]                                                    decoded       = transportDetails.getDecodedMessage(message.getMessageBody().getBytes());
      org.signal.libsignal.protocol.message.PreKeySignalMessage preKeyMessage = new org.signal.libsignal.protocol.message.PreKeySignalMessage(decoded);
      org.signal.libsignal.protocol.SessionCipher              sessionCipher = new org.signal.libsignal.protocol.SessionCipher(store, new org.signal.libsignal.protocol.SignalProtocolAddress(message.getSender(), 1));
      byte[]                                                    padded        = sessionCipher.decrypt(preKeyMessage);
      byte[]                                                    plaintext     = transportDetails.getStrippedPaddingMessageBody(padded);

      return new IncomingEncryptedMessage(message, new String(plaintext));
    } catch (org.signal.libsignal.protocol.DuplicateMessageException e) {
      throw new DuplicateMessageException(e.getMessage());
    } catch (org.signal.libsignal.protocol.LegacyMessageException e) {
      throw new LegacyMessageException(e.getMessage());
    } catch (org.signal.libsignal.protocol.InvalidVersionException e) {
      throw new InvalidVersionException(e.getMessage());
    } catch (org.signal.libsignal.protocol.UntrustedIdentityException e) {
      throw toVendored(e);
    } catch (org.signal.libsignal.protocol.InvalidKeyException | org.signal.libsignal.protocol.InvalidKeyIdException e) {
      throw new InvalidMessageException(e);
    } catch (org.signal.libsignal.protocol.InvalidMessageException e) {
      throw new InvalidMessageException(e);
    } catch (IOException e) {
      throw new InvalidMessageException(e);
    }
  }

  public OutgoingTextMessage encrypt(OutgoingTextMessage message)
    throws NoSessionException, UntrustedIdentityException
  {
    byte[] paddedBody      = transportDetails.getPaddedMessageBody(message.getMessageBody().getBytes());
    String recipientNumber = message.getRecipients().getPrimaryRecipient().getNumber();

    org.signal.libsignal.protocol.SignalProtocolAddress address = new org.signal.libsignal.protocol.SignalProtocolAddress(recipientNumber, 1);

    if (!store.containsSession(address)) {
      throw new NoSessionException("No session for: " + recipientNumber);
    }

    try {
      org.signal.libsignal.protocol.SessionCipher             cipher            = new org.signal.libsignal.protocol.SessionCipher(store, address);
      org.signal.libsignal.protocol.message.CiphertextMessage ciphertextMessage = cipher.encrypt(paddedBody);
      String                                                  encodedCiphertext = new String(transportDetails.getEncodedMessage(ciphertextMessage.serialize()));

      if (ciphertextMessage.getType() == org.signal.libsignal.protocol.message.CiphertextMessage.PREKEY_TYPE) {
        return new OutgoingPrekeyBundleMessage(message, encodedCiphertext);
      } else {
        return message.withBody(encodedCiphertext);
      }
    } catch (org.signal.libsignal.protocol.NoSessionException e) {
      throw new NoSessionException(e);
    } catch (org.signal.libsignal.protocol.UntrustedIdentityException e) {
      throw toVendored(e);
    }
  }

  public OutgoingKeyExchangeMessage process(Context context, IncomingKeyExchangeMessage message)
      throws UntrustedIdentityException, StaleKeyExchangeException,
             InvalidVersionException, LegacyMessageException, InvalidMessageException
  {
    try {
      Recipients            recipients            = RecipientFactory.getRecipientsFromString(context, message.getSender(), false);
      SignalProtocolAddress signalProtocolAddress = new SignalProtocolAddress(message.getSender(), 1);
      KeyExchangeMessage    exchangeMessage       = new KeyExchangeMessage(transportDetails.getDecodedMessage(message.getMessageBody().getBytes()));

      // Drive the handshake through an isolated store so the transitional pendingKeyExchange
      // state (a vendored-only field) is never clobbered by the libsignal cipher, which shares
      // the sessions-v2 files. Identity/pre-key material still comes from the vendored store.
      KeyExchangeSessionStore kexSessionStore = new KeyExchangeSessionStore(this.context, this.masterSecret, this.subscriptionId);
      SessionBuilder          sessionBuilder  = new SessionBuilder(kexSessionStore, vendoredStore, vendoredStore, vendoredStore, signalProtocolAddress);

      KeyExchangeMessage response        = sessionBuilder.process(exchangeMessage);

      // Once the handshake yields a fully established session, publish it into the shared
      // sessions-v2 store so the libsignal SessionCipher can encrypt/decrypt with it, then
      // drop the transitional copy.
      if (kexSessionStore.containsSession(signalProtocolAddress)) {
        VendoredSessionStore sharedStore = new VendoredSessionStore(this.context, this.masterSecret, this.subscriptionId);
        sharedStore.storeSession(signalProtocolAddress, kexSessionStore.loadSession(signalProtocolAddress));
        kexSessionStore.deleteSession(signalProtocolAddress);
      }

      if (response != null) {
        byte[] serializedResponse = transportDetails.getEncodedMessage(response.serialize());
        return new OutgoingKeyExchangeMessage(recipients, new String(serializedResponse), message.getSubscriptionId());
      } else {
        return null;
      }
    } catch (IOException | InvalidKeyException e) {
      throw new InvalidMessageException(e);
    }
  }

  private static UntrustedIdentityException toVendored(org.signal.libsignal.protocol.UntrustedIdentityException e) {
    org.signal.libsignal.protocol.IdentityKey untrusted = e.getUntrustedIdentity();
    org.whispersystems.libsignal.IdentityKey  vendored  = null;
    if (untrusted != null) {
      try {
        vendored = new org.whispersystems.libsignal.IdentityKey(untrusted.serialize(), 0);
      } catch (InvalidKeyException ike) {
        throw new AssertionError(ike);
      }
    }
    return new UntrustedIdentityException(e.getName(), vendored);
  }

}
