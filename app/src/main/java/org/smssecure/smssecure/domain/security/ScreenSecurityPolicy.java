package org.smssecure.smssecure.domain.security;

public final class ScreenSecurityPolicy {
  private ScreenSecurityPolicy() {}

  public static boolean shouldSecure(boolean userEnabled, boolean destinationAlwaysSecure) {
    return userEnabled || destinationAlwaysSecure;
  }
}