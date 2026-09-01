package org.smssecure.smssecure.notifications;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.Person;

import org.smssecure.smssecure.R;
import org.smssecure.smssecure.mms.PartAuthority;
import org.smssecure.smssecure.mms.Slide;
import org.smssecure.smssecure.mms.SlideDeck;
import org.smssecure.smssecure.preferences.widgets.NotificationPrivacyPreference;
import org.smssecure.smssecure.recipients.Recipient;
import org.smssecure.smssecure.recipients.Recipients;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class NotificationConversation {

  public static final class Message {
    private final @NonNull  CharSequence text;
    private final long                   timestamp;
    private final @NonNull  Person       sender;
    private final @Nullable String       attachmentMimeType;
    private final @Nullable Uri          attachmentUri;

    private Message(@NonNull CharSequence text,
                    long timestamp,
                    @NonNull Person sender,
                    @Nullable String attachmentMimeType,
                    @Nullable Uri attachmentUri)
    {
      this.text               = text;
      this.timestamp          = timestamp;
      this.sender             = sender;
      this.attachmentMimeType = attachmentMimeType;
      this.attachmentUri      = attachmentUri;
    }

    public @NonNull CharSequence getText() {
      return text;
    }

    public long getTimestamp() {
      return timestamp;
    }

    public @NonNull Person getSender() {
      return sender;
    }

    public @Nullable String getAttachmentMimeType() {
      return attachmentMimeType;
    }

    public @Nullable Uri getAttachmentUri() {
      return attachmentUri;
    }
  }

  private final long                  threadId;
  private final @NonNull String       conversationKey;
  private final @NonNull CharSequence title;
  private final boolean               groupConversation;
  private final @NonNull Person       user;
  private final @NonNull List<Message> messages;
  private final boolean               replyPermitted;

  private NotificationConversation(long threadId,
                                   @NonNull CharSequence title,
                                   boolean groupConversation,
                                   @NonNull List<Message> messages,
                                   boolean replyPermitted)
  {
    this.threadId         = threadId;
    this.conversationKey  = "thread:" + threadId;
    this.title            = title;
    this.groupConversation = groupConversation;
    this.user             = new Person.Builder().setName("Silence").setKey("user:self").build();
    this.messages         = Collections.unmodifiableList(new ArrayList<>(messages));
    this.replyPermitted   = replyPermitted;
  }

  public static @NonNull NotificationConversation from(@NonNull Context context,
                                                        @NonNull NotificationState state,
                                                        @NonNull NotificationPrivacyPreference privacy,
                                                        boolean replyPermitted)
  {
    if (state.getThreadCount() != 1 || state.getNotifications().isEmpty()) {
      throw new IllegalArgumentException("A notification conversation requires exactly one thread");
    }

    long threadId = state.getNotifications().get(0).getThreadId();
    List<NotificationItem> items = state.getNotificationsForThread(threadId);
    Recipients recipients = items.get(0).getRecipients();
    CharSequence title = privacy.isDisplayContact()
        ? recipients.toShortString()
        : context.getString(R.string.SingleRecipientNotificationBuilder_silence);
    List<Message> messages = new ArrayList<>(items.size());

    for (NotificationItem item : items) {
      Recipient sender = item.getIndividualRecipient();
      CharSequence senderName = privacy.isDisplayContact()
          ? sender.toShortString()
          : context.getString(R.string.SingleRecipientNotificationBuilder_silence);
      CharSequence text = privacy.isDisplayMessage()
          ? item.getText() == null ? "" : item.getText()
          : context.getString(R.string.SingleRecipientNotificationBuilder_new_message);
      String attachmentMimeType = null;
      Uri attachmentUri = null;

      if (privacy.isDisplayMessage()) {
        SlideDeck slideDeck = item.getSlideDeck();
        Slide slide = slideDeck == null ? null : slideDeck.getThumbnailSlide();

        if (slide != null && !slide.isInProgress() && slide.getUri() != null) {
          attachmentMimeType = slide.getContentType();
          attachmentUri = PartAuthority.isLocalUri(slide.getUri())
              ? PartAuthority.getAttachmentPublicUri(slide.getUri())
              : slide.getUri();
        }
      }

      Person senderPerson = new Person.Builder()
          .setName(senderName)
          .setKey("recipient:" + sender.getRecipientId())
          .build();

      messages.add(new Message(text, item.getTimestamp(), senderPerson,
                               attachmentMimeType, attachmentUri));
    }

    return new NotificationConversation(threadId, title, recipients.isGroupRecipient(), messages,
                                        replyPermitted);
  }

  public long getThreadId() {
    return threadId;
  }

  public @NonNull String getConversationKey() {
    return conversationKey;
  }

  public @NonNull CharSequence getTitle() {
    return title;
  }

  public boolean isGroupConversation() {
    return groupConversation;
  }

  public @NonNull Person getUser() {
    return user;
  }

  public @NonNull List<Message> getMessages() {
    return messages;
  }

  public boolean isReplyPermitted() {
    return replyPermitted;
  }
}