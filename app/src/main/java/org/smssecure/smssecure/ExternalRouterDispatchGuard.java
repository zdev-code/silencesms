package org.smssecure.smssecure;

final class ExternalRouterDispatchGuard {
  private boolean processed;

  boolean claim(boolean restored) {
    if (processed || restored) return false;
    processed = true;
    return true;
  }
}