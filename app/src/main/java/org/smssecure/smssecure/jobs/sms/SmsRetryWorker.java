package org.smssecure.smssecure.jobs.sms;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.hilt.work.HiltWorker;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import androidx.work.WorkManager;

import org.smssecure.smssecure.ApplicationContext;
import org.smssecure.smssecure.database.DatabaseFactory;
import org.smssecure.smssecure.database.SmsSendAttemptDatabase;
import org.smssecure.smssecure.jobs.SmsAttemptSendJob;

import java.util.concurrent.TimeUnit;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedInject;

@HiltWorker
public final class SmsRetryWorker extends Worker {
  private static final String TAG = SmsRetryWorker.class.getSimpleName();
  private static final String ATTEMPT_ID = "attempt_id";

  @AssistedInject
  public SmsRetryWorker(@Assisted @NonNull Context context,
                        @Assisted @NonNull WorkerParameters parameters) {
    super(context, parameters);
  }

  public static void schedule(Context context, String attemptId) {
    long dueAt = DatabaseFactory.getSmsSendAttemptDatabase(context).retryDueAt(attemptId);
    if (dueAt < 0) return;
    OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(SmsRetryWorker.class)
        .setInputData(new Data.Builder().putString(ATTEMPT_ID, attemptId).build())
        .setInitialDelay(Math.max(0, dueAt - System.currentTimeMillis()), TimeUnit.MILLISECONDS)
        .build();
    WorkManager.getInstance(context).enqueueUniqueWork("sms-retry-" + attemptId,
        ExistingWorkPolicy.KEEP, work);
  }

  @NonNull @Override public Result doWork() {
    String attemptId = getInputData().getString(ATTEMPT_ID);
    if (attemptId == null) return Result.failure();
    Context context = getApplicationContext();
    SmsSendAttemptDatabase ledger = DatabaseFactory.getSmsSendAttemptDatabase(context);
    if (ledger.retryDueAt(attemptId) > System.currentTimeMillis()) return Result.retry();
    String successor = ledger.claimRetry(attemptId, System.currentTimeMillis());
    if (successor == null) return Result.success();
    long messageId = ledger.messageIdForAttempt(successor);
    try {
        long threadId = DatabaseFactory.getSmsDatabase(context).getThreadIdForMessage(messageId);
        org.smssecure.smssecure.recipients.Recipients recipients =
          DatabaseFactory.getThreadDatabase(context).getRecipientsForThreadId(threadId);
        if (recipients == null) throw new IllegalStateException("Retry recipient missing");
        String recipient = recipients.getPrimaryRecipient().getNumber();
      ApplicationContext.getInstance(context).getJobManager().add(
          new SmsAttemptSendJob(context, messageId, recipient, successor));
    } catch (RuntimeException error) {
      Log.w(TAG, "Unable to enqueue claimed retry", error);
      ledger.markTimedOut(successor, System.currentTimeMillis());
    }
    return Result.success();
  }
}