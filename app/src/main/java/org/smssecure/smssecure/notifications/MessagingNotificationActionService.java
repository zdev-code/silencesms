package org.smssecure.smssecure.notifications;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.RemoteInput;

import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.service.KeyCachingService;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

public class MessagingNotificationActionService extends Service {

  static final String ACTION_REPLY = "org.smssecure.smssecure.notifications.action.REPLY";
  static final String ACTION_MARK_READ = "org.smssecure.smssecure.notifications.action.MARK_READ";
  static final String EXTRA_RECIPIENT_IDS = "recipient_ids";
  static final String EXTRA_THREAD_ID = "thread_id";
  static final String EXTRA_SUBSCRIPTION_ID = "subscription_id";
  static final String EXTRA_SECURE_REPLY_REQUIRED = "secure_reply_required";
  static final String EXTRA_THREAD_IDS = "thread_ids";
  static final String EXTRA_NOTIFICATION_ID = "notification_id";
  static final String EXTRA_ACTION_TOKEN = "action_token";

  private static final String TAG = MessagingNotificationActionService.class.getSimpleName();

  private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable ->
      new Thread(runnable, "messaging-notification-action"));
  private final NotificationActionTokenRegistry tokenRegistry = new NotificationActionTokenRegistry();

  @Override
  public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
    if (intent == null) {
      stopSelfResult(startId);
      return START_NOT_STICKY;
    }

    String token = intent.getStringExtra(EXTRA_ACTION_TOKEN);
    if (!tokenRegistry.claim(token)) {
      Log.w(TAG, "Ignoring malformed or concurrent duplicate notification action");
      stopSelfResult(startId);
      return START_NOT_STICKY;
    }

    try {
      executor.execute(() -> {
        try {
          handleAction(intent);
        } catch (Exception exception) {
          Log.w(TAG, "Notification action failed", exception);
        } finally {
          tokenRegistry.release(token);
          stopSelfResult(startId);
        }
      });
    } catch (RejectedExecutionException exception) {
      tokenRegistry.release(token);
      Log.w(TAG, "Notification action submitted after service shutdown", exception);
      stopSelfResult(startId);
    }

    return START_NOT_STICKY;
  }

  private void handleAction(Intent intent) {
    if (ACTION_REPLY.equals(intent.getAction())) {
      handleReply(intent);
    } else if (ACTION_MARK_READ.equals(intent.getAction())) {
      handleMarkRead(intent);
    } else {
      Log.w(TAG, "Ignoring unknown notification action");
    }
  }

  private void handleReply(Intent intent) {
    long[] recipientIds = intent.getLongArrayExtra(EXTRA_RECIPIENT_IDS);
    long threadId = intent.getLongExtra(EXTRA_THREAD_ID, -1);
    int subscriptionId = intent.getIntExtra(EXTRA_SUBSCRIPTION_ID,
                        NotificationActionOperations.UNKNOWN_SUBSCRIPTION_ID);
    boolean secureReplyRequired = intent.getBooleanExtra(EXTRA_SECURE_REPLY_REQUIRED, false);
    Bundle results = RemoteInput.getResultsFromIntent(intent);
    CharSequence reply = results == null ? null : results.getCharSequence(MessageNotifier.EXTRA_REMOTE_REPLY);

    MasterSecret masterSecret = KeyCachingService.getMasterSecret(this);
    if (masterSecret == null || recipientIds == null || recipientIds.length == 0 ||
        threadId < 0 || reply == null || reply.toString().trim().isEmpty())
    {
      Log.w(TAG, "Ignoring invalid or locked reply action");
      return;
    }

      String actionSource = intent.getData() != null && !intent.getData().getPathSegments().isEmpty()
        ? intent.getData().getPathSegments().get(0)
        : "unknown";
      Log.i(TAG, "Handling notification reply: source=" + actionSource +
             ", thread=" + threadId +
             ", subscription=" + subscriptionId +
             ", secureRequired=" + secureReplyRequired);
    NotificationActionOperations.sendReply(getApplicationContext(), masterSecret, recipientIds,
                                           reply, threadId, subscriptionId,
                                           secureReplyRequired, true);
  }

  private void handleMarkRead(Intent intent) {
    long[] threadIds = intent.getLongArrayExtra(EXTRA_THREAD_IDS);
    if (threadIds == null || threadIds.length == 0) {
      Log.w(TAG, "Ignoring mark-read action without threads");
      return;
    }

    MasterSecret masterSecret = KeyCachingService.getMasterSecret(this);
    NotificationActionOperations.markThreadsRead(getApplicationContext(), masterSecret,
                                                 threadIds, true);

    int notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1);
    if (notificationId >= 0) NotificationManagerCompat.from(this).cancel(notificationId);
  }

  @Override
  public void onDestroy() {
    executor.shutdown();
    super.onDestroy();
  }

  @Nullable
  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}