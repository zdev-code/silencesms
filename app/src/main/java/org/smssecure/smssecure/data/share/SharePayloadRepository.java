package org.smssecure.smssecure.data.share;

import android.net.Uri;

import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor.TaskHandle;

public interface SharePayloadRepository {
  TaskHandle resolve(MasterSecret masterSecret, Uri source, String mimeType, Callback callback);
  void delete(Uri resolved);

  interface Callback {
    void onSuccess(Uri resolved);
    void onFailure(Exception exception);
  }
}