package org.smssecure.smssecure.notifications;


import android.content.BroadcastReceiver;
import android.content.BroadcastReceiver.PendingResult;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.smssecure.smssecure.util.concurrent.AsyncBroadcastTask;

public class DeleteNotificationReceiver extends BroadcastReceiver {

  private static final String TAG = DeleteNotificationReceiver.class.getSimpleName();

  public static String DELETE_NOTIFICATION_ACTION = "org.smssecure.smssecure.DELETE_NOTIFICATION";

  public static String EXTRA_IDS = "message_ids";
  public static String EXTRA_MMS = "is_mms";

  @Override
  public void onReceive(final Context context, Intent intent) {
    if (DELETE_NOTIFICATION_ACTION.equals(intent.getAction())) {
      MessageNotifier.clearReminder(context);

      final long[]    ids = intent.getLongArrayExtra(EXTRA_IDS);
      final boolean[] mms = intent.getBooleanArrayExtra(EXTRA_MMS);

      if (ids == null  || mms == null || ids.length != mms.length) return;

      Context appContext = context.getApplicationContext();
      PendingResult pendingResult = goAsync();
      AsyncBroadcastTask.submit(pendingResult, TAG, () -> {
        NotificationActionOperations.markMessagesNotified(appContext, ids, mms);
        return null;
      });
    }
  }
}
