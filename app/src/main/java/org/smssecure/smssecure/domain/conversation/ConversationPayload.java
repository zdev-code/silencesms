package org.smssecure.smssecure.domain.conversation;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;

public final class ConversationPayload {
  private final long threadId;
  private final long[] recipientIds;
  private final int distributionType;
  private final String text;
  private final Uri media;
  private final String mediaType;

  public ConversationPayload(long threadId, @NonNull long[] recipientIds, int distributionType,
                             @Nullable String text, @Nullable Uri media,
                             @Nullable String mediaType) {
    this.threadId = threadId;
    this.recipientIds = Objects.requireNonNull(recipientIds).clone();
    this.distributionType = distributionType;
    this.text = text;
    this.media = media;
    this.mediaType = mediaType;
  }

  public long getThreadId() { return threadId; }
  public long[] getRecipientIds() { return recipientIds.clone(); }
  public int getDistributionType() { return distributionType; }
  public @Nullable String getText() { return text; }
  public @Nullable Uri getMedia() { return media; }
  public @Nullable String getMediaType() { return mediaType; }
}