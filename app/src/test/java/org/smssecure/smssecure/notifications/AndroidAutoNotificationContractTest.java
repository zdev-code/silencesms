package org.smssecure.smssecure.notifications;

import android.app.PendingIntent;

import androidx.core.app.NotificationCompat;
import androidx.core.app.Person;
import androidx.core.app.RemoteInput;

import org.junit.Test;
import org.smssecure.smssecure.BaseUnitTest;
import org.smssecure.smssecure.database.model.DisplayRecord;
import org.smssecure.smssecure.protocol.SecureMessageWirePrefix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class AndroidAutoNotificationContractTest extends BaseUnitTest {

  @Test
  public void messagingStyleCarriesConversationAndSenderIdentity() {
    Person user = new Person.Builder().setName("You").setKey("user:self").build();
    Person sender = new Person.Builder().setName("Alice").setKey("recipient:7").build();

    NotificationCompat.MessagingStyle style = new NotificationCompat.MessagingStyle(user)
        .setConversationTitle("Alice")
        .setGroupConversation(false)
        .addMessage("Hello", 123L, sender);

    assertSame(user, style.getUser());
    assertEquals("Alice", style.getConversationTitle());
    assertFalse(style.isGroupConversation());
    assertEquals(1, style.getMessages().size());
    assertEquals("Hello", style.getMessages().get(0).getText());
    assertEquals(123L, style.getMessages().get(0).getTimestamp());
    assertSame(sender, style.getMessages().get(0).getPerson());
  }

  @Test
  public void replyActionHasRequiredSemanticMetadata() {
    RemoteInput remoteInput = new RemoteInput.Builder("reply").setLabel("Reply").build();
    NotificationCompat.Action action = new NotificationCompat.Action.Builder(
        0, "Reply", mock(PendingIntent.class))
        .addRemoteInput(remoteInput)
        .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
        .setShowsUserInterface(false)
        .build();

    assertEquals(NotificationCompat.Action.SEMANTIC_ACTION_REPLY, action.getSemanticAction());
    assertFalse(action.getShowsUserInterface());
    assertEquals(1, action.getRemoteInputs().length);
    assertEquals("reply", action.getRemoteInputs()[0].getResultKey());
  }

  @Test
  public void markReadActionHasNoRemoteInput() {
    NotificationCompat.Action action = new NotificationCompat.Action.Builder(
        0, "Mark read", mock(PendingIntent.class))
        .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
        .setShowsUserInterface(false)
        .build();

    assertEquals(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ, action.getSemanticAction());
    assertFalse(action.getShowsUserInterface());
    assertNull(action.getRemoteInputs());
  }

  @Test
  public void productionReplyActionMatchesAutoContract() {
    NotificationCompat.Action action = SingleRecipientNotificationBuilder.createReplyAction(
        mock(PendingIntent.class), "Reply");

    assertEquals(NotificationCompat.Action.SEMANTIC_ACTION_REPLY, action.getSemanticAction());
    assertFalse(action.getShowsUserInterface());
    assertEquals(1, action.getRemoteInputs().length);
    assertEquals(MessageNotifier.EXTRA_REMOTE_REPLY, action.getRemoteInputs()[0].getResultKey());
  }

  @Test
  public void productionMarkReadActionMatchesAutoContract() {
    NotificationCompat.Action action = SingleRecipientNotificationBuilder.createMarkReadAction(
        mock(PendingIntent.class), "Mark read");

    assertEquals(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ, action.getSemanticAction());
    assertFalse(action.getShowsUserInterface());
    assertNull(action.getRemoteInputs());
  }

  @Test
  public void standaloneConversationNotificationIsNotGrouped() {
    SingleRecipientNotificationBuilder builder = mock(SingleRecipientNotificationBuilder.class);

    MessageNotifier.configureSingleThreadGrouping(builder, false);

    verify(builder, never()).setGroup("messages");
    verify(builder, never()).setGroupSummary(true);
  }

  @Test
  public void bundledConversationNotificationRemainsAGroupChild() {
    SingleRecipientNotificationBuilder builder = mock(SingleRecipientNotificationBuilder.class);

    MessageNotifier.configureSingleThreadGrouping(builder, true);

    verify(builder).setGroup("messages");
    verify(builder, never()).setGroupSummary(true);
  }

  @Test
  public void notificationNeverRendersWirePrefixedCiphertext() {
    String ciphertext = "MwEKIQVPFJrDu4kmsSZ4uGj7RXzyBDgl1lTYF";
    String wireBody = new SecureMessageWirePrefix().calculatePrefix(ciphertext) + ciphertext;

    assertTrue(MessageNotifier.shouldHideNotificationBody(
        0, new DisplayRecord.Body(wireBody, true)));
    assertFalse(MessageNotifier.shouldHideNotificationBody(
        0, new DisplayRecord.Body("ordinary message", true)));
  }
}