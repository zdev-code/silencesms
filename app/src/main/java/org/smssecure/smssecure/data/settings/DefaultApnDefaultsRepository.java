package org.smssecure.smssecure.data.settings;

import android.content.Context;

import org.smssecure.smssecure.database.ApnDatabase;
import org.smssecure.smssecure.util.TelephonyUtil;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor.TaskHandle;

import java.util.Objects;

public final class DefaultApnDefaultsRepository implements ApnDefaultsRepository {
  private final Context context;
  private final AppTaskExecutor executor;

  public DefaultApnDefaultsRepository(Context context, AppTaskExecutor executor) {
    this.context = Objects.requireNonNull(context).getApplicationContext();
    this.executor = Objects.requireNonNull(executor);
  }

  @Override public TaskHandle load(Callback callback) {
    return executor.submitSerial(
        () -> ApnDatabase.getInstance(context)
            .getDefaultApnParameters(TelephonyUtil.getMccMnc(context), TelephonyUtil.getApn(context)),
        callback::onSuccess, callback::onFailure);
  }
}