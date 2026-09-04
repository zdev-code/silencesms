package org.smssecure.smssecure.data.recipient;

import android.net.Uri;

import org.smssecure.smssecure.color.MaterialColor;
import org.smssecure.smssecure.database.RecipientPreferenceDatabase.VibrateState;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor.TaskHandle;

import java.util.List;

public interface RecipientPreferencesRepository {
  interface Listener {
    void onChanged(List<long[]> blockedRecipientIds);
    void onFailure(Exception exception);
  }

  interface Subscription extends AutoCloseable {
    @Override void close();
  }

  Subscription observeBlocked(Listener listener);
  TaskHandle setRingtone(long[] recipientIds, Uri ringtone, Callback callback);
  TaskHandle setVibrate(long[] recipientIds, VibrateState vibrate, Callback callback);
  TaskHandle setMuted(long[] recipientIds, long until, Callback callback);
  TaskHandle setBlocked(long[] recipientIds, boolean blocked, Callback callback);
  TaskHandle setColor(long[] recipientIds, MaterialColor color, Callback callback);

  interface Callback {
    void onComplete();
    void onFailure(Exception exception);
  }
}