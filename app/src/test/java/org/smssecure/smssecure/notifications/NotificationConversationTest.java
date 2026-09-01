package org.smssecure.smssecure.notifications;

import org.junit.Before;
import org.junit.Test;
import org.smssecure.smssecure.BaseUnitTest;
import org.smssecure.smssecure.R;
import org.smssecure.smssecure.preferences.widgets.NotificationPrivacyPreference;
import org.smssecure.smssecure.recipients.Recipient;
import org.smssecure.smssecure.recipients.Recipients;

import androidx.core.app.NotificationCompat;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class NotificationConversationTest extends BaseUnitTest {

  private Recipient alice;
  private Recipient bob;
  private Recipients threadRecipients;

  @Before
  public void setUpRecipients() {
    alice = mock(Recipient.class);
    bob = mock(Recipient.class);
    threadRecipients = mock(Recipients.class);

    when(alice.getRecipientId()).thenReturn(7L);
    when(alice.toShortString()).thenReturn("Alice");
    when(bob.getRecipientId()).thenReturn(8L);
    when(bob.toShortString()).thenReturn("Bob");
    when(threadRecipients.toShortString()).thenReturn("Alice, Bob");
    when(threadRecipients.isGroupRecipient()).thenReturn(true);
    when(context.getString(R.string.SingleRecipientNotificationBuilder_silence)).thenReturn("Silence");
    when(context.getString(R.string.SingleRecipientNotificationBuilder_new_message)).thenReturn("New message");
  }

  @Test
  public void visibleConversationPreservesChronologyAndSenderIdentity() {
    NotificationState state = state(
        item(1, alice, "First", 100),
        item(2, bob, "Second", 200));

    NotificationConversation conversation = NotificationConversation.from(
        context, state, new NotificationPrivacyPreference("all"), true);

    assertEquals(42L, conversation.getThreadId());
    assertEquals("thread:42", conversation.getConversationKey());
    assertEquals("Alice, Bob", conversation.getTitle());
    assertTrue(conversation.isGroupConversation());
    assertTrue(conversation.isReplyPermitted());
    assertEquals("user:self", conversation.getUser().getKey());
    assertEquals("First", conversation.getMessages().get(0).getText());
    assertEquals(100L, conversation.getMessages().get(0).getTimestamp());
    assertEquals("recipient:7", conversation.getMessages().get(0).getSender().getKey());
    assertEquals("Second", conversation.getMessages().get(1).getText());
    assertEquals("recipient:8", conversation.getMessages().get(1).getSender().getKey());
  }

  @Test
  public void privateConversationRedactsNamesBodiesAndLockedReply() {
    NotificationState state = state(item(1, alice, null, 100));

    NotificationConversation conversation = NotificationConversation.from(
        context, state, new NotificationPrivacyPreference("none"), false);

    assertEquals("Silence", conversation.getTitle());
    assertEquals("Silence", conversation.getMessages().get(0).getSender().getName());
    assertEquals("New message", conversation.getMessages().get(0).getText());
    assertFalse(conversation.isReplyPermitted());
    assertNull(conversation.getMessages().get(0).getAttachmentMimeType());
    assertNull(conversation.getMessages().get(0).getAttachmentUri());
  }

  @Test
  public void visibleNullBodyBecomesEmptyText() {
    NotificationConversation conversation = NotificationConversation.from(
        context, state(item(1, alice, null, 100)), new NotificationPrivacyPreference("all"), true);

    assertEquals("", conversation.getMessages().get(0).getText());
  }

  @Test
  public void messagingStyleRendersConversationMessages() {
    NotificationConversation conversation = NotificationConversation.from(
        context,
        state(item(1, alice, "First", 100), item(2, bob, "Second", 200)),
        new NotificationPrivacyPreference("all"),
        true);

    NotificationCompat.MessagingStyle style =
        SingleRecipientNotificationBuilder.createMessagingStyle(conversation);

    assertEquals("Alice, Bob", style.getConversationTitle());
    assertTrue(style.isGroupConversation());
    assertEquals(2, style.getMessages().size());
    assertEquals("First", style.getMessages().get(0).getText());
    assertEquals("recipient:7", style.getMessages().get(0).getPerson().getKey());
    assertEquals("Second", style.getMessages().get(1).getText());
  }

  private NotificationState state(NotificationItem... items) {
    return new NotificationState(Arrays.asList(items));
  }

  private NotificationItem item(long id, Recipient sender, String text, long timestamp) {
    return new NotificationItem(id, false, sender, threadRecipients, threadRecipients,
                                42L, 3, false, text, timestamp, null);
  }
}