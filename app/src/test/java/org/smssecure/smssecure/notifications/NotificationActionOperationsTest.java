package org.smssecure.smssecure.notifications;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.smssecure.smssecure.BaseUnitTest;
import org.smssecure.smssecure.database.DatabaseFactory;
import org.smssecure.smssecure.database.MmsDatabase;
import org.smssecure.smssecure.database.RecipientPreferenceDatabase.RecipientsPreferences;
import org.smssecure.smssecure.database.SmsDatabase;
import org.smssecure.smssecure.database.ThreadDatabase;

import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class NotificationActionOperationsTest extends BaseUnitTest {

  @Test
  public void notificationSubscriptionOverridesPreferenceWithLegacyFallback() {
    RecipientsPreferences preferences = mock(RecipientsPreferences.class);
    when(preferences.getDefaultSubscriptionId()).thenReturn(Optional.of(7));

    assertEquals(3, NotificationActionOperations.resolveSubscriptionId(3, Optional.of(preferences)));
    assertEquals(7, NotificationActionOperations.resolveSubscriptionId(
        NotificationActionOperations.UNKNOWN_SUBSCRIPTION_ID, Optional.of(preferences)));
    assertEquals(-1, NotificationActionOperations.resolveSubscriptionId(
        NotificationActionOperations.UNKNOWN_SUBSCRIPTION_ID, Optional.empty()));
  }

  @Test
  public void secureNotificationCannotDowngradeReplyToPlaintext() {
    assertTrue(NotificationActionOperations.shouldEncryptReply(true, false));
    assertTrue(NotificationActionOperations.shouldEncryptReply(false, true));
    assertFalse(NotificationActionOperations.shouldEncryptReply(false, false));
  }

  @Test
  public void markThreadsReadPreservesLastSeenPolicy() {
    ThreadDatabase threadDatabase = mock(ThreadDatabase.class);

    try (MockedStatic<DatabaseFactory> databases = org.mockito.Mockito.mockStatic(DatabaseFactory.class);
         MockedStatic<MessageNotifier> notifier = org.mockito.Mockito.mockStatic(MessageNotifier.class))
    {
      databases.when(() -> DatabaseFactory.getThreadDatabase(context)).thenReturn(threadDatabase);

      NotificationActionOperations.markThreadsRead(context, masterSecret, new long[] { 4, 8 }, true);

      verify(threadDatabase).setRead(4);
      verify(threadDatabase).setRead(8);
      verify(threadDatabase).setLastSeen(4);
      verify(threadDatabase).setLastSeen(8);
      notifier.verify(() -> MessageNotifier.updateNotification(context, masterSecret));

      NotificationActionOperations.markThreadsRead(context, masterSecret, new long[] { 16 }, false);

      verify(threadDatabase).setRead(16);
      verify(threadDatabase, never()).setLastSeen(16);
    }
  }

  @Test
  public void markMessagesNotifiedRoutesSmsAndMmsIds() {
    SmsDatabase smsDatabase = mock(SmsDatabase.class);
    MmsDatabase mmsDatabase = mock(MmsDatabase.class);

    try (MockedStatic<DatabaseFactory> databases = org.mockito.Mockito.mockStatic(DatabaseFactory.class)) {
      databases.when(() -> DatabaseFactory.getSmsDatabase(context)).thenReturn(smsDatabase);
      databases.when(() -> DatabaseFactory.getMmsDatabase(context)).thenReturn(mmsDatabase);

      NotificationActionOperations.markMessagesNotified(context,
                                                         new long[] { 10, 20, 30 },
                                                         new boolean[] { false, true, false });

      verify(smsDatabase).markAsNotified(10);
      verify(mmsDatabase).markAsNotified(20);
      verify(smsDatabase).markAsNotified(30);
    }
  }

}