package org.smssecure.smssecure.data.share;

import android.content.Context;
import android.net.Uri;

import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.providers.PersistentBlobProvider;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor.TaskHandle;

import java.io.InputStream;
import java.util.Objects;

public final class DefaultSharePayloadRepository implements SharePayloadRepository {
  private final Context context;
  private final AppTaskExecutor executor;

  public DefaultSharePayloadRepository(Context context, AppTaskExecutor executor) {
    this.context = Objects.requireNonNull(context).getApplicationContext();
    this.executor = Objects.requireNonNull(executor);
  }

  @Override public TaskHandle resolve(MasterSecret masterSecret, Uri source, String mimeType,
                                      Callback callback) {
    return executor.submitSerial(() -> {
      if (source == null || Thread.currentThread().isInterrupted()) return null;
      Uri resolved;
      try (InputStream input = context.getContentResolver().openInputStream(source)) {
        if (input == null) return null;
        resolved = PersistentBlobProvider.getInstance(context).create(masterSecret, input, mimeType);
      }
      if (Thread.currentThread().isInterrupted()) {
        delete(resolved);
        return null;
      }
      return resolved;
    }, callback::onSuccess, callback::onFailure);
  }

  @Override public void delete(Uri resolved) {
    if (resolved != null) PersistentBlobProvider.getInstance(context).delete(resolved);
  }
}