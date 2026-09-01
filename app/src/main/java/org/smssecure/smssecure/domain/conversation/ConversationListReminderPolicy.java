package org.smssecure.smssecure.domain.conversation;

import org.smssecure.smssecure.util.concurrent.AppTaskExecutor.TaskHandle;

public interface ConversationListReminderPolicy {
  enum Kind { NONE, DEFAULT_SMS, SYSTEM_SMS_IMPORT, DELIVERY_REPORTS, STORE_RATING }

  TaskHandle refresh(Callback callback);

  interface Callback {
    void onResult(Kind kind);
    void onFailure(Exception exception);
  }
}