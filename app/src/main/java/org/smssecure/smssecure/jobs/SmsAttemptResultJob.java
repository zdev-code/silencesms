package org.smssecure.smssecure.jobs;

import android.content.Context;
import android.util.Log;

import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.database.DatabaseFactory;
import org.smssecure.smssecure.database.SmsSendAttemptDatabase;
import org.smssecure.smssecure.jobs.sms.SmsAttemptRecoveryWorker;
import org.smssecure.smssecure.jobs.sms.SmsRetryWorker;
import org.smssecure.smssecure.jobs.sms.SmsSendRetryPolicy;
import org.smssecure.smssecure.service.SmsDeliveryListener;
import org.smssecure.smssecure.service.KeyCachingService;
import org.whispersystems.jobqueue.JobParameters;

public final class SmsAttemptResultJob extends ContextJob {
  private static final String TAG = SmsAttemptResultJob.class.getSimpleName();

  private final String attemptId;
  private final int attemptNumber;
  private final long messageId;
  private final int partIndex;
  private final int partCount;
  private final String action;
  private final int result;
  private final long callbackAt;

  public SmsAttemptResultJob(Context context, String attemptId, int attemptNumber, long messageId,
                             int partIndex, int partCount, String action, int result, long callbackAt) {
    super(context, JobParameters.newBuilder().withPersistence().withGroupId(attemptId).create());
    this.attemptId = attemptId;
    this.attemptNumber = attemptNumber;
    this.messageId = messageId;
    this.partIndex = partIndex;
    this.partCount = partCount;
    this.action = action;
    this.result = result;
    this.callbackAt = callbackAt;
  }

  @Override public void onAdded() {}

  @Override public void onRun() {
    persistEvidence(context, attemptId, attemptNumber, messageId, partIndex, partCount,
        action, result, callbackAt);
    SmsAttemptRecoveryWorker.replayWithoutSecret(context);
    MasterSecret secret = KeyCachingService.getCachedMasterSecret();
    if (secret != null) org.smssecure.smssecure.database.SmsEndSessionReplay.replay(context, secret, 32);
  }

  public static void persistEvidence(Context context, String attemptId, int attemptNumber,
                                     long messageId, int partIndex, int partCount, String action,
                                     int result, long callbackAt) {
    SmsSendAttemptDatabase ledger = DatabaseFactory.getSmsSendAttemptDatabase(context);
    if (!ledger.matchesAttempt(attemptId, messageId, attemptNumber)) {
      Log.w(TAG, "Ignoring callback with mismatched attempt identity");
      return;
    }
    if (SmsDeliveryListener.SENT_SMS_ACTION.equals(action)) {
      Log.i(TAG, "SMS attempt " + attemptPrefix(attemptId) + " part " + (partIndex + 1) + "/" + partCount +
          " result=" + result + " attempt=" + attemptNumber);
      long dueAt = callbackAt + SmsSendRetryPolicy.delayMillis(
          Math.min(attemptNumber, SmsSendRetryPolicy.MAX_ATTEMPTS - 1), Math.random());
      SmsSendAttemptDatabase.Transition transition = ledger.recordSentResultAndScheduleRetry(
          attemptId, partIndex, partCount, result, callbackAt, dueAt);
      if (transition.decision == org.smssecure.smssecure.jobs.sms.SmsSendAttempt.Decision.RETRYABLE_FAILURE) {
        SmsRetryWorker.schedule(context, attemptId);
      }
    } else if (SmsDeliveryListener.DELIVERED_SMS_ACTION.equals(action)) {
      ledger.recordDeliveryResult(attemptId, partIndex, partCount, result, callbackAt);
    }
  }

  @Override public boolean onShouldRetry(Exception error) {
    return false;
  }

  @Override public void onCanceled() {}

  private static String attemptPrefix(String attemptId) {
    return attemptId.substring(0, Math.min(8, attemptId.length()));
  }
}