package org.smssecure.smssecure.data.settings;

import android.content.Context;

import org.smssecure.smssecure.domain.conversation.ConversationUnlockCapability;
import org.smssecure.smssecure.notifications.MessageNotifier;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor.TaskHandle;

import java.util.Objects;

public final class DefaultNotificationRefreshRepository implements NotificationRefreshRepository {
  private final Context context;
  private final AppTaskExecutor executor;

  public DefaultNotificationRefreshRepository(Context context, AppTaskExecutor executor) {
    this.context = Objects.requireNonNull(context).getApplicationContext();
    this.executor = Objects.requireNonNull(executor);
  }

  @Override public TaskHandle refresh(ConversationUnlockCapability unlockCapability, Callback callback) {
    return executor.submitSerial(
        () -> unlockCapability.use(masterSecret -> {
          MessageNotifier.updateNotification(context, masterSecret);
          return null;
        }),
        ignored -> callback.onComplete(), callback::onFailure);
  }
}