package org.smssecure.smssecure.ui.messagedetails;

public final class MessageDetailsUiState {
  private final boolean loading;
  private final boolean failed;

  MessageDetailsUiState(boolean loading, boolean failed) {
    this.loading = loading;
    this.failed = failed;
  }

  public boolean isLoading() { return loading; }
  public boolean isFailed() { return failed; }
}