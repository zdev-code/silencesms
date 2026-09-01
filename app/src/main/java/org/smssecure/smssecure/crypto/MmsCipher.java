package org.smssecure.smssecure.crypto;

import android.content.Context;
import android.util.Log;

import org.smssecure.smssecure.mms.TextTransport;
import org.smssecure.smssecure.protocol.WirePrefix;
import org.smssecure.smssecure.recipients.RecipientFormattingException;
import org.smssecure.smssecure.transport.UndeliverableMessageException;
import org.smssecure.smssecure.util.Util;
import org.smssecure.smssecure.crypto.storage.SilenceSignalProtocolStore;
import org.signal.libsignal.protocol.DuplicateMessageException;
import org.whispersystems.libsignal.InvalidKeyException;
import org.signal.libsignal.protocol.InvalidMessageException;
import org.signal.libsignal.protocol.LegacyMessageException;
import org.signal.libsignal.protocol.NoSessionException;
import org.whispersystems.libsignal.UntrustedIdentityException;
import java.util.Optional;

import java.io.IOException;

import com.google.android.mms.ContentType;
import com.google.android.mms.pdu_alt.EncodedStringValue;
import com.google.android.mms.pdu_alt.MultimediaMessagePdu;
import com.google.android.mms.pdu_alt.PduBody;
import com.google.android.mms.pdu_alt.PduComposer;
import com.google.android.mms.pdu_alt.PduParser;
import com.google.android.mms.pdu_alt.PduPart;
import com.google.android.mms.pdu_alt.RetrieveConf;
import com.google.android.mms.pdu_alt.SendReq;

public class MmsCipher {

  private static final String TAG = MmsCipher.class.getSimpleName();

  private final TextTransport textTransport = new TextTransport();
  private final SilenceSignalProtocolStore axolotlStore;

  public MmsCipher(Context context, MasterSecret masterSecret, int subscriptionId) {
    this.axolotlStore = new SilenceSignalProtocolStore(context, masterSecret, subscriptionId);
  }

  public MultimediaMessagePdu decrypt(Context context, MultimediaMessagePdu pdu)
      throws InvalidMessageException, LegacyMessageException, DuplicateMessageException,
             NoSessionException, UntrustedIdentityException
  {
    try {
      org.signal.libsignal.protocol.SessionCipher sessionCipher = new org.signal.libsignal.protocol.SessionCipher(axolotlStore, new org.signal.libsignal.protocol.SignalProtocolAddress(pdu.getFrom().getString(), 1));
      Optional<byte[]> ciphertext = getEncryptedData(pdu);

      if (!ciphertext.isPresent()) {
        throw new InvalidMessageException("No ciphertext present!");
      }

      byte[] decodedCiphertext = textTransport.getDecodedMessage(ciphertext.get());
      byte[] plaintext;

      if (decodedCiphertext == null) {
        throw new InvalidMessageException("failed to decode ciphertext");
      }

      try {
        plaintext = sessionCipher.decrypt(new org.signal.libsignal.protocol.message.SignalMessage(decodedCiphertext));
      } catch (org.signal.libsignal.protocol.InvalidMessageException e) {
        // NOTE - For some reason, Sprint seems to append a single character to the
        // end of message text segments.  I don't know why, so here we just try
        // truncating the message by one if the MAC fails.
        if (ciphertext.get().length > 2) {
          Log.w(TAG, "Attempting truncated decrypt...");
          byte[] truncated = Util.trim(ciphertext.get(), ciphertext.get().length - 1);
          decodedCiphertext = textTransport.getDecodedMessage(truncated);
          plaintext = sessionCipher.decrypt(new org.signal.libsignal.protocol.message.SignalMessage(decodedCiphertext));
        } else {
          throw e;
        }
      }

      return (MultimediaMessagePdu) new PduParser(plaintext).parse();
    } catch (org.signal.libsignal.protocol.UntrustedIdentityException e) {
      throw toVendored(e);
    } catch (org.signal.libsignal.protocol.InvalidVersionException | org.signal.libsignal.protocol.InvalidKeyException e) {
      throw new InvalidMessageException(e);
    } catch (IOException e) {
      throw new InvalidMessageException(e);
    }
  }

  public SendReq encrypt(Context context, SendReq message)
      throws NoSessionException, RecipientFormattingException, UndeliverableMessageException,
             UntrustedIdentityException
  {
    EncodedStringValue[] encodedRecipient = message.getTo();
    String               recipientString  = encodedRecipient[0].getString();
    byte[]               pduBytes         = new PduComposer(context, message).make();

    if (pduBytes == null) {
      throw new UndeliverableMessageException("PDU composition failed, null payload");
    }

    org.signal.libsignal.protocol.SignalProtocolAddress address = new org.signal.libsignal.protocol.SignalProtocolAddress(recipientString, 1);

    if (!axolotlStore.containsSession(address)) {
      throw new NoSessionException("No session for: " + recipientString);
    }

    try {
      org.signal.libsignal.protocol.SessionCipher             cipher            = new org.signal.libsignal.protocol.SessionCipher(axolotlStore, address);
      org.signal.libsignal.protocol.message.CiphertextMessage ciphertextMessage = cipher.encrypt(pduBytes);
      byte[]                                                  encryptedPduBytes = textTransport.getEncodedMessage(ciphertextMessage.serialize());

      PduBody body         = new PduBody();
      PduPart part         = new PduPart();

      part.setContentId((System.currentTimeMillis()+"").getBytes());
      part.setContentType(ContentType.TEXT_PLAIN.getBytes());
      part.setName((System.currentTimeMillis()+"").getBytes());
      part.setData(encryptedPduBytes);
      body.addPart(part);
      message.setSubject(new EncodedStringValue(WirePrefix.calculateEncryptedMmsSubject()));
      message.setBody(body);

      return message;
    } catch (org.signal.libsignal.protocol.NoSessionException e) {
      throw new NoSessionException(e.getMessage());
    } catch (org.signal.libsignal.protocol.UntrustedIdentityException e) {
      throw toVendored(e);
    }
  }


  private Optional<byte[]> getEncryptedData(MultimediaMessagePdu pdu) {
    for (int i=0;i<pdu.getBody().getPartsNum();i++) {
      if (new String(pdu.getBody().getPart(i).getContentType()).equals(ContentType.TEXT_PLAIN)) {
        return Optional.of(pdu.getBody().getPart(i).getData());
      }
    }

    return Optional.empty();
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
