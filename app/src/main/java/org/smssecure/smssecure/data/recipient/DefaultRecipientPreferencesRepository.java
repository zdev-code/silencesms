package org.smssecure.smssecure.data.recipient;

import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.smssecure.smssecure.color.MaterialColor;
import org.smssecure.smssecure.database.DatabaseContentProviders;
import org.smssecure.smssecure.database.DatabaseFactory;
import org.smssecure.smssecure.database.RecipientPreferenceDatabase;
import org.smssecure.smssecure.database.RecipientPreferenceDatabase.VibrateState;
import org.smssecure.smssecure.recipients.RecipientFactory;
import org.smssecure.smssecure.recipients.Recipients;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor.TaskHandle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DefaultRecipientPreferencesRepository implements RecipientPreferencesRepository {
  private final Context context;
  private final AppTaskExecutor executor;
  private final RecipientPreferenceDatabase database;

  public DefaultRecipientPreferencesRepository(Context context, AppTaskExecutor executor) {
    this.context = Objects.requireNonNull(context).getApplicationContext();
    this.executor = Objects.requireNonNull(executor);
    this.database = DatabaseFactory.getRecipientPreferenceDatabase(this.context);
  }

  @Override
  public Subscription observeBlocked(Listener listener) {
    Objects.requireNonNull(listener);
    AtomicBoolean closed = new AtomicBoolean();
    ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
      @Override public void onChange(boolean selfChange) { loadBlocked(listener, closed); }
    };
    context.getContentResolver().registerContentObserver(
        DatabaseContentProviders.RecipientPreference.CONTENT_URI, true, observer);
    loadBlocked(listener, closed);
    return () -> {
      if (closed.compareAndSet(false, true)) {
        context.getContentResolver().unregisterContentObserver(observer);
      }
    };
  }

  @Override public TaskHandle setRingtone(long[] ids, Uri ringtone, Callback callback) {
    return mutate(ids, recipients -> database.setRingtone(recipients, ringtone), callback);
  }

  @Override public TaskHandle setVibrate(long[] ids, VibrateState vibrate, Callback callback) {
    return mutate(ids, recipients -> database.setVibrate(recipients, vibrate), callback);
  }

  @Override public TaskHandle setMuted(long[] ids, long until, Callback callback) {
    return mutate(ids, recipients -> database.setMuted(recipients, until), callback);
  }

  @Override public TaskHandle setBlocked(long[] ids, boolean blocked, Callback callback) {
    return mutate(ids, recipients -> database.setBlocked(recipients, blocked), callback);
  }

  @Override public TaskHandle setColor(long[] ids, MaterialColor color, Callback callback) {
    return mutate(ids, recipients -> database.setColor(recipients, color), callback);
  }

  private void loadBlocked(Listener listener, AtomicBoolean closed) {
    executor.submitSerial(this::readBlocked,
        result -> { if (!closed.get()) listener.onChanged(result); },
        exception -> { if (!closed.get()) listener.onFailure(exception); });
  }

  private List<long[]> readBlocked() {
    List<long[]> result = new ArrayList<>();
    try (Cursor cursor = database.getBlocked()) {
      while (cursor.moveToNext()) {
        String[] values = cursor.getString(1).trim().split("\\s+");
        long[] ids = new long[values.length];
        for (int index = 0; index < values.length; index++) ids[index] = Long.parseLong(values[index]);
        result.add(ids);
      }
    }
    return result;
  }

  private TaskHandle mutate(long[] ids, Mutation mutation, Callback callback) {
    long[] copy = Arrays.copyOf(Objects.requireNonNull(ids), ids.length);
    Objects.requireNonNull(callback);
    return executor.submitSerial(() -> {
      mutation.apply(RecipientFactory.getRecipientsForIds(context, copy, true));
      return null;
    }, ignored -> callback.onComplete(), callback::onFailure);
  }

  private interface Mutation {
    void apply(Recipients recipients);
  }
}