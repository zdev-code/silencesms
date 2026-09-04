package org.smssecure.smssecure.data.conversationscreen;

import org.smssecure.smssecure.database.DraftDatabase.Drafts;
import org.smssecure.smssecure.domain.conversation.ConversationUnlockCapability;
import org.smssecure.smssecure.mms.SlideDeck;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor.TaskHandle;

import java.util.List;
import java.util.Optional;

public interface ConversationScreenRepository {
  TaskHandle setMuted(long[] recipientIds, long until, Callback<Void> callback);
  TaskHandle setBlocked(long[] recipientIds, boolean blocked, Callback<Void> callback);
  TaskHandle findExistingThread(long[] recipientIds, Callback<Long> callback);
  TaskHandle getOrCreateThread(long[] recipientIds, int distributionType, Callback<Long> callback);
  TaskHandle setDistributionType(long threadId, int distributionType, Callback<Void> callback);
  TaskHandle deleteThread(long threadId, Callback<Void> callback);
  TaskHandle setArchived(long threadId, boolean archived, Callback<Void> callback);
  TaskHandle loadDefaultSubscription(long[] recipientIds, Callback<Optional<Integer>> callback);
  TaskHandle restoreDrafts(long threadId, ConversationUnlockCapability unlockCapability,
                           Callback<List<org.smssecure.smssecure.database.DraftDatabase.Draft>> callback);
  TaskHandle saveDrafts(long threadId, long[] recipientIds, int distributionType, Drafts drafts,
                        ConversationUnlockCapability unlockCapability, Callback<Long> callback);
  TaskHandle markRead(long threadId, ConversationUnlockCapability unlockCapability,
                      Callback<Void> callback);
  TaskHandle markLastSeen(long threadId, Callback<Void> callback);
  TaskHandle setDefaultSubscription(long[] recipientIds, int subscriptionId, Callback<Void> callback);
  TaskHandle loadMmsCapability(Callback<Boolean> callback);
  TaskHandle sendText(TextSendRequest request, ConversationUnlockCapability unlockCapability,
                      Callback<Long> callback);
  TaskHandle sendMedia(MediaSendRequest request, ConversationUnlockCapability unlockCapability,
                       Callback<Long> callback);

  interface Callback<T> {
    void onSuccess(T result);
    void onFailure(Exception exception);
  }

  final class TextSendRequest {
    private final long[] recipientIds;
    private final String body;
    private final boolean secure;
    private final int subscriptionId;
    private final long threadId;

    public TextSendRequest(long[] recipientIds, String body, boolean secure,
                           int subscriptionId, long threadId) {
      if (recipientIds == null || recipientIds.length == 0) throw new IllegalArgumentException("Recipients are required");
      if (body == null) throw new NullPointerException("body");
      if (threadId == 0 || threadId < -1) throw new IllegalArgumentException("Invalid thread ID");
      this.recipientIds = recipientIds.clone();
      this.body = body;
      this.secure = secure;
      this.subscriptionId = subscriptionId;
      this.threadId = threadId;
    }

    public long[] getRecipientIds() { return recipientIds.clone(); }
    public String getBody() { return body; }
    public boolean isSecure() { return secure; }
    public int getSubscriptionId() { return subscriptionId; }
    public long getThreadId() { return threadId; }
  }

  final class MediaSendRequest {
    private final long[] recipientIds;
    private final SlideDeck slideDeck;
    private final String body;
    private final long sentTimeMillis;
    private final int subscriptionId;
    private final int distributionType;
    private final boolean secure;
    private final long threadId;

    public MediaSendRequest(long[] recipientIds, SlideDeck slideDeck, String body,
                            long sentTimeMillis, int subscriptionId, int distributionType,
                            boolean secure, long threadId) {
      if (recipientIds == null || recipientIds.length == 0) throw new IllegalArgumentException("Recipients are required");
      if (slideDeck == null) throw new NullPointerException("slideDeck");
      if (body == null) throw new NullPointerException("body");
      if (threadId == 0 || threadId < -1) throw new IllegalArgumentException("Invalid thread ID");
      this.recipientIds = recipientIds.clone();
      this.slideDeck = slideDeck;
      this.body = body;
      this.sentTimeMillis = sentTimeMillis;
      this.subscriptionId = subscriptionId;
      this.distributionType = distributionType;
      this.secure = secure;
      this.threadId = threadId;
    }

    public long[] getRecipientIds() { return recipientIds.clone(); }
    public SlideDeck getSlideDeck() { return slideDeck; }
    public String getBody() { return body; }
    public long getSentTimeMillis() { return sentTimeMillis; }
    public int getSubscriptionId() { return subscriptionId; }
    public int getDistributionType() { return distributionType; }
    public boolean isSecure() { return secure; }
    public long getThreadId() { return threadId; }
  }
}
