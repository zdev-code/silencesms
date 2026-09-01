package org.smssecure.smssecure.notifications;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NotificationActionTokenRegistryTest {

  @Test
  public void rejectsMissingAndBlankTokens() {
    NotificationActionTokenRegistry registry = new NotificationActionTokenRegistry();

    assertFalse(registry.claim(null));
    assertFalse(registry.claim("  "));
  }

  @Test
  public void suppressesConcurrentDuplicateAndAllowsExplicitRetry() {
    NotificationActionTokenRegistry registry = new NotificationActionTokenRegistry();

    assertTrue(registry.claim("token"));
    assertFalse(registry.claim("token"));

    registry.release("token");
    assertTrue(registry.claim("token"));
  }
}