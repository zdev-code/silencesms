package org.smssecure.smssecure.ui.share;

public final class ShareTarget {
  private final long threadId;
  private final String recipientIds;
  private final int distributionType;

  public ShareTarget(long threadId, String recipientIds, int distributionType) {
    this.threadId = threadId;
    this.recipientIds = recipientIds;
    this.distributionType = distributionType;
  }

  public long getThreadId() { return threadId; }
  public String getRecipientIds() { return recipientIds; }
  public int getDistributionType() { return distributionType; }
}