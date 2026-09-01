package org.smssecure.smssecure.data.conversation;

public final class ConversationListEntry {
  private final long    threadId;
  private final String  recipientIds;
  private final long    date;
  private final long    messageCount;
  private final boolean read;
  private final String  snippet;
  private final long    snippetType;
  private final String  snippetUri;
  private final int     distributionType;
  private final boolean archived;
  private final int     status;
  private final long    lastSeen;

  public ConversationListEntry(long threadId, String recipientIds, long date, long messageCount,
                               boolean read, String snippet, long snippetType, String snippetUri, int distributionType,
                               boolean archived, int status, long lastSeen)
  {
    if (threadId <= 0) throw new IllegalArgumentException("Conversation entries require a positive thread ID");
    this.threadId         = threadId;
    this.recipientIds     = recipientIds;
    this.date             = date;
    this.messageCount     = messageCount;
    this.read             = read;
    this.snippet          = snippet;
    this.snippetType      = snippetType;
    this.snippetUri       = snippetUri;
    this.distributionType = distributionType;
    this.archived         = archived;
    this.status           = status;
    this.lastSeen         = lastSeen;
  }

  public long getThreadId() { return threadId; }
  public String getRecipientIds() { return recipientIds; }
  public long getDate() { return date; }
  public long getMessageCount() { return messageCount; }
  public boolean isRead() { return read; }
  public String getSnippet() { return snippet; }
  public long getSnippetType() { return snippetType; }
  public String getSnippetUri() { return snippetUri; }
  public int getDistributionType() { return distributionType; }
  public boolean isArchived() { return archived; }
  public int getStatus() { return status; }
  public long getLastSeen() { return lastSeen; }
}