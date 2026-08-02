package org.smssecure.smssecure.notifications;

import android.content.BroadcastReceiver.PendingResult;
import android.content.Context;
import android.content.Intent;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationManagerCompat;

import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.util.concurrent.AsyncBroadcastTask;

public class MarkReadReceiver extends MasterSecretBroadcastReceiver {

  private static final String TAG                   = MarkReadReceiver.class.getSimpleName();
  public static final  String CLEAR_ACTION          = "org.smssecure.smssecure.notifications.CLEAR";
  public static final  String THREAD_IDS_EXTRA      = "thread_ids";
  public static final  String NOTIFICATION_ID_EXTRA = "notification_id";

  @Override
  protected void onReceive(final Context context, Intent intent,
                           @Nullable final MasterSecret masterSecret)
  {
    if (!CLEAR_ACTION.equals(intent.getAction()))
      return;

    final long[] threadIds = intent.getLongArrayExtra(THREAD_IDS_EXTRA);

    if (threadIds == null) return;

    Context appContext = context.getApplicationContext();
    NotificationManagerCompat.from(appContext).cancel(intent.getIntExtra(NOTIFICATION_ID_EXTRA, -1));

    PendingResult pendingResult = goAsync();
    AsyncBroadcastTask.submit(pendingResult, TAG, () -> {
      NotificationActionOperations.markThreadsRead(appContext, masterSecret, threadIds, true);
      return null;
    });
  }
}
