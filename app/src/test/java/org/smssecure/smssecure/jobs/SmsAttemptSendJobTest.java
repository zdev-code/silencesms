package org.smssecure.smssecure.jobs;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.telephony.SmsManager;

import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.signal.libsignal.protocol.NoSessionException;
import org.smssecure.smssecure.BaseUnitTest;
import org.smssecure.smssecure.crypto.SmsCipher;
import org.smssecure.smssecure.database.DatabaseFactory;
import org.smssecure.smssecure.database.EncryptingSmsDatabase;
import org.smssecure.smssecure.database.SmsDatabase;
import org.smssecure.smssecure.database.SmsSendAttemptDatabase;
import org.smssecure.smssecure.database.model.SmsMessageRecord;
import org.smssecure.smssecure.notifications.MessageNotifier;
import org.smssecure.smssecure.recipients.Recipient;
import org.smssecure.smssecure.recipients.Recipients;
import org.smssecure.smssecure.sms.OutgoingTextMessage;
import org.smssecure.smssecure.util.NumberUtil;
import org.smssecure.smssecure.util.SmsManagerUtil;

public class SmsAttemptSendJobTest extends BaseUnitTest {
  @Test
  public void missingSessionPreservesEncryptionFlagsAndMarksMessageFailed() throws Exception {
    long messageId = 42L;
    EncryptingSmsDatabase encryptingDatabase = mock(EncryptingSmsDatabase.class);
    SmsDatabase smsDatabase = mock(SmsDatabase.class);
    SmsSendAttemptDatabase ledger = mock(SmsSendAttemptDatabase.class);
    SmsMessageRecord record = mock(SmsMessageRecord.class);
    Recipient recipient = mock(Recipient.class);
    Recipients recipients = mock(Recipients.class);
    OutgoingTextMessage transport = mock(OutgoingTextMessage.class);

    when(encryptingDatabase.getMessage(masterSecret, messageId)).thenReturn(record);
    when(record.getIndividualRecipient()).thenReturn(recipient);
    when(recipient.getNumber()).thenReturn("alice@example.com");
    when(record.isSecure()).thenReturn(true);
    when(record.getRecipients()).thenReturn(recipients);
    when(record.getThreadId()).thenReturn(7L);

    try (MockedStatic<DatabaseFactory> databases = Mockito.mockStatic(DatabaseFactory.class);
         MockedStatic<SmsManagerUtil> managers = Mockito.mockStatic(SmsManagerUtil.class);
         MockedStatic<NumberUtil> numbers = Mockito.mockStatic(NumberUtil.class);
         MockedStatic<OutgoingTextMessage> outgoing = Mockito.mockStatic(OutgoingTextMessage.class);
         MockedStatic<MessageNotifier> notifier = Mockito.mockStatic(MessageNotifier.class);
         MockedConstruction<SmsCipher> cipher = Mockito.mockConstruction(SmsCipher.class,
             (mock, context) -> when(mock.encrypt(any(OutgoingTextMessage.class)))
                 .thenThrow(new NoSessionException("missing session")))) {
      databases.when(() -> DatabaseFactory.getEncryptingSmsDatabase(context)).thenReturn(encryptingDatabase);
      databases.when(() -> DatabaseFactory.getSmsDatabase(context)).thenReturn(smsDatabase);
      databases.when(() -> DatabaseFactory.getSmsSendAttemptDatabase(context)).thenReturn(ledger);
      managers.when(() -> SmsManagerUtil.getSystemSmsManager(context)).thenReturn(mock(SmsManager.class));
      numbers.when(() -> NumberUtil.isValidEmail("alice@example.com")).thenReturn(true);
      numbers.when(() -> NumberUtil.isValidSmsOrEmail("alice@example.com")).thenReturn(true);
      outgoing.when(() -> OutgoingTextMessage.from(record)).thenReturn(transport);

      new SmsAttemptSendJob(context, messageId, "recipient").onSend(masterSecret);

      verify(smsDatabase).markAsSentFailed(messageId);
      verify(smsDatabase, never()).markAsNoSession(messageId);
      notifier.verify(() -> MessageNotifier.notifyMessageDeliveryFailed(context, recipients, 7L));
    }
  }
}