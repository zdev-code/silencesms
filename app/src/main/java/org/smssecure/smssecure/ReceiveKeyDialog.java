/**
 * Copyright (C) 2011 Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.smssecure.smssecure;

import android.content.Context;
import android.content.DialogInterface;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.database.DatabaseFactory;
import org.smssecure.smssecure.database.EncryptingSmsDatabase;
import org.smssecure.smssecure.database.IdentityDatabase;
import org.smssecure.smssecure.database.documents.IdentityKeyMismatch;
import org.smssecure.smssecure.database.model.MessageRecord;
import org.smssecure.smssecure.domain.identity.ConflictIdentityStore;
import org.smssecure.smssecure.jobs.SmsDecryptJob;
import org.smssecure.smssecure.protocol.KeyExchangeMessage;
import org.smssecure.smssecure.sms.IncomingIdentityUpdateMessage;
import org.smssecure.smssecure.sms.IncomingKeyExchangeMessage;
import org.smssecure.smssecure.sms.IncomingPreKeyBundleMessage;
import org.smssecure.smssecure.sms.IncomingTextMessage;
import org.smssecure.smssecure.util.Base64;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.InvalidMessageException;
import org.whispersystems.libsignal.InvalidVersionException;
import org.whispersystems.libsignal.LegacyMessageException;
import org.whispersystems.libsignal.protocol.PreKeySignalMessage;
import org.signal.libsignal.protocol.IdentityKey;

import java.io.IOException;

/**
 * Activity for displaying sent/received session keys.
 *
 * @author Moxie Marlinspike
 */

public class ReceiveKeyDialog extends AlertDialog {
  private static final String TAG = ReceiveKeyDialog.class.getSimpleName();

  private OnClickListener callback;

  public ReceiveKeyDialog(@NonNull Context context,
                          @NonNull MasterSecret masterSecret,
                          @NonNull MessageRecord messageRecord)
  {
    this(context, masterSecret, messageRecord, null);
  }

  public ReceiveKeyDialog(@NonNull Context context,
                          @NonNull MasterSecret masterSecret,
                          @NonNull MessageRecord messageRecord,
                          @Nullable IdentityKeyMismatch mismatch)
  {
    super(context);

    try{
      final IncomingKeyExchangeMessage message = getMessage(messageRecord);
      final IdentityKey identityKey = mismatch == null ? getIdentityKey(message) : mismatch.getIdentityKey();
      final long recipientId = mismatch == null ? messageRecord.getIndividualRecipient().getRecipientId()
                                                : mismatch.getRecipientId();

      if (isTrusted(DatabaseFactory.getIdentityDatabase(context), masterSecret, recipientId, identityKey)){
        setMessage(context.getString(R.string.ReceiveKeyActivity_the_signature_on_this_key_exchange_is_trusted_but));
      } else {
        setUntrustedText(messageRecord, identityKey, recipientId);
      }

      setButton(AlertDialog.BUTTON_POSITIVE, context.getString(R.string.receive_key_activity__complete), new AcceptListener(masterSecret, messageRecord, message, identityKey, mismatch));
      setButton(AlertDialog.BUTTON_NEGATIVE, context.getString(android.R.string.cancel), new CancelListener());

    } catch (InvalidKeyException | InvalidVersionException | InvalidMessageException | LegacyMessageException e) {
      throw new AssertionError(e);
    }

  }

  @Override
  public void show() {
    super.show();
    ((TextView)this.findViewById(android.R.id.message))
            .setMovementMethod(LinkMovementMethod.getInstance());
  }

  public void setCallback(OnClickListener callback) {
    this.callback = callback;
  }

  private void setUntrustedText(final MessageRecord messageRecord,
                                final IdentityKey identityKey,
                                final long recipientId) {
    String          introText       = getContext().getString(R.string.ReceiveKeyActivity_the_signature_on_this_key_exchange_is_different);
    SpannableString spannableString = new SpannableString(introText + " " +
                                                          getContext().getString(R.string.ConfirmIdentityDialog_you_may_wish_to_verify_this_contact));
    spannableString.setSpan(new ClickableSpan() {
                              @Override
                              public void onClick(View widget) {
                                try {
                                  String token = ConflictIdentityStore.getInstance().put(
                                      VerifyIdentityFragment.CONFLICT_OWNER,
                                      recipientId,
                                      messageRecord.getSubscriptionId(), identityKey);
                                  getContext().startActivity(
                                      HostNavigationCommand.createConflictVerifyIdentityIntent(
                                          getContext(), token));
                                } catch (ConflictIdentityStore.InvalidPayloadException error) {
                                  Log.w(TAG, "Unable to open conflict identity verification");
                                }
                              }
                            }, introText.length() + 1,
            spannableString.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

    setMessage(spannableString);
  }

  static boolean isTrusted(IdentityDatabase identityDatabase,
                           MasterSecret masterSecret,
                           long recipientId,
                           IdentityKey identityKey) {
    return identityDatabase.isValidIdentity(masterSecret, recipientId, identityKey);
  }

  private static IncomingKeyExchangeMessage getMessage(MessageRecord messageRecord)
      throws InvalidKeyException, InvalidVersionException,
             InvalidMessageException, LegacyMessageException
  {
    IncomingTextMessage message = new IncomingTextMessage(messageRecord.getIndividualRecipient().getNumber(),
                                                          messageRecord.getRecipientDeviceId(),
                                                          System.currentTimeMillis(),
                                                          messageRecord.getBody().getBody(),
                                                          messageRecord.getSubscriptionId());

    if (messageRecord.isBundleKeyExchange()) {
      return new IncomingPreKeyBundleMessage(message, message.getMessageBody());
    } else if (messageRecord.isIdentityUpdate()) {
      return new IncomingIdentityUpdateMessage(message, message.getMessageBody());
    } else {
      return new IncomingKeyExchangeMessage(message, message.getMessageBody());
    }
  }

  private static IdentityKey getIdentityKey(IncomingKeyExchangeMessage message)
          throws InvalidKeyException, InvalidVersionException,
          InvalidMessageException, LegacyMessageException
  {
    try {
      if (message.isIdentityUpdate()) {
        return toNew(new org.whispersystems.libsignal.IdentityKey(Base64.decodeWithoutPadding(message.getMessageBody()), 0));
      } else if (message.isPreKeyBundle()) {
        return toNew(new PreKeySignalMessage(Base64.decodeWithoutPadding(message.getMessageBody())).getIdentityKey());
      } else {
        return toNew(new KeyExchangeMessage(Base64.decodeWithoutPadding(message.getMessageBody())).getIdentityKey());
      }
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  // ReceiveKeyDialog is part of the vendored Key-Exchange trust UI, so it works in vendored
  // IdentityKey; the app's IdentityDatabase and conflict handoff are maintained-library typed,
  // so convert at those seams (serialization is byte-identical across the two libraries).
  private static org.signal.libsignal.protocol.IdentityKey toNew(org.whispersystems.libsignal.IdentityKey vendored) {
    try {
      return new org.signal.libsignal.protocol.IdentityKey(vendored.serialize(), 0);
    } catch (org.signal.libsignal.protocol.InvalidKeyException e) {
      throw new AssertionError(e);
    }
  }

  private class CancelListener implements OnClickListener {
    @Override
    public void onClick(DialogInterface dialog, int which) {
      if (callback != null) callback.onClick(null, 0);
    }
  }

  private class AcceptListener implements OnClickListener {

    private MasterSecret                masterSecret;
    private MessageRecord               messageRecord;
    private IncomingKeyExchangeMessage  message;
    private IdentityKey                 identityKey;
    private IdentityKeyMismatch         mismatch;

    private AcceptListener(MasterSecret masterSecret,
                           MessageRecord messageRecord,
                           IncomingKeyExchangeMessage message,
                           IdentityKey identityKey,
                           IdentityKeyMismatch mismatch)
    {
      this.masterSecret  = masterSecret;
      this.messageRecord = messageRecord;
      this.message       = message;
      this.identityKey   = identityKey;
      this.mismatch      = mismatch;
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
      Context appContext = getContext().getApplicationContext();
      MasterSecret currentMasterSecret = masterSecret;
      long recipientId = mismatch == null ? messageRecord.getIndividualRecipient().getRecipientId()
                  : mismatch.getRecipientId();
      long messageId = messageRecord.getId();
      IdentityKey currentIdentityKey = identityKey;
      boolean identityUpdate = message.isIdentityUpdate();
      IdentityKeyMismatch currentMismatch = mismatch;

      AppTaskExecutor.getInstance().submitSerial(
          () -> {
            acceptKey(appContext, currentMasterSecret, recipientId, messageId,
                      currentIdentityKey, identityUpdate, currentMismatch);
            return null;
          },
          ignored -> {},
          exception -> Log.w(TAG, "Unable to accept received key", exception));

      if (callback != null) callback.onClick(null, 0);
    }
  }

  static void acceptKey(Context context,
                        MasterSecret masterSecret,
                        long recipientId,
                        long messageId,
                        IdentityKey identityKey,
                        boolean identityUpdate,
                        IdentityKeyMismatch mismatch)
  {
    IdentityDatabase identityDatabase = DatabaseFactory.getIdentityDatabase(context);
    EncryptingSmsDatabase smsDatabase = DatabaseFactory.getEncryptingSmsDatabase(context);

    identityDatabase.saveIdentity(masterSecret, recipientId, identityKey);

    if (mismatch != null) {
      smsDatabase.removeMismatchedIdentity(messageId, mismatch.getRecipientId(), identityKey);
      smsDatabase.notifyMessageStateChanged(messageId);
    }

    if (identityUpdate) {
      smsDatabase.markAsProcessedKeyExchange(messageId);
    } else {
      ApplicationContext.getInstance(context)
                        .getJobManager()
                        .add(new SmsDecryptJob(context, messageId, true, false));
    }
  }
}
