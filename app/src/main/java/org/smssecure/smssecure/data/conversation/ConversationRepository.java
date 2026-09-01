package org.smssecure.smssecure.data.conversation;

import org.smssecure.smssecure.util.concurrent.AppTaskExecutor.TaskHandle;
import org.smssecure.smssecure.domain.conversation.ConversationUnlockCapability;

import java.util.Set;

public interface ConversationRepository {
  Subscription observe(ConversationListQuery query, Observer observer);
  TaskHandle archive(Set<Long> threadIds, MutationCallback callback);
  TaskHandle unarchive(Set<Long> threadIds, MutationCallback callback);
  TaskHandle delete(Set<Long> threadIds, ConversationUnlockCapability unlockCapability,
                    MutationCallback callback);
  TaskHandle setArchivedFromSwipe(long threadId, boolean archived, boolean updateReadState,
                                  ConversationUnlockCapability unlockCapability,
                                  MutationCallback callback);

  interface Subscription extends AutoCloseable {
    void refresh();
    @Override void close();
  }

  interface Observer {
    void onSnapshot(ConversationListSnapshot snapshot);
    void onError(Exception exception);
  }

  interface MutationCallback {
    void onSuccess();
    void onFailure(Exception exception);
  }
}