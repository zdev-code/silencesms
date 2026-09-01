package org.smssecure.smssecure.data.conversationthread;

import java.util.Objects;

public final class ConversationThreadQuery {
  private final long threadId;
  private final long limit;
  private final long lastSeen;

  public ConversationThreadQuery(long threadId, long limit, long lastSeen) {
    if (threadId <= 0) throw new IllegalArgumentException("Thread ID must be positive");
    if (limit < 0) throw new IllegalArgumentException("Limit cannot be negative");
    if (lastSeen < -1) throw new IllegalArgumentException("Last seen cannot be less than -1");
    this.threadId = threadId;
    this.limit    = limit;
    this.lastSeen = lastSeen;
  }

  public long getThreadId() { return threadId; }
  public long getLimit() { return limit; }
  public long getLastSeen() { return lastSeen; }
  public boolean hasLimit() { return limit > 0; }

  @Override
  public boolean equals(Object other) {
    if (this == other) return true;
    if (!(other instanceof ConversationThreadQuery)) return false;
    ConversationThreadQuery query = (ConversationThreadQuery) other;
    return threadId == query.threadId && limit == query.limit && lastSeen == query.lastSeen;
  }

  @Override
  public int hashCode() {
    return Objects.hash(threadId, limit, lastSeen);
  }
}
