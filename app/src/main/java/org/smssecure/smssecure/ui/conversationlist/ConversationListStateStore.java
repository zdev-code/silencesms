package org.smssecure.smssecure.ui.conversationlist;

import androidx.lifecycle.SavedStateHandle;

import javax.inject.Inject;

import java.util.LinkedHashSet;
import java.util.Set;

final class ConversationListStateStore {
  static final String KEY_ARCHIVED = "conversation_list.archived";
  static final String KEY_SELECTED_THREAD_IDS = "conversation_list.selected_thread_ids";
  static final String LEGACY_KEY_FILTER = "conversation_list.filter";

  private final SavedStateHandle handle;

  @Inject
  ConversationListStateStore(SavedStateHandle handle) {
    this(handle, Boolean.TRUE.equals(handle.get("archive")));
  }

  ConversationListStateStore(SavedStateHandle handle, boolean archivedDefault) {
    this.handle = handle;
    handle.remove(LEGACY_KEY_FILTER);
    if (!handle.contains(KEY_ARCHIVED)) handle.set(KEY_ARCHIVED, archivedDefault);
  }

  boolean isArchived() {
    Boolean value = handle.get(KEY_ARCHIVED);
    return value != null && value;
  }

  String getFilter() {
    return "";
  }

  Set<Long> getSelectedThreadIds() {
    long[] values = handle.get(KEY_SELECTED_THREAD_IDS);
    LinkedHashSet<Long> result = new LinkedHashSet<>();
    if (values != null) for (long value : values) if (value > 0) result.add(value);
    return result;
  }

  void save(String filter, Set<Long> selectedThreadIds) {
    long[] values = new long[selectedThreadIds.size()];
    int index = 0;
    for (long threadId : selectedThreadIds) values[index++] = threadId;
    handle.set(KEY_SELECTED_THREAD_IDS, values);
  }
}