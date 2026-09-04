package org.smssecure.smssecure.ui.recipientpreferences;

public final class RecipientPreferencesUiState {
  private final int pendingMutations;
  private final boolean failed;

  RecipientPreferencesUiState(int pendingMutations, boolean failed) {
    this.pendingMutations = pendingMutations;
    this.failed = failed;
  }

  public int getPendingMutations() { return pendingMutations; }
  public boolean isFailed() { return failed; }
}