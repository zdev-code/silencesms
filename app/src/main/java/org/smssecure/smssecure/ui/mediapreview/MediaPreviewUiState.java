package org.smssecure.smssecure.ui.mediapreview;

public final class MediaPreviewUiState {
  private final boolean saving;
  private final Integer result;

  MediaPreviewUiState(boolean saving, Integer result) {
    this.saving = saving;
    this.result = result;
  }

  public boolean isSaving() { return saving; }
  public Integer getResult() { return result; }
}