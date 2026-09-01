package org.smssecure.smssecure.data.conversationscreen;

import org.smssecure.smssecure.database.DraftDatabase.Drafts;
import org.smssecure.smssecure.domain.conversation.ConversationUnlockCapability;
import org.smssecure.smssecure.recipients.Recipients;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor.TaskHandle;

import java.util.List;
import java.util.Optional;

public interface ConversationScreenRepository {
  TaskHandle setMuted(long[] recipientIds, long until, Callback<Void> callback);
  TaskHandle setBlocked(long[] recipientIds, boolean blocked, Callback<Void> callback);
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

  interface Callback<T> {
    void onSuccess(T result);
    void onFailure(Exception exception);
  }
}
