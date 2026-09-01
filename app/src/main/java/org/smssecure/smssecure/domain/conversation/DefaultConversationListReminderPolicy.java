package org.smssecure.smssecure.domain.conversation;

import android.content.Context;

import org.smssecure.smssecure.components.reminder.DefaultSmsReminder;
import org.smssecure.smssecure.components.reminder.DeliveryReportsReminder;
import org.smssecure.smssecure.components.reminder.StoreRatingReminder;
import org.smssecure.smssecure.components.reminder.SystemSmsImportReminder;
import org.smssecure.smssecure.util.Util;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor.TaskHandle;

import java.util.Objects;

public final class DefaultConversationListReminderPolicy implements ConversationListReminderPolicy {
  private final Context         context;
  private final AppTaskExecutor executor;

  public DefaultConversationListReminderPolicy(Context context, AppTaskExecutor executor) {
    this.context  = Objects.requireNonNull(context).getApplicationContext();
    this.executor = Objects.requireNonNull(executor);
  }

  @Override
  public TaskHandle refresh(Callback callback) {
    Objects.requireNonNull(callback);
    return executor.submitSerial(this::eligibleKind, callback::onResult, callback::onFailure);
  }

  private Kind eligibleKind() {
    if (DefaultSmsReminder.isEligible(context)) return Kind.DEFAULT_SMS;
    if (Util.isDefaultSmsProvider(context) && SystemSmsImportReminder.isEligible(context)) {
      return Kind.SYSTEM_SMS_IMPORT;
    }
    if (DeliveryReportsReminder.isEligible(context)) return Kind.DELIVERY_REPORTS;
    if (StoreRatingReminder.isEligible(context)) return Kind.STORE_RATING;
    return Kind.NONE;
  }
}