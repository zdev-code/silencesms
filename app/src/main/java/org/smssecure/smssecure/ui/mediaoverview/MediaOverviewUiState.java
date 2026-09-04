package org.smssecure.smssecure.ui.mediaoverview;

public final class MediaOverviewUiState {
  public enum Phase { IDLE, LOADING, COLLECTING, SAVING }

  private final Phase phase;
  private final int attachmentCount;
  private final Integer saveResult;

  MediaOverviewUiState(Phase phase, int attachmentCount, Integer saveResult) {
    this.phase = phase;
    this.attachmentCount = attachmentCount;
    this.saveResult = saveResult;
  }

  public Phase getPhase() { return phase; }
  public int getAttachmentCount() { return attachmentCount; }
  public Integer getSaveResult() { return saveResult; }
}