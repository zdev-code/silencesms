package org.smssecure.smssecure.ui.groupcreate;

import java.util.Arrays;
import java.util.List;

public final class GroupCreateUiState {
  public enum Error { NONE, NO_MEMBERS, CREATE_FAILED }

  private final List<GroupMember> members;
  private final boolean creating;
  private final long createdThreadId;
  private final long[] createdRecipientIds;
  private final Error error;

  GroupCreateUiState(List<GroupMember> members, boolean creating, long createdThreadId,
                     long[] createdRecipientIds, Error error) {
    this.members = List.copyOf(members);
    this.creating = creating;
    this.createdThreadId = createdThreadId;
    this.createdRecipientIds = Arrays.copyOf(createdRecipientIds, createdRecipientIds.length);
    this.error = error;
  }

  public List<GroupMember> getMembers() { return members; }
  public boolean isCreating() { return creating; }
  public long getCreatedThreadId() { return createdThreadId; }
  public long[] getCreatedRecipientIds() {
    return Arrays.copyOf(createdRecipientIds, createdRecipientIds.length);
  }
  public Error getError() { return error; }
}