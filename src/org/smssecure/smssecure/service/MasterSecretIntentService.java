package org.smssecure.smssecure.service;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.smssecure.smssecure.crypto.MasterSecret;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public abstract class MasterSecretIntentService extends Service {

  private final ExecutorService executor = Executors.newSingleThreadExecutor();

  @Override
  public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
    if (intent != null) {
      executor.execute(() -> {
        try {
          onHandleIntent(intent, KeyCachingService.getMasterSecret(this));
        } finally {
          stopSelf(startId);
        }
      });
    } else {
      stopSelf(startId);
    }

    return START_NOT_STICKY;
  }

  @Override
  public void onDestroy() {
    executor.shutdownNow();
    super.onDestroy();
  }

  @Override
  public @Nullable IBinder onBind(@NonNull Intent intent) {
    return null;
  }

  protected abstract void onHandleIntent(Intent intent, @Nullable MasterSecret masterSecret);
}
