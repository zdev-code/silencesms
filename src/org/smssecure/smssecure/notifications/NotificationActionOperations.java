package org.smssecure.smssecure.notifications;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import org.smssecure.smssecure.attachments.Attachment;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.crypto.SessionUtil;
import org.smssecure.smssecure.database.DatabaseFactory;
import org.smssecure.smssecure.database.RecipientPreferenceDatabase.RecipientsPreferences;
import org.smssecure.smssecure.mms.OutgoingMediaMessage;
import org.smssecure.smssecure.recipients.RecipientFactory;
import org.smssecure.smssecure.recipients.Recipients;
import org.smssecure.smssecure.sms.MessageSender;
import org.smssecure.smssecure.sms.OutgoingEncryptedMessage;
import org.smssecure.smssecure.sms.OutgoingTextMessage;

import java.util.LinkedList;
import java.util.Optional;

final class NotificationActionOperations {

  private static final String TAG = NotificationActionOperations.class.getSimpleName();

  static final int UNKNOWN_SUBSCRIPTION_ID = Integer.MIN_VALUE;

  private NotificationActionOperations() {}

  static void sendReply(Context context,
                        @Nullable MasterSecret masterSecret,
                        long[] recipientIds,
                        CharSequence responseText,
                        long threadId,
                        int notificationSubscriptionId,
                        boolean secureReplyRequired,
                        boolean markLastSeen)
  {
    Optional<RecipientsPreferences> preferences = DatabaseFactory.getRecipientPreferenceDatabase(context)
                                                                 .getRecipientsPreferences(recipientIds);
    int subscriptionId = resolveSubscriptionId(notificationSubscriptionId, preferences);
    Recipients recipients = RecipientFactory.getRecipientsForIds(context, recipientIds, false);
    long replyThreadId;

    if (recipients.isGroupRecipient()) {
      OutgoingMediaMessage reply = new OutgoingMediaMessage(recipients,
                                                            responseText.toString(),
                                                            new LinkedList<Attachment>(),
                                                            System.currentTimeMillis(),
                                                            subscriptionId,
                                                            0);
      replyThreadId = MessageSender.send(context, masterSecret, reply, threadId, false);
    } else {
      boolean hasSession = SessionUtil.hasSession(context, masterSecret,
                                                  recipients.getPrimaryRecipient().getNumber(), subscriptionId);
      boolean secure = shouldEncryptReply(secureReplyRequired, hasSession);
      Log.i(TAG, "Queueing notification reply: thread=" + threadId +
                 ", subscription=" + subscriptionId +
                 ", secureRequired=" + secureReplyRequired +
                 ", hasSession=" + hasSession +
                 ", messageType=" + (secure ? "encrypted" : "plaintext"));
      OutgoingTextMessage reply = secure
          ? new OutgoingEncryptedMessage(recipients, responseText.toString(), subscriptionId)
          : new OutgoingTextMessage(recipients, responseText.toString(), subscriptionId);

      replyThreadId = MessageSender.send(context, masterSecret, reply, threadId, false);
    }

    DatabaseFactory.getThreadDatabase(context).setRead(replyThreadId);
    if (markLastSeen) DatabaseFactory.getThreadDatabase(context).setLastSeen(replyThreadId);
    MessageNotifier.updateNotification(context, masterSecret);
  }

  static int resolveSubscriptionId(int notificationSubscriptionId,
                                   Optional<RecipientsPreferences> preferences)
  {
    return notificationSubscriptionId != UNKNOWN_SUBSCRIPTION_ID
        ? notificationSubscriptionId
        : preferences.flatMap(RecipientsPreferences::getDefaultSubscriptionId).orElse(-1);
  }

  static boolean shouldEncryptReply(boolean secureReplyRequired, boolean hasSession) {
    return secureReplyRequired || hasSession;
  }

  static void markThreadsRead(Context context,
                              @Nullable MasterSecret masterSecret,
                              long[] threadIds,
                              boolean markLastSeen)
  {
    for (long threadId : threadIds) {
      DatabaseFactory.getThreadDatabase(context).setRead(threadId);
      if (markLastSeen) DatabaseFactory.getThreadDatabase(context).setLastSeen(threadId);
    }

    MessageNotifier.updateNotification(context, masterSecret);
  }

  static void markMessagesNotified(Context context, long[] messageIds, boolean[] mms) {
    for (int i = 0; i < messageIds.length; i++) {
      if (mms[i]) DatabaseFactory.getMmsDatabase(context).markAsNotified(messageIds[i]);
      else        DatabaseFactory.getSmsDatabase(context).markAsNotified(messageIds[i]);
    }
  }
}