package org.smssecure.smssecure.notifications;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationCompat.Action;
import androidx.core.app.Person;
import androidx.core.app.RemoteInput;
import android.text.SpannableStringBuilder;

import org.smssecure.smssecure.R;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.preferences.widgets.NotificationPrivacyPreference;
import org.smssecure.smssecure.recipients.Recipient;
import org.smssecure.smssecure.recipients.Recipients;
import org.smssecure.smssecure.util.BitmapUtil;
import org.smssecure.smssecure.util.Util;

public class SingleRecipientNotificationBuilder extends AbstractNotificationBuilder {

  private       CharSequence contentTitle;
  private       CharSequence contentText;
  private       NotificationConversation conversation;

  public SingleRecipientNotificationBuilder(@NonNull Context context,
                                            @NonNull NotificationPrivacyPreference privacy)
  {
    super(context, privacy);

    setSmallIcon(R.drawable.icon_notification);
    setColor(androidx.core.content.ContextCompat.getColor(context, R.color.silence_primary));
    setPriority(NotificationCompat.PRIORITY_HIGH);
    setCategory(NotificationCompat.CATEGORY_MESSAGE);
  }

  public void setThread(@NonNull Recipients recipients) {
    if (privacy.isDisplayContact()) {
      setContentTitle(recipients.toShortString());

      if (recipients.isSingleRecipient() && recipients.getPrimaryRecipient().getContactUri() != null) {
        Recipient recipient = recipients.getPrimaryRecipient();
        addPerson(new Person.Builder()
                      .setName(recipient.toShortString())
                      .setKey("recipient:" + recipient.getRecipientId())
                      .setUri(recipient.getContactUri().toString())
                      .build());
      }

      setLargeIcon(recipients.getContactPhoto()
                            .asDrawable(context, recipients.getColor()
                                                          .toConversationColor(context)));
    } else {
      setContentTitle(context.getString(R.string.SingleRecipientNotificationBuilder_silence));
      setLargeIcon(Recipient.getUnknownRecipient()
                            .getContactPhoto()
                            .asDrawable(context, Recipient.getUnknownRecipient()
                                                          .getColor()
                                                          .toConversationColor(context)));
    }
  }

  public void setMessageCount(int messageCount) {
    setContentInfo(String.valueOf(messageCount));
    setNumber(messageCount);
  }

  public void setConversation(@NonNull NotificationConversation conversation) {
    this.conversation = conversation;
  }

  public void setPrimaryMessageBody(@NonNull  Recipients threadRecipients,
                                    @NonNull  Recipient individualRecipient,
                                    @NonNull  CharSequence message)
  {
    SpannableStringBuilder stringBuilder = new SpannableStringBuilder();

    if (privacy.isDisplayContact() && (threadRecipients.isGroupRecipient() || !threadRecipients.isSingleRecipient())) {
      stringBuilder.append(Util.getBoldedString(individualRecipient.toShortString() + ": "));
    }

    if (privacy.isDisplayMessage()) {
      setContentText(stringBuilder.append(message));
    } else {
      setContentText(stringBuilder.append(context.getString(R.string.SingleRecipientNotificationBuilder_new_message)));
    }
  }

  public void addActions(@Nullable MasterSecret masterSecret,
                         @NonNull PendingIntent markReadIntent,
                         @NonNull PendingIntent wearableReplyIntent)
  {
    Action markAsReadAction = createMarkReadAction(
        markReadIntent, context.getString(R.string.MessageNotifier_mark_read));

    if (masterSecret != null) {
      Action replyAction = createReplyAction(
          wearableReplyIntent, context.getString(R.string.MessageNotifier_reply));

      addAction(markAsReadAction);
      addAction(replyAction);

      extend(new NotificationCompat.WearableExtender().addAction(markAsReadAction)
                                                      .addAction(replyAction));
    } else {
      addAction(markAsReadAction);

      extend(new NotificationCompat.WearableExtender().addAction(markAsReadAction));
    }
  }

  static Action createMarkReadAction(@NonNull PendingIntent pendingIntent,
                                     @NonNull CharSequence label)
  {
    return new Action.Builder(R.drawable.check, label, pendingIntent)
        .setSemanticAction(Action.SEMANTIC_ACTION_MARK_AS_READ)
        .setShowsUserInterface(false)
        .build();
  }

  static Action createReplyAction(@NonNull PendingIntent pendingIntent,
                                  @NonNull CharSequence label)
  {
    return new Action.Builder(R.drawable.ic_reply_white_36dp, label, pendingIntent)
        .addRemoteInput(new RemoteInput.Builder(MessageNotifier.EXTRA_REMOTE_REPLY)
                            .setLabel(label)
                            .build())
        .setSemanticAction(Action.SEMANTIC_ACTION_REPLY)
        .setShowsUserInterface(false)
        .build();
  }

  @Override
  public Notification build() {
    if (conversation != null) setStyle(createMessagingStyle(conversation));

    return super.build();
  }

  static NotificationCompat.MessagingStyle createMessagingStyle(@NonNull NotificationConversation conversation) {
    NotificationCompat.MessagingStyle style = new NotificationCompat.MessagingStyle(conversation.getUser())
        .setConversationTitle(conversation.getTitle())
        .setGroupConversation(conversation.isGroupConversation());

    for (NotificationConversation.Message message : conversation.getMessages()) {
      NotificationCompat.MessagingStyle.Message styledMessage =
          new NotificationCompat.MessagingStyle.Message(message.getText(), message.getTimestamp(),
                                                        message.getSender());

      if (message.getAttachmentMimeType() != null && message.getAttachmentUri() != null) {
        styledMessage.setData(message.getAttachmentMimeType(), message.getAttachmentUri());
      }

      style.addMessage(styledMessage);
    }

    return style;
  }

  private void setLargeIcon(@Nullable Drawable drawable) {
    if (drawable != null) {
      int    largeIconTargetSize  = context.getResources().getDimensionPixelSize(R.dimen.contact_photo_target_size);
      Bitmap recipientPhotoBitmap = BitmapUtil.createFromDrawable(drawable, largeIconTargetSize, largeIconTargetSize);

      if (recipientPhotoBitmap != null) {
        setLargeIcon(recipientPhotoBitmap);
      }
    }
  }

  @Override
  public NotificationCompat.Builder setContentTitle(CharSequence contentTitle) {
    this.contentTitle = contentTitle;
    return super.setContentTitle(contentTitle);
  }

  public NotificationCompat.Builder setContentText(CharSequence contentText) {
    this.contentText = trimToDisplayLength(contentText);
    return super.setContentText(this.contentText);
  }

}
