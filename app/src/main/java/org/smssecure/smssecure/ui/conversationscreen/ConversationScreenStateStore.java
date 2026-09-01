package org.smssecure.smssecure.ui.conversationscreen;

import androidx.lifecycle.SavedStateHandle;

final class ConversationScreenStateStore {
  static final String KEY_RECIPIENT_IDS = "conversation_screen.recipient_ids";
  static final String KEY_THREAD_ID = "conversation_screen.thread_id";
  static final String KEY_DISTRIBUTION_TYPE = "conversation_screen.distribution_type";
  static final String KEY_ARCHIVED = "conversation_screen.archived";

  private final SavedStateHandle handle;

  ConversationScreenStateStore(SavedStateHandle handle) {
    this.handle = handle;
  }

  boolean hasConversation() { return handle.contains(KEY_RECIPIENT_IDS); }
  long[] getRecipientIds() {
    long[] value = handle.get(KEY_RECIPIENT_IDS);
    return value == null ? new long[0] : value.clone();
  }
  long getThreadId() {
    Long value = handle.get(KEY_THREAD_ID);
    return value == null ? -1 : value;
  }
  int getDistributionType(int defaultValue) {
    Integer value = handle.get(KEY_DISTRIBUTION_TYPE);
    return value == null ? defaultValue : value;
  }
  boolean isArchived() {
    Boolean value = handle.get(KEY_ARCHIVED);
    return value != null && value;
  }

  void save(long[] recipientIds, long threadId, int distributionType, boolean archived) {
    handle.set(KEY_RECIPIENT_IDS, recipientIds.clone());
    handle.set(KEY_THREAD_ID, threadId);
    handle.set(KEY_DISTRIBUTION_TYPE, distributionType);
    handle.set(KEY_ARCHIVED, archived);
  }
}
