package org.smssecure.smssecure.ui.conversationthread;

import org.smssecure.smssecure.data.conversationthread.ConversationMessageRow;

import java.util.List;
import java.util.Set;

public final class ConversationThreadUiState {
  public enum Mutation { NONE, DELETE, RESEND, SAVE_ATTACHMENT }
  public enum Error { NONE, LOAD_FAILED, DELETE_FAILED, RESEND_FAILED, SAVE_ATTACHMENT_FAILED, LOCKED }

  private final long threadId;
  private final List<ConversationMessageRow> messages;
  private final Set<String> selectedMessageIds;
  private final long lastSeen;
  private final boolean loading;
  private final boolean limited;
  private final Mutation mutation;
  private final Error error;
  private final boolean threadDeleted;
  private final int attachmentSaveResult;

  ConversationThreadUiState(long threadId, List<ConversationMessageRow> messages,
                            Set<String> selectedMessageIds, long lastSeen, boolean loading,
                            boolean limited, Mutation mutation, Error error,
                            boolean threadDeleted, int attachmentSaveResult) {
    this.threadId = threadId;
    this.messages = List.copyOf(messages);
    this.selectedMessageIds = Set.copyOf(selectedMessageIds);
    this.lastSeen = lastSeen;
    this.loading = loading;
    this.limited = limited;
    this.mutation = mutation;
    this.error = error;
    this.threadDeleted = threadDeleted;
    this.attachmentSaveResult = attachmentSaveResult;
  }

  public long getThreadId() { return threadId; }
  public List<ConversationMessageRow> getMessages() { return messages; }
  public Set<String> getSelectedMessageIds() { return selectedMessageIds; }
  public long getLastSeen() { return lastSeen; }
  public boolean isLoading() { return loading; }
  public boolean isLimited() { return limited; }
  public Mutation getMutation() { return mutation; }
  public Error getError() { return error; }
  public boolean isThreadDeleted() { return threadDeleted; }
  public int getAttachmentSaveResult() { return attachmentSaveResult; }
}
