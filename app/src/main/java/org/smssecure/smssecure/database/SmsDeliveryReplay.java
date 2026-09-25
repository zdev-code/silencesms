package org.smssecure.smssecure.database;

import android.content.Context;

import org.smssecure.smssecure.notifications.MessageNotifier;
import org.smssecure.smssecure.recipients.Recipient;
import org.smssecure.smssecure.recipients.Recipients;
import org.smssecure.smssecure.util.SilencePreferences;

public final class SmsDeliveryReplay {
  static final long CLAIM_LEASE_MILLIS = 5 * 60 * 1000L;

  interface DeliverySink {
    boolean markDelivered(long messageId);
  }

  private SmsDeliveryReplay() {}

  public static int replay(Context context, int limit) {
    SmsSendAttemptDatabase ledger = DatabaseFactory.getSmsSendAttemptDatabase(context);
    return replay(ledger, limit, System.currentTimeMillis(), messageId -> {
      DatabaseFactory.getSmsDatabase(context).markAsReceived(messageId);
      if (SilencePreferences.isSmsDeliveryReportsToastEnabled(context)) {
        long threadId = DatabaseFactory.getSmsDatabase(context).getThreadIdForMessage(messageId);
        Recipients recipients = DatabaseFactory.getThreadDatabase(context).getRecipientsForThreadId(threadId);
        if (recipients != null && !recipients.isEmpty()) {
          Recipient recipient = recipients.getPrimaryRecipient();
          String name = recipient.getName() == null ? recipient.getNumber() : recipient.getName();
          MessageNotifier.sendDeliveryToast(context, name);
        }
      }
      return true;
    });
  }

  static int replay(SmsSendAttemptDatabase ledger, int limit, long now, DeliverySink sink) {
    if (limit <= 0) throw new IllegalArgumentException("limit must be positive");
    int completed = 0;
    while (completed < limit) {
      SmsSendAttemptDatabase.PendingDelivery delivery =
          ledger.claimPendingDelivery(now, now + CLAIM_LEASE_MILLIS);
      if (delivery == null) break;
      if (sink.markDelivered(delivery.messageId)) {
        if (!ledger.acknowledgeDelivery(delivery)) {
          throw new IllegalStateException("Lost SMS delivery effect claim");
        }
        completed++;
      } else {
        ledger.releaseDelivery(delivery);
        break;
      }
    }
    return completed;
  }
}