package org.smssecure.smssecure.database;

import android.content.Context;

import org.smssecure.smssecure.notifications.MessageNotifier;
import org.smssecure.smssecure.recipients.Recipients;

public final class SmsFailureNotificationReplay {
  interface NotificationSink {
    boolean notifyFailure(long messageId);
  }

  private SmsFailureNotificationReplay() {}

  public static int replay(Context context, int limit) {
    SmsSendAttemptDatabase ledger = DatabaseFactory.getSmsSendAttemptDatabase(context);
    return replay(ledger, limit, messageId -> {
      long threadId = DatabaseFactory.getSmsDatabase(context).getThreadIdForMessage(messageId);
      if (threadId < 0) return false;
      DatabaseFactory.getSmsDatabase(context).notifyMessageStateChanged(messageId);
      Recipients recipients = DatabaseFactory.getThreadDatabase(context).getRecipientsForThreadId(threadId);
      if (recipients == null) return false;
      MessageNotifier.notifyMessageDeliveryFailedForReplay(context, recipients, threadId);
      return true;
    });
  }

  static int replay(SmsSendAttemptDatabase ledger, int limit, NotificationSink sink) {
    int completed = 0;
    for (SmsSendAttemptDatabase.PendingEffect effect : ledger.getPendingFailureNotifications(limit)) {
      if (sink.notifyFailure(effect.messageId) &&
          ledger.acknowledgeFailureNotification(effect.attemptId)) completed++;
    }
    return completed;
  }
}