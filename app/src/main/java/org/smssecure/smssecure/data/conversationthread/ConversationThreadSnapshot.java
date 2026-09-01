package org.smssecure.smssecure.data.conversationthread;

import java.util.List;

public final class ConversationThreadSnapshot {
  private final List<ConversationMessageRow> messages;
  private final long                         lastSeen;
  private final boolean                      limited;

  public ConversationThreadSnapshot(List<ConversationMessageRow> messages, long lastSeen,
                                    boolean limited) {
    this.messages = List.copyOf(messages);
    this.lastSeen = lastSeen;
    this.limited  = limited;
  }

  public List<ConversationMessageRow> getMessages() { return messages; }
  public long getLastSeen() { return lastSeen; }
  public boolean isLimited() { return limited; }
}
