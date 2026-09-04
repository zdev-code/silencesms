package org.smssecure.smssecure.ui.newconversation;

import java.util.Arrays;

public final class NewConversationUiState {
  private final boolean resolving;
  private final long threadId;
  private final long[] recipientIds;

  NewConversationUiState(boolean resolving, long threadId, long[] recipientIds) {
    this.resolving = resolving;
    this.threadId = threadId;
    this.recipientIds = Arrays.copyOf(recipientIds, recipientIds.length);
  }

  public boolean isResolving() { return resolving; }
  public boolean hasResult() { return recipientIds.length > 0; }
  public long getThreadId() { return threadId; }
  public long[] getRecipientIds() { return Arrays.copyOf(recipientIds, recipientIds.length); }
}