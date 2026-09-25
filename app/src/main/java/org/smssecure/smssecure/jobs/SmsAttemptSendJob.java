package org.smssecure.smssecure.jobs;

import android.content.Context;
import android.app.PendingIntent;
import android.telephony.PhoneNumberUtils;
import android.telephony.SmsManager;
import android.util.Log;

import org.signal.libsignal.protocol.NoSessionException;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.crypto.SmsCipher;
import org.smssecure.smssecure.crypto.storage.StorageFileLock;
import org.smssecure.smssecure.crypto.storage.VendoredSessionStore;
import org.smssecure.smssecure.database.DatabaseFactory;
import org.smssecure.smssecure.database.NoSuchMessageException;
import org.smssecure.smssecure.database.SmsSendAttemptDatabase;
import org.smssecure.smssecure.database.model.SmsMessageRecord;
import org.smssecure.smssecure.jobs.requirements.MasterSecretRequirement;
import org.smssecure.smssecure.jobs.sms.SmsAttemptCallbacks;
import org.smssecure.smssecure.jobs.sms.SmsAttemptTimeoutWorker;
import org.smssecure.smssecure.notifications.MessageNotifier;
import org.smssecure.smssecure.sms.MultipartSmsMessageHandler;
import org.smssecure.smssecure.sms.OutgoingTextMessage;
import org.smssecure.smssecure.util.NumberUtil;
import org.smssecure.smssecure.util.SilencePreferences;
import org.smssecure.smssecure.util.SmsManagerUtil;
import org.smssecure.smssecure.util.dualsim.DualSimUtil;
import org.whispersystems.jobqueue.JobParameters;
import org.whispersystems.libsignal.UntrustedIdentityException;

import java.util.ArrayList;

public final class SmsAttemptSendJob extends SendJob {
  private static final String TAG = SmsAttemptSendJob.class.getSimpleName();

  private final long messageId;
  private final String claimedAttemptId;

  public SmsAttemptSendJob(Context context, long messageId, String name) {
    this(context, messageId, name, null);
  }

  public SmsAttemptSendJob(Context context, long messageId, String name, String claimedAttemptId) {
    super(context, JobParameters.newBuilder().withPersistence()
        .withRequirement(new MasterSecretRequirement(context)).withGroupId(name).create());
    this.messageId = messageId;
    this.claimedAttemptId = claimedAttemptId;
  }

  @Override public void onAdded() {}

  @Override protected void onSend(MasterSecret masterSecret)
      throws NoSuchMessageException, NoSessionException, UntrustedIdentityException {
    SmsMessageRecord record = DatabaseFactory.getEncryptingSmsDatabase(context)
        .getMessage(masterSecret, messageId);
    String recipient = record.getIndividualRecipient().getNumber();
    if (!NumberUtil.isValidEmail(recipient)) {
      recipient = PhoneNumberUtils.stripSeparators(PhoneNumberUtils.convertKeypadLettersToDigits(recipient));
    }
    if (!NumberUtil.isValidSmsOrEmail(recipient)) {
      Log.w(TAG, "Invalid SMS destination");
      failBeforeAttempt(record);
      return;
    }

    SmsSendAttemptDatabase ledger = DatabaseFactory.getSmsSendAttemptDatabase(context);
    int[] claimed = claimedAttemptId == null ? null : ledger.preparedAttempt(claimedAttemptId, messageId);
    if (claimedAttemptId != null && claimed == null) return;
    if (claimedAttemptId == null && ledger.hasAttemptForMessage(messageId)) return;

    SmsManager systemManager = SmsManagerUtil.getSystemSmsManager(context);
    ArrayList<String> parts;
    String snapshot = null;
    try {
      if (record.isSecure() || record.isKeyExchange() || record.isEndSession()) {
        OutgoingTextMessage transport = OutgoingTextMessage.from(record);
        if (!record.isKeyExchange()) {
          if (record.isEndSession()) {
            synchronized (StorageFileLock.forDirectory(VendoredSessionStore.getSessionDirectory(context))) {
              transport = new SmsCipher(context, masterSecret, record.getSubscriptionId()).encrypt(transport);
              snapshot = new VendoredSessionStore(context, masterSecret, record.getSubscriptionId())
                  .snapshotSession(record.getIndividualRecipient().getNumber());
            }
          } else {
            transport = new SmsCipher(context, masterSecret, record.getSubscriptionId()).encrypt(transport);
          }
        }
        parts = systemManager.divideMessage(new MultipartSmsMessageHandler().getEncodedMessage(transport));
      } else {
        parts = systemManager.divideMessage(record.getBody().getBody());
      }
    } catch (NoSessionException | UntrustedIdentityException error) {
      Log.w(TAG, "Cannot prepare secure SMS", error);
      if (claimedAttemptId == null) {
        failBeforeAttempt(record);
      } else {
        ledger.markTimedOut(claimedAttemptId, System.currentTimeMillis());
      }
      return;
    }
    if (parts == null || parts.isEmpty()) {
      Log.w(TAG, "SMS division returned no parts");
      failBeforeAttempt(record);
      return;
    }

    String attemptId = claimedAttemptId == null
        ? ledger.createAttempt(messageId, parts.size(), record.getSubscriptionId(), System.currentTimeMillis())
        : claimedAttemptId;
    if (claimed != null && claimed[1] != parts.size()) {
      ledger.markTimedOut(attemptId, System.currentTimeMillis());
      return;
    }
    if (!ledger.stageSecureIntent(attemptId, record.isSecure())) {
      throw new IllegalStateException("Cannot persist secure-send intent");
    }
    if (record.isEndSession() && !ledger.stageEndSessionSnapshot(attemptId, snapshot)) {
      throw new IllegalStateException("Cannot persist end-session snapshot");
    }
    int deviceSubscriptionId = DualSimUtil.getSubscriptionIdFromAppSubscriptionId(context,
        record.getSubscriptionId());
    SmsManager smsManager = SmsManagerUtil.getSystemSmsManager(context, deviceSubscriptionId);
    ArrayList<PendingIntent> sentCallbacks = SmsAttemptCallbacks.create(context, attemptId,
        claimed == null ? 1 : claimed[0], messageId, parts.size(), false);
    ArrayList<PendingIntent> deliveryCallbacks = SilencePreferences.isSmsDeliveryReportsEnabled(context)
        ? SmsAttemptCallbacks.create(context, attemptId, claimed == null ? 1 : claimed[0],
            messageId, parts.size(), true) : null;
              SmsAttemptTimeoutWorker.schedule(context, attemptId);
    if (!ledger.markSubmitted(attemptId, System.currentTimeMillis())) {
      throw new IllegalStateException("Attempt is no longer prepared for submission");
    }
    try {
      smsManager.sendMultipartTextMessage(recipient, null, parts, sentCallbacks, deliveryCallbacks);
    } catch (RuntimeException error) {
      Log.w(TAG, "SMS submission outcome unknown for prepared attempt", error);
    }
  }

  @Override public boolean onShouldRetryThrowable(Exception error) {
    return false;
  }

  @Override public void onCanceled() {
    SmsSendAttemptDatabase ledger = DatabaseFactory.getSmsSendAttemptDatabase(context);
    if (claimedAttemptId != null) {
      if (ledger.preparedAttempt(claimedAttemptId, messageId) != null) {
        ledger.markTimedOut(claimedAttemptId, System.currentTimeMillis());
      }
      return;
    }
    if (ledger.hasAttemptForMessage(messageId)) return;
    long threadId = DatabaseFactory.getSmsDatabase(context).getThreadIdForMessage(messageId);
    DatabaseFactory.getSmsDatabase(context).markAsSentFailed(messageId);
    if (threadId >= 0) {
      org.smssecure.smssecure.recipients.Recipients recipients =
          DatabaseFactory.getThreadDatabase(context).getRecipientsForThreadId(threadId);
      if (recipients != null) MessageNotifier.notifyMessageDeliveryFailed(context, recipients, threadId);
    }
  }

  private void failBeforeAttempt(SmsMessageRecord record) {
    if (claimedAttemptId != null) {
      DatabaseFactory.getSmsSendAttemptDatabase(context)
          .markTimedOut(claimedAttemptId, System.currentTimeMillis());
    } else {
      DatabaseFactory.getSmsDatabase(context).markAsSentFailed(messageId);
      MessageNotifier.notifyMessageDeliveryFailed(context, record.getRecipients(), record.getThreadId());
    }
  }
}