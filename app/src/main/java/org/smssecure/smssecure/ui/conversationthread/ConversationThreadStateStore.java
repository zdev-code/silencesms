package org.smssecure.smssecure.ui.conversationthread;

import androidx.lifecycle.SavedStateHandle;

import javax.inject.Inject;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

final class ConversationThreadStateStore {
  static final String KEY_THREAD_ID = "conversation_thread.thread_id";
  static final String KEY_LAST_SEEN = "conversation_thread.last_seen";
  static final String KEY_SELECTED_MESSAGE_IDS = "conversation_thread.selected_message_ids";
  static final String KEY_FULL_HISTORY = "conversation_thread.full_history";

  private final SavedStateHandle handle;

  @Inject
  ConversationThreadStateStore(SavedStateHandle handle) {
    this(handle, requiredThreadId(handle), lastSeen(handle));
  }

  ConversationThreadStateStore(SavedStateHandle handle, long threadId, long lastSeen) {
    if (threadId <= 0) throw new IllegalArgumentException("Thread ID must be positive");
    if (lastSeen < -1) throw new IllegalArgumentException("Last seen cannot be less than -1");
    this.handle = handle;
    if (!handle.contains(KEY_THREAD_ID)) handle.set(KEY_THREAD_ID, threadId);
    if (!handle.contains(KEY_LAST_SEEN)) handle.set(KEY_LAST_SEEN, lastSeen);
  }

  private static long requiredThreadId(SavedStateHandle handle) {
    Long value = handle.get("thread_id");
    if (value == null || value <= 0) throw new IllegalStateException("Missing thread ID");
    return value;
  }

  private static long lastSeen(SavedStateHandle handle) {
    Long value = handle.get("last_seen");
    return value == null ? -1 : value;
  }

  long getThreadId() {
    Long value = handle.get(KEY_THREAD_ID);
    if (value == null || value <= 0) throw new IllegalStateException("Missing thread ID");
    return value;
  }

  long getLastSeen() {
    Long value = handle.get(KEY_LAST_SEEN);
    return value == null ? -1 : value;
  }

  boolean isFullHistory() {
    Boolean value = handle.get(KEY_FULL_HISTORY);
    return value != null && value;
  }

  Set<String> getSelectedMessageIds() {
    String[] values = handle.get(KEY_SELECTED_MESSAGE_IDS);
    return values == null ? new LinkedHashSet<>() : new LinkedHashSet<>(Arrays.asList(values));
  }

  void save(long lastSeen, boolean fullHistory, Set<String> selectedMessageIds) {
    handle.set(KEY_LAST_SEEN, lastSeen);
    handle.set(KEY_FULL_HISTORY, fullHistory);
    handle.set(KEY_SELECTED_MESSAGE_IDS, selectedMessageIds.toArray(new String[0]));
  }
}
