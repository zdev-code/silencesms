package org.smssecure.smssecure.data.settings;

import org.smssecure.smssecure.domain.conversation.ConversationUnlockCapability;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor.TaskHandle;

public interface NotificationRefreshRepository {
  TaskHandle refresh(ConversationUnlockCapability unlockCapability, Callback callback);

  interface Callback {
    void onComplete();
    void onFailure(Exception exception);
  }
}