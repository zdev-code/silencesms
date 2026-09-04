package org.smssecure.smssecure.data.message;

import android.content.Context;
import android.database.Cursor;

import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.database.DatabaseFactory;
import org.smssecure.smssecure.database.EncryptingSmsDatabase;
import org.smssecure.smssecure.database.MmsDatabase;
import org.smssecure.smssecure.database.MmsSmsDatabase;
import org.smssecure.smssecure.database.SmsDatabase;
import org.smssecure.smssecure.database.model.MessageRecord;
import org.smssecure.smssecure.domain.conversation.ConversationUnlockCapability;
import org.smssecure.smssecure.recipients.Recipients;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor.TaskHandle;

import java.util.Objects;

public final class DefaultMessageDetailsRepository implements MessageDetailsRepository {
  private final Context context;
  private final AppTaskExecutor executor;

  public DefaultMessageDetailsRepository(Context context, AppTaskExecutor executor) {
    this.context = Objects.requireNonNull(context).getApplicationContext();
    this.executor = Objects.requireNonNull(executor);
  }

  @Override
  public TaskHandle load(String transport, long messageId,
                         ConversationUnlockCapability unlockCapability, Callback callback) {
    Objects.requireNonNull(unlockCapability);
    Objects.requireNonNull(callback);
    return executor.submitSerial(
                                 () -> unlockCapability.use(
                                     masterSecret -> read(transport, messageId, masterSecret)),
                                 callback::onSuccess, callback::onFailure);
  }

  private Result read(String transport, long messageId, MasterSecret masterSecret) {
    MessageRecord message;
    switch (transport) {
      case MmsSmsDatabase.SMS_TRANSPORT:
        EncryptingSmsDatabase smsDatabase = DatabaseFactory.getEncryptingSmsDatabase(context);
        try (Cursor cursor = smsDatabase.getMessage(messageId)) {
          SmsDatabase.Reader reader = smsDatabase.readerFor(masterSecret, cursor);
          message = reader.getNext();
        }
        break;
      case MmsSmsDatabase.MMS_TRANSPORT:
        MmsDatabase mmsDatabase = DatabaseFactory.getMmsDatabase(context);
        try (Cursor cursor = mmsDatabase.getMessage(messageId)) {
          MmsDatabase.Reader reader = mmsDatabase.readerFor(masterSecret, cursor);
          message = reader.getNext();
        }
        break;
      default:
        throw new IllegalArgumentException("Unknown message transport: " + transport);
    }

    Recipients recipients = message == null ? null : message.isMms()
        ? DatabaseFactory.getMmsAddressDatabase(context).getRecipientsForId(message.getId())
        : message.getRecipients();
    return new Result(masterSecret, message, recipients);
  }
}