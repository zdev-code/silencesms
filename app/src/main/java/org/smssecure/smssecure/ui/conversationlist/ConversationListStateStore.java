package org.smssecure.smssecure.ui.conversationlist;

import androidx.lifecycle.SavedStateHandle;

import java.util.LinkedHashSet;
import java.util.Set;

final class ConversationListStateStore {
  static final String KEY_ARCHIVED = "conversation_list.archived";
  static final String KEY_FILTER = "conversation_list.filter";
  static final String KEY_SELECTED_THREAD_IDS = "conversation_list.selected_thread_ids";

  private final SavedStateHandle handle;

  ConversationListStateStore(SavedStateHandle handle, boolean archivedDefault) {
    this.handle = handle;
    if (!handle.contains(KEY_ARCHIVED)) handle.set(KEY_ARCHIVED, archivedDefault);
  }

  boolean isArchived() {
    Boolean value = handle.get(KEY_ARCHIVED);
    return value != null && value;
  }

  String getFilter() {
    String value = handle.get(KEY_FILTER);
    return value == null ? "" : value;
  }

  Set<Long> getSelectedThreadIds() {
    long[] values = handle.get(KEY_SELECTED_THREAD_IDS);
    LinkedHashSet<Long> result = new LinkedHashSet<>();
    if (values != null) for (long value : values) if (value > 0) result.add(value);
    return result;
  }

  void save(String filter, Set<Long> selectedThreadIds) {
    handle.set(KEY_FILTER, filter);
    long[] values = new long[selectedThreadIds.size()];
    int index = 0;
    for (long threadId : selectedThreadIds) values[index++] = threadId;
    handle.set(KEY_SELECTED_THREAD_IDS, values);
  }
}