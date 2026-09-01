package org.smssecure.smssecure.notifications;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.util.Log;

import org.smssecure.smssecure.database.RecipientPreferenceDatabase.VibrateState;
import org.smssecure.smssecure.recipients.Recipients;

import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Arrays;
import java.util.UUID;

public class NotificationState {

  private final LinkedList<NotificationItem> notifications = new LinkedList<>();
  private final LinkedHashSet<Long>          threads       = new LinkedHashSet<>();

  private int notificationCount = 0;

  public NotificationState() {}

  public NotificationState(@NonNull List<NotificationItem> items) {
    for (NotificationItem item : items) {
      addNotification(item);
    }
  }

  public void addNotification(NotificationItem item) {
    notifications.addFirst(item);

    if (threads.contains(item.getThreadId())) {
      threads.remove(item.getThreadId());
    }

    threads.add(item.getThreadId());
    notificationCount++;
  }

  public @Nullable Uri getRingtone() {
    if (!notifications.isEmpty()) {
      Recipients recipients = notifications.getFirst().getRecipients();

      if (recipients != null) {
        return recipients.getRingtone();
      }
    }

    return null;
  }

  public VibrateState getVibrate() {
    if (!notifications.isEmpty()) {
      Recipients recipients = notifications.getFirst().getRecipients();

      if (recipients != null) {
        return recipients.getVibrate();
      }
    }

    return VibrateState.DEFAULT;
  }

  public boolean hasMultipleThreads() {
    return threads.size() > 1;
  }

  public LinkedHashSet<Long> getThreads() {
    return threads;
  }

  public int getThreadCount() {
    return threads.size();
  }

  public int getMessageCount() {
    return notificationCount;
  }

  public List<NotificationItem> getNotifications() {
    return notifications;
  }

  public List<NotificationItem> getNotificationsForThread(long threadId) {
    LinkedList<NotificationItem> list = new LinkedList<>();

    for (NotificationItem item : notifications) {
      if (item.getThreadId() == threadId) list.addFirst(item);
    }

    return list;
  }

  public PendingIntent getMarkAsReadIntent(Context context, int notificationId) {
    long[] threadArray = new long[threads.size()];
    int    index       = 0;

    for (long thread : threads) {
      Log.w("NotificationState", "Added thread: " + thread);
      threadArray[index++] = thread;
    }

    Intent intent = new Intent(context, MessagingNotificationActionService.class);
    intent.setAction(MessagingNotificationActionService.ACTION_MARK_READ);
    long identity = Arrays.hashCode(threadArray);
    intent.setData(Uri.parse(NotificationActionIdentity.data("mark-read", identity)));
    intent.putExtra(MessagingNotificationActionService.EXTRA_THREAD_IDS, threadArray);
    intent.putExtra(MessagingNotificationActionService.EXTRA_NOTIFICATION_ID, notificationId);
    intent.putExtra(MessagingNotificationActionService.EXTRA_ACTION_TOKEN, UUID.randomUUID().toString());

    return PendingIntent.getService(context,
                    NotificationActionIdentity.requestCode("mark-read", identity),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  public PendingIntent getRemoteReplyIntent(Context context, Recipients recipients) {
    if (threads.size() != 1) throw new AssertionError("We only support replies to single thread notifications!");

    Intent intent = new Intent(context, MessagingNotificationActionService.class);
    intent.setAction(MessagingNotificationActionService.ACTION_REPLY);
    long threadId = threads.iterator().next();
    intent.setData(Uri.parse(NotificationActionIdentity.data("reply", threadId)));
    intent.putExtra(MessagingNotificationActionService.EXTRA_RECIPIENT_IDS, recipients.getIds());
    intent.putExtra(MessagingNotificationActionService.EXTRA_THREAD_ID, threadId);
    intent.putExtra(MessagingNotificationActionService.EXTRA_SUBSCRIPTION_ID,
            notifications.getFirst().getSubscriptionId());
    intent.putExtra(MessagingNotificationActionService.EXTRA_SECURE_REPLY_REQUIRED,
            notifications.getFirst().isSecure());
    intent.putExtra(MessagingNotificationActionService.EXTRA_ACTION_TOKEN, UUID.randomUUID().toString());
    intent.setPackage(context.getPackageName());

    return PendingIntent.getService(context,
                    NotificationActionIdentity.requestCode("reply", threadId),
                    intent,
                    getRemoteReplyMutabilityFlag() | PendingIntent.FLAG_UPDATE_CURRENT);
  }

  public PendingIntent getDeleteIntent(Context context) {
    int       index = 0;
    long[]    ids   = new long[notifications.size()];
    boolean[] mms   = new boolean[ids.length];

    for (NotificationItem notificationItem : notifications) {
      ids[index] = notificationItem.getId();
      mms[index++]   = notificationItem.isMms();
    }

    Intent intent = new Intent(context, DeleteNotificationReceiver.class);
    intent.setAction(DeleteNotificationReceiver.DELETE_NOTIFICATION_ACTION);
    intent.putExtra(DeleteNotificationReceiver.EXTRA_IDS, ids);
    intent.putExtra(DeleteNotificationReceiver.EXTRA_MMS, mms);
    long identity = Arrays.hashCode(ids);
    intent.setData(Uri.parse(NotificationActionIdentity.data("delete", identity)));

    return PendingIntent.getBroadcast(context,
                      NotificationActionIdentity.requestCode("delete", identity),
                      intent,
                      PendingIntent.FLAG_UPDATE_CURRENT | getImmutableFlag());
  }

  private static int getRemoteReplyMutabilityFlag() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      return PendingIntent.FLAG_MUTABLE;
    }

    return getImmutableFlag();
  }

  private static int getImmutableFlag() {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
  }


}
