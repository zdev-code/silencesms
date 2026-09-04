package org.smssecure.smssecure.ui.blocked;

import java.util.Arrays;

public final class BlockedContactEntry {
  private final long[] recipientIds;

  public BlockedContactEntry(long[] recipientIds) {
    this.recipientIds = Arrays.copyOf(recipientIds, recipientIds.length);
  }

  public long[] getRecipientIds() { return Arrays.copyOf(recipientIds, recipientIds.length); }
}