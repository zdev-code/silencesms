package org.smssecure.smssecure.ui.mmspreferences;

import org.smssecure.smssecure.mms.LegacyMmsConnection;

public final class MmsPreferencesUiState {
  private final boolean loading;
  private final LegacyMmsConnection.Apn defaults;
  private final boolean failed;

  MmsPreferencesUiState(boolean loading, LegacyMmsConnection.Apn defaults, boolean failed) {
    this.loading = loading;
    this.defaults = defaults;
    this.failed = failed;
  }

  public boolean isLoading() { return loading; }
  public LegacyMmsConnection.Apn getDefaults() { return defaults; }
  public boolean isFailed() { return failed; }
}