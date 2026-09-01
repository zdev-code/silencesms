package org.smssecure.smssecure.notifications;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class NotificationActionTokenRegistry {

  private final Set<String> inFlightTokens = ConcurrentHashMap.newKeySet();

  boolean claim(String token) {
    return token != null && !token.trim().isEmpty() && inFlightTokens.add(token);
  }

  void release(String token) {
    if (token != null) inFlightTokens.remove(token);
  }
}