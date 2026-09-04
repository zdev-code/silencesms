package org.smssecure.smssecure.backup;

import android.app.NotificationManager;
import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.hilt.work.HiltWorker;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.smssecure.smssecure.R;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.database.EncryptedBackupExporter;
import org.smssecure.smssecure.notifications.NotificationChannels;
import org.smssecure.smssecure.service.KeyCachingService;

import java.io.IOException;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedInject;

@HiltWorker
public final class AutomaticBackupWorker extends Worker {
  private static final int RETAINED_BACKUPS = 5;
  private static final int FAILURE_NOTIFICATION_ID = 9051;
  private static final String BACKUP_PREFIX = "Silence-";
  private static final String BACKUP_SUFFIX = ".silencebackup";
  private static final String PARTIAL_SUFFIX = BACKUP_SUFFIX + ".partial";

  @AssistedInject
  public AutomaticBackupWorker(@Assisted @NonNull Context context,
                               @Assisted @NonNull WorkerParameters parameters) {
    super(context, parameters);
  }

  @NonNull
  @Override
  public Result doWork() {
    Context context = getApplicationContext();
    if (!AutomaticBackupManager.isEnabled(context)) return Result.success();

    MasterSecret masterSecret = KeyCachingService.getMasterSecret(context);
    if (masterSecret == null) return Result.retry();

    byte[] recoveryKey = null;
    DocumentFile backup = null;
    try {
      Uri destination = AutomaticBackupManager.getDestination(context);
      DocumentFile directory = DocumentFile.fromTreeUri(context, destination);
      if (directory == null || !directory.canWrite()) throw new IOException("Backup directory is unavailable");
      cleanInterruptedBackups(directory);

      String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
      String finalName = BACKUP_PREFIX + timestamp + BACKUP_SUFFIX;
      backup = directory.createFile("application/octet-stream",
                BACKUP_PREFIX + timestamp + PARTIAL_SUFFIX);
      if (backup == null) throw new IOException("Unable to create backup document");

      recoveryKey = AutomaticBackupManager.getRecoveryKey(context);
      try (OutputStream output = context.getContentResolver().openOutputStream(backup.getUri(), "w")) {
        if (output == null) throw new IOException("Unable to open backup document");
        EncryptedBackupExporter.exportToStream(context, masterSecret, recoveryKey, output);
      }
      if (!backup.renameTo(finalName)) throw new IOException("Unable to publish completed backup");
      rotate(directory);
      return Result.success();
    } catch (IOException | GeneralSecurityException error) {
      if (backup != null) backup.delete();
      notifyFailure(context);
      return getRunAttemptCount() < 3 ? Result.retry() : Result.failure();
    } finally {
      if (recoveryKey != null) Arrays.fill(recoveryKey, (byte) 0);
    }
  }

  private static void rotate(DocumentFile directory) {
    DocumentFile[] backups = Arrays.stream(directory.listFiles())
        .filter(file -> file.isFile() && file.getName() != null &&
                        file.getName().startsWith(BACKUP_PREFIX) &&
                        file.getName().endsWith(BACKUP_SUFFIX))
        .sorted(Comparator.comparingLong(DocumentFile::lastModified).reversed())
        .toArray(DocumentFile[]::new);
    for (int index = RETAINED_BACKUPS; index < backups.length; index++) backups[index].delete();
  }

  static void cleanInterruptedBackups(DocumentFile directory) {
    Arrays.stream(directory.listFiles())
        .filter(file -> file.isFile() && file.getName() != null &&
                        file.getName().startsWith(BACKUP_PREFIX) &&
                        file.getName().endsWith(PARTIAL_SUFFIX))
        .forEach(DocumentFile::delete);
  }

  private static void notifyFailure(Context context) {
    NotificationCompat.Builder notification =
        new NotificationCompat.Builder(context, NotificationChannels.FAILURES)
            .setSmallIcon(R.drawable.icon_notification)
            .setContentTitle(context.getString(R.string.AutomaticBackup_failure_title))
            .setContentText(context.getString(R.string.AutomaticBackup_failure_message))
            .setAutoCancel(true);
    context.getSystemService(NotificationManager.class)
           .notify(FAILURE_NOTIFICATION_ID, notification.build());
  }
}