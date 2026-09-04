package org.smssecure.smssecure.ui.share;

public final class SharePayloadUiState {
  private final boolean loading;
  private final boolean failed;

  SharePayloadUiState(boolean loading, boolean failed) {
    this.loading = loading;
    this.failed = failed;
  }

  public boolean isLoading() { return loading; }
  public boolean isFailed() { return failed; }
}