package org.smssecure.smssecure.ui.conversationscreen;

public final class ConversationScreenUiState {
  public enum Error { NONE, MMS_CAPABILITY_FAILED, TEXT_SEND_FAILED, MEDIA_SEND_FAILED, LOCKED }

  private final long[] recipientIds;
  private final long threadId;
  private final int distributionType;
  private final boolean archived;
  private final boolean secureDestination;
  private final boolean encryptedConversation;
  private final boolean blocked;
  private final boolean draftPresent;
  private final boolean sendReady;
  private final boolean mmsEnabled;
  private final boolean sending;
  private final long sentThreadId;
  private final Error error;

  ConversationScreenUiState(long[] recipientIds, long threadId, int distributionType,
                            boolean archived, boolean secureDestination,
                            boolean encryptedConversation, boolean blocked,
                            boolean draftPresent, boolean sendReady, boolean mmsEnabled,
                            boolean sending, long sentThreadId, Error error) {
    this.recipientIds = recipientIds.clone();
    this.threadId = threadId;
    this.distributionType = distributionType;
    this.archived = archived;
    this.secureDestination = secureDestination;
    this.encryptedConversation = encryptedConversation;
    this.blocked = blocked;
    this.draftPresent = draftPresent;
    this.sendReady = sendReady;
    this.mmsEnabled = mmsEnabled;
    this.sending = sending;
    this.sentThreadId = sentThreadId;
    this.error = error;
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
  public boolean isMmsEnabled() { return mmsEnabled; }
  public boolean isSending() { return sending; }
  public long getSentThreadId() { return sentThreadId; }
  public Error getError() { return error; }
}
