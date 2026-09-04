package org.smssecure.smssecure.ui.groupcreate;

import java.util.Objects;

public final class GroupMember {
  private final long recipientId;
  private final String name;
  private final String number;

  public GroupMember(long recipientId, String name, String number) {
    if (recipientId <= 0) throw new IllegalArgumentException("recipientId must be positive");
    this.recipientId = recipientId;
    this.name = Objects.requireNonNullElse(name, "");
    this.number = Objects.requireNonNullElse(number, "");
  }

  public long getRecipientId() { return recipientId; }
  public String getName() { return name; }
  public String getNumber() { return number; }
}