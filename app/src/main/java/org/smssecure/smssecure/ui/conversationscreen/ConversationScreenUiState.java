package org.smssecure.smssecure.ui.conversationscreen;

public final class ConversationScreenUiState {
  private final long[] recipientIds;
  private final long threadId;
  private final int distributionType;
  private final boolean archived;
  private final boolean secureDestination;
  private final boolean encryptedConversation;
  private final boolean blocked;
  private final boolean draftPresent;
  private final boolean sendReady;

  ConversationScreenUiState(long[] recipientIds, long threadId, int distributionType,
                            boolean archived, boolean secureDestination,
                            boolean encryptedConversation, boolean blocked,
                            boolean draftPresent, boolean sendReady) {
    this.recipientIds = recipientIds.clone();
    this.threadId = threadId;
    this.distributionType = distributionType;
    this.archived = archived;
    this.secureDestination = secureDestination;
    this.encryptedConversation = encryptedConversation;
    this.blocked = blocked;
    this.draftPresent = draftPresent;
    this.sendReady = sendReady;
  }

  public long[] getRecipientIds() { return recipientIds.clone(); }
  public long getThreadId() { return threadId; }
  public int getDistributionType() { return distributionType; }
  public boolean isArchived() { return archived; }
  public boolean isSecureDestination() { return secureDestination; }
  public boolean isEncryptedConversation() { return encryptedConversation; }
  public boolean isBlocked() { return blocked; }
  public boolean isDraftPresent() { return draftPresent; }
  public boolean isSendReady() { return sendReady; }
}
