package org.smssecure.smssecure.notifications;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class NotificationActionIdentityTest {

  @Test
  public void identityIsStableForRepostedConversation() {
    assertEquals(NotificationActionIdentity.data("reply", 42),
                 NotificationActionIdentity.data("reply", 42));
    assertEquals(NotificationActionIdentity.requestCode("reply", 42),
                 NotificationActionIdentity.requestCode("reply", 42));
  }

  @Test
  public void conversationsHaveDistinctIdentity() {
    assertNotEquals(NotificationActionIdentity.data("reply", 42),
                    NotificationActionIdentity.data("reply", 43));
    assertNotEquals(NotificationActionIdentity.requestCode("reply", 42),
                    NotificationActionIdentity.requestCode("reply", 43));
  }

  @Test
  public void actionTypesHaveDistinctIdentity() {
    assertNotEquals(NotificationActionIdentity.data("reply", 42),
                    NotificationActionIdentity.data("mark-read", 42));
    assertNotEquals(NotificationActionIdentity.requestCode("reply", 42),
                    NotificationActionIdentity.requestCode("mark-read", 42));
  }
}