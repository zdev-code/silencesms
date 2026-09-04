package org.smssecure.smssecure.ui.notificationpreferences;

public final class NotificationPreferencesUiState {
  private final boolean refreshing;
  private final boolean failed;

  NotificationPreferencesUiState(boolean refreshing, boolean failed) {
    this.refreshing = refreshing;
    this.failed = failed;
  }

  public boolean isRefreshing() { return refreshing; }
  public boolean isFailed() { return failed; }
}