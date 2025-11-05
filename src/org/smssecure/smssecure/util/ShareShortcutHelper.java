package org.smssecure.smssecure.util;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.Person;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;

import org.smssecure.smssecure.R;
import org.smssecure.smssecure.ShareActivity;
import org.smssecure.smssecure.crypto.MasterCipher;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.database.DatabaseFactory;
import org.smssecure.smssecure.database.ThreadDatabase;
import org.smssecure.smssecure.database.model.ThreadRecord;
import org.smssecure.smssecure.recipients.Recipients;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Publishes ShareSheet shortcuts for recent conversations using the Share Shortcut API.
 */
public final class ShareShortcutHelper {

  private static final String TAG = ShareShortcutHelper.class.getSimpleName();
  private static final String SHARE_CATEGORY = "org.smssecure.smssecure.SHARE_TARGET";
  private static final int MAX_SHORTCUTS = 4;

  private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
    Thread thread = new Thread(runnable, "ShareShortcutHelper");
    thread.setPriority(Thread.MIN_PRIORITY);
    return thread;
  });

  private ShareShortcutHelper() {
  }

  public static void publishShareShortcuts(@NonNull Context context, @NonNull MasterSecret masterSecret) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) {
      return;
    }

    final Context appContext = context.getApplicationContext();
    EXECUTOR.execute(() -> publishInternal(appContext, masterSecret));
  }

  private static void publishInternal(@NonNull Context context, @NonNull MasterSecret masterSecret) {
    MasterCipher masterCipher = new MasterCipher(masterSecret);
    ThreadDatabase threadDatabase = DatabaseFactory.getThreadDatabase(context);

    Cursor cursor = null;
    ThreadDatabase.Reader reader = null;
    List<ShortcutInfoCompat> shortcuts = new ArrayList<>();

    try {
      cursor = threadDatabase.getDirectShareList();
      reader = threadDatabase.readerFor(cursor, masterCipher);

      ThreadRecord record;
      while ((record = reader.getNext()) != null && shortcuts.size() < MAX_SHORTCUTS) {
        if (record.isArchived()) {
          continue;
        }

        Recipients recipients = record.getRecipients();
        if (recipients == null) {
          continue;
        }

        String shortLabel = recipients.toShortString();
        if (shortLabel == null || shortLabel.trim().isEmpty()) {
          shortLabel = context.getString(R.string.app_name);
        }

        Intent intent = new Intent(context, ShareActivity.class)
            .setAction(Intent.ACTION_DEFAULT)
            .putExtra(ShareActivity.EXTRA_THREAD_ID, record.getThreadId())
            .putExtra(ShareActivity.EXTRA_RECIPIENT_IDS, recipients.getIds())
            .putExtra(ShareActivity.EXTRA_DISTRIBUTION_TYPE, record.getDistributionType())
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);

        IconCompat icon = buildIcon(context, recipients);
        Person person = new Person.Builder()
            .setName(shortLabel)
            .build();

        ShortcutInfoCompat shortcut = new ShortcutInfoCompat.Builder(context, buildShortcutId(record))
            .setShortLabel(shortLabel)
            .setLongLabel(shortLabel)
            .setIcon(icon)
            .setIntent(intent)
            .setCategories(Collections.singleton(SHARE_CATEGORY))
            .setPerson(person)
            .build();

        shortcuts.add(shortcut);
      }
    } catch (Exception e) {
      Log.w(TAG, "Unable to publish share shortcuts", e);
    } finally {
      if (reader != null) {
        reader.close();
      } else if (cursor != null) {
        cursor.close();
      }
    }

    try {
      ShortcutManagerCompat.removeAllDynamicShortcuts(context);
      if (!shortcuts.isEmpty()) {
        ShortcutManagerCompat.addDynamicShortcuts(context, shortcuts);
      }
    } catch (Exception e) {
      Log.w(TAG, "Failed to update dynamic share shortcuts", e);
    }
  }

  private static IconCompat buildIcon(@NonNull Context context, @NonNull Recipients recipients) {
    try {
      Drawable drawable = recipients.getContactPhoto().asDrawable(context,
          recipients.getColor().toConversationColor(context));
      Bitmap bitmap = drawable != null ? BitmapUtil.createFromDrawable(drawable, 192, 192) : null;
      if (bitmap != null) {
        return IconCompat.createWithBitmap(bitmap);
      }
    } catch (Exception e) {
      Log.w(TAG, "Falling back to default share shortcut icon", e);
    }

    return IconCompat.createWithResource(context, R.drawable.icon);
  }

  private static String buildShortcutId(@NonNull ThreadRecord record) {
    return "share_thread_" + record.getThreadId();
  }
}
