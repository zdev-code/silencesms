package org.smssecure.smssecure.data.conversation;

import java.util.Objects;

public final class ConversationListQuery {
  private final boolean archived;
  private final String  filter;

  public ConversationListQuery(boolean archived, String filter) {
    this.archived = archived;
    this.filter   = filter == null ? "" : filter.trim();
  }

  public boolean isArchived() {
    return archived;
  }

  public String getFilter() {
    return filter;
  }

  public boolean isFiltered() {
    return !filter.isEmpty();
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) return true;
    if (!(other instanceof ConversationListQuery)) return false;
    ConversationListQuery that = (ConversationListQuery) other;
    return archived == that.archived && filter.equals(that.filter);
  }

  @Override
  public int hashCode() {
    return Objects.hash(archived, filter);
  }
}