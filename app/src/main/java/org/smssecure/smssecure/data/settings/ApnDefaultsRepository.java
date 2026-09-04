package org.smssecure.smssecure.data.settings;

import org.smssecure.smssecure.mms.LegacyMmsConnection;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor.TaskHandle;

public interface ApnDefaultsRepository {
  TaskHandle load(Callback callback);

  interface Callback {
    void onSuccess(LegacyMmsConnection.Apn apn);
    void onFailure(Exception exception);
  }
}