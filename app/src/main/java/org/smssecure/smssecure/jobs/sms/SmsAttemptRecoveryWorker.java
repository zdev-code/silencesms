package org.smssecure.smssecure.jobs.sms;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.hilt.work.HiltWorker;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.database.DatabaseFactory;
import org.smssecure.smssecure.database.SmsDeliveryReplay;
import org.smssecure.smssecure.database.SmsEndSessionReplay;
import org.smssecure.smssecure.database.SmsFailureNotificationReplay;
import org.smssecure.smssecure.database.SmsSendAttemptDatabase;
import org.smssecure.smssecure.domain.upgrade.DatabaseUpgradePolicy;
import org.smssecure.smssecure.service.KeyCachingService;

import java.util.List;
import java.util.concurrent.TimeUnit;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedInject;

@HiltWorker
public final class SmsAttemptRecoveryWorker extends Worker {
  private static final String TAG = SmsAttemptRecoveryWorker.class.getSimpleName();

  @AssistedInject
  public SmsAttemptRecoveryWorker(@Assisted @NonNull Context context,
                                  @Assisted @NonNull WorkerParameters parameters) {
    super(context, parameters);
  }

  public static void schedule(Context context) {
    WorkManager.getInstance(context).enqueueUniquePeriodicWork("sms-attempt-recovery",
        ExistingPeriodicWorkPolicy.KEEP,
        new PeriodicWorkRequest.Builder(SmsAttemptRecoveryWorker.class, 1, TimeUnit.HOURS).build());
  }

  @NonNull @Override public Result doWork() {
    Context context = getApplicationContext();
    if (DatabaseUpgradePolicy.isUpdate(context)) return Result.success();
    try {
      replayWithoutSecret(context);
      MasterSecret secret = KeyCachingService.getCachedMasterSecret();
      if (secret != null) replay(context, secret);
      return Result.success();
    } catch (RuntimeException error) {
      Log.w(TAG, "Pending SMS effect replay failed", error);
      return Result.retry();
    }
  }

  public static void replay(Context context, MasterSecret secret) {
    SmsSendAttemptDatabase attempts = DatabaseFactory.getSmsSendAttemptDatabase(context);
    long now = System.currentTimeMillis();
    while (attempts.reconcileStaleAttempts(now - TimeUnit.HOURS.toMillis(24), now, 32) == 32) {}
    replayWithoutSecret(context);
    while (SmsEndSessionReplay.replay(context, secret, 32) == 32) {}
  }

  public static void replayWithoutSecret(Context context) {
    SmsSendAttemptDatabase attempts = DatabaseFactory.getSmsSendAttemptDatabase(context);
    replayMessageStates(context);
    SmsDeliveryReplay.replay(context, 32);
    List<SmsSendAttemptDatabase.PendingEffect> successes;
    do {
      successes = attempts.getPendingOrdinarySuccesses(32);
      int acknowledged = 0;
      for (SmsSendAttemptDatabase.PendingEffect effect : successes) {
        DatabaseFactory.getSmsDatabase(context).notifyMessageStateChanged(effect.messageId);
        if (attempts.acknowledgeEffect(effect.attemptId, SmsSendAttempt.Decision.SUCCEEDED)) {
          acknowledged++;
        }
      }
      if (acknowledged != 32) break;
    } while (true);
    while (SmsFailureNotificationReplay.replay(context, 32) == 32) {}
    recoverRetryScheduling(context);
  }

  private static void recoverRetryScheduling(Context context) {
    SmsSendAttemptDatabase attempts = DatabaseFactory.getSmsSendAttemptDatabase(context);
    long now = System.currentTimeMillis();
    while (attempts.scheduleUnscheduledRetries(now, 32) == 32) {}
    String afterRetryId = null;
    List<String> retryIds;
    do {
      retryIds = attempts.getScheduledRetriesAfter(afterRetryId, 32);
      for (String attemptId : retryIds) {
        SmsRetryWorker.schedule(context, attemptId);
        afterRetryId = attemptId;
      }
    } while (retryIds.size() == 32);
  }

  public static void replayMessageStates(Context context) {
    SmsSendAttemptDatabase attempts = DatabaseFactory.getSmsSendAttemptDatabase(context);
    while (attempts.replayPendingMessageStates(32) == 32) {}
  }
}