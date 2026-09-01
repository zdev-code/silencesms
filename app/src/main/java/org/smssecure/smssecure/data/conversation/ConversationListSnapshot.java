package org.smssecure.smssecure.data.conversation;

import java.util.List;

public final class ConversationListSnapshot {
  private final List<ConversationListEntry> entries;
  private final int                         archivedCount;

  public ConversationListSnapshot(List<ConversationListEntry> entries, int archivedCount) {
    this.entries       = List.copyOf(entries);
    this.archivedCount = archivedCount;
  }

  public List<ConversationListEntry> getEntries() {
    return entries;
  }

  public int getArchivedCount() {
    return archivedCount;
  }
}