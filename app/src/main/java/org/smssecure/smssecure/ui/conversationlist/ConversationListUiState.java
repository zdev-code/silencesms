package org.smssecure.smssecure.ui.conversationlist;

import org.smssecure.smssecure.data.conversation.ConversationListEntry;
import org.smssecure.smssecure.domain.conversation.ConversationListReminderPolicy;

import java.util.List;
import java.util.Set;

public final class ConversationListUiState {
  public enum Mutation { NONE, ARCHIVE, UNARCHIVE, UNDO, DELETE, SEND_DRAFTS }
  public enum Error { NONE, LOAD_FAILED, MUTATION_FAILED, DELETE_FAILED, SEND_DRAFTS_FAILED, LOCKED }

  private final boolean                     archived;
  private final String                      filter;
  private final List<ConversationListEntry> entries;
  private final int                         archivedCount;
  private final Set<Long>                   selectedThreadIds;
  private final boolean                     loading;
  private final Mutation                    activeMutation;
  private final boolean                     undoAvailable;
  private final Error                       error;
  private final ConversationListReminderPolicy.Kind reminderKind;

  ConversationListUiState(boolean archived, String filter, List<ConversationListEntry> entries,
                          int archivedCount, Set<Long> selectedThreadIds, boolean loading,
                          Mutation activeMutation, boolean undoAvailable, Error error)
  {
    this(archived, filter, entries, archivedCount, selectedThreadIds, loading, activeMutation,
         undoAvailable, error, ConversationListReminderPolicy.Kind.NONE);
  }

  ConversationListUiState(boolean archived, String filter, List<ConversationListEntry> entries,
                          int archivedCount, Set<Long> selectedThreadIds, boolean loading,
                          Mutation activeMutation, boolean undoAvailable, Error error,
                          ConversationListReminderPolicy.Kind reminderKind)
  {
    this.archived          = archived;
    this.filter            = filter;
    this.entries           = List.copyOf(entries);
    this.archivedCount     = archivedCount;
    this.selectedThreadIds = Set.copyOf(selectedThreadIds);
    this.loading           = loading;
    this.activeMutation    = activeMutation;
    this.undoAvailable     = undoAvailable;
    this.error             = error;
    this.reminderKind      = reminderKind;
  }

  public boolean isArchived() { return archived; }
  public String getFilter() { return filter; }
  public List<ConversationListEntry> getEntries() { return entries; }
  public int getArchivedCount() { return archivedCount; }
  public Set<Long> getSelectedThreadIds() { return selectedThreadIds; }
  public boolean isLoading() { return loading; }
  public Mutation getActiveMutation() { return activeMutation; }
  public boolean isUndoAvailable() { return undoAvailable; }
  public Error getError() { return error; }
  public ConversationListReminderPolicy.Kind getReminderKind() { return reminderKind; }
}