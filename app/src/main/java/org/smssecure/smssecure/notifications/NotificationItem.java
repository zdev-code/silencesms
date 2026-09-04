package org.smssecure.smssecure.notifications;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.TaskStackBuilder;

import org.smssecure.smssecure.ConversationActivity;
import org.smssecure.smssecure.HostNavigationCommand;
import org.smssecure.smssecure.database.ThreadDatabase;
import org.smssecure.smssecure.mms.SlideDeck;
import org.smssecure.smssecure.recipients.Recipient;
import org.smssecure.smssecure.recipients.Recipients;

public class NotificationItem {

  private final long                        id;
  private final boolean                     mms;
  private final @NonNull  Recipients        recipients;
  private final @NonNull  Recipient         individualRecipient;
  private final @Nullable Recipients        threadRecipients;
  private final long                        threadId;
  private final int                         subscriptionId;
  private final boolean                     secure;
  private final @Nullable CharSequence      text;
  private final long                        timestamp;
  private final @Nullable SlideDeck         slideDeck;

  public NotificationItem(long id, boolean mms,
                          @NonNull   Recipient individualRecipient,
                          @NonNull   Recipients recipients,
                          @Nullable  Recipients threadRecipients,
                          long threadId, int subscriptionId, boolean secure,
                          @Nullable CharSequence text, long timestamp,
                          @Nullable SlideDeck slideDeck)
  {
    this.id                  = id;
    this.mms                 = mms;
    this.individualRecipient = individualRecipient;
    this.recipients          = recipients;
    this.threadRecipients    = threadRecipients;
    this.text                = text;
    this.threadId            = threadId;
    this.subscriptionId      = subscriptionId;
    this.secure              = secure;
    this.timestamp           = timestamp;
    this.slideDeck           = slideDeck;
  }

  public @NonNull  Recipients getRecipients() {
    return threadRecipients == null ? recipients : threadRecipients;
  }

  public @NonNull  Recipient getIndividualRecipient() {
    return individualRecipient;
  }

  public CharSequence getText() {
    return text;
  }

  public long getTimestamp() {
    return timestamp;
  }

  public long getThreadId() {
    return threadId;
  }

  public int getSubscriptionId() {
    return subscriptionId;
  }

  public boolean isSecure() {
    return secure;
  }

  public @Nullable SlideDeck getSlideDeck() {
    return slideDeck;
  }

  public PendingIntent getPendingIntent(Context context) {
    Recipients notifyRecipients = threadRecipients != null ? threadRecipients : recipients;
    if (notifyRecipients == null) throw new IllegalStateException("Notification has no recipients");
    Intent intent = HostNavigationCommand.createConversationIntent(
      context, notifyRecipients.getIds(), threadId, ThreadDatabase.DistributionTypes.DEFAULT,
      false, System.currentTimeMillis(), 0L, null);
    intent.setData(Uri.parse(NotificationActionIdentity.data("content", threadId)));

    return TaskStackBuilder.create(context)
                           .addNextIntentWithParentStack(intent)
                 .getPendingIntent(NotificationActionIdentity.requestCode("content", threadId),
                         PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  public long getId() {
    return id;
  }

  public boolean isMms() {
    return mms;
  }
}
