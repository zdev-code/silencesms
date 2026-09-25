package org.smssecure.smssecure.jobs.sms;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.hilt.work.HiltWorker;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.smssecure.smssecure.database.DatabaseFactory;
import org.smssecure.smssecure.database.SmsFailureNotificationReplay;
import org.smssecure.smssecure.database.SmsSendAttemptDatabase;

import java.util.concurrent.TimeUnit;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedInject;

@HiltWorker
public final class SmsAttemptTimeoutWorker extends Worker {
  static final long TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(15);
  private static final String TAG = SmsAttemptTimeoutWorker.class.getSimpleName();
  private static final String ATTEMPT_ID = "attempt_id";

  @AssistedInject
  public SmsAttemptTimeoutWorker(@Assisted @NonNull Context context,
                                 @Assisted @NonNull WorkerParameters parameters) {
    super(context, parameters);
  }

  public static void schedule(Context context, String attemptId) {
    SmsSendAttemptDatabase ledger = DatabaseFactory.getSmsSendAttemptDatabase(context);
    long dueAt = ledger.timeoutDueAt(attemptId, TIMEOUT_MILLIS);
    if (dueAt < 0) return;
    OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(SmsAttemptTimeoutWorker.class)
        .setInputData(new Data.Builder().putString(ATTEMPT_ID, attemptId).build())
        .setInitialDelay(Math.max(0, dueAt - System.currentTimeMillis()), TimeUnit.MILLISECONDS)
        .build();
    WorkManager.getInstance(context).enqueueUniqueWork("sms-timeout-" + attemptId,
        ExistingWorkPolicy.KEEP, work);
  }

  @NonNull @Override public Result doWork() {
    String attemptId = getInputData().getString(ATTEMPT_ID);
    if (attemptId == null) return Result.failure();
    Context context = getApplicationContext();
    SmsSendAttemptDatabase ledger = DatabaseFactory.getSmsSendAttemptDatabase(context);
    long dueAt = ledger.timeoutDueAt(attemptId, TIMEOUT_MILLIS);
    if (dueAt < 0) return Result.success();
    if (dueAt > System.currentTimeMillis()) return Result.retry();
    try {
      SmsSendAttemptDatabase.Transition transition = ledger.markTimedOut(attemptId, dueAt);
      if (transition.changed) {
        SmsAttemptRecoveryWorker.replayMessageStates(context);
        SmsFailureNotificationReplay.replay(context, 32);
      }
      return Result.success();
    } catch (RuntimeException error) {
      Log.w(TAG, "Unable to reconcile timed-out SMS attempt", error);
      return Result.retry();
    }
  }
}