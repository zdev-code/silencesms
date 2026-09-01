package org.smssecure.smssecure.backup;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import org.smssecure.smssecure.crypto.MasterSecretUtil;
import org.smssecure.smssecure.util.Base64;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

public final class AutomaticBackupManager {
  static final String UNIQUE_WORK = "silence-secure-automatic-backup";
  private static final String DESTINATION = "automatic_backup_destination";
  private static final String WRAPPED_RECOVERY_KEY = "automatic_backup_recovery_key";

  private AutomaticBackupManager() {}

  public static void enable(Context context, Uri destination, byte[] recoveryKey)
      throws GeneralSecurityException
  {
    if (!MasterSecretUtil.isDeviceProtectionEnabled(context)) {
      MasterSecretUtil.enableDeviceProtection(context);
    }
    byte[] wrapped = MasterSecretUtil.wrapWithDeviceKey(context, recoveryKey);
    try {
      SharedPreferences preferences = preferences(context);
      if (!preferences.edit()
                      .putString(DESTINATION, destination.toString())
                      .putString(WRAPPED_RECOVERY_KEY, Base64.encodeBytes(wrapped))
                      .commit()) {
        throw new GeneralSecurityException("Unable to save automatic backup settings");
      }
      schedule(context);
    } finally {
      Arrays.fill(wrapped, (byte) 0);
    }
  }

  public static void disable(Context context) {
    preferences(context).edit().remove(DESTINATION).apply();
    WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK);
  }

  public static void clear(Context context) {
    preferences(context).edit().remove(DESTINATION).remove(WRAPPED_RECOVERY_KEY).apply();
    WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK);
  }

  static Uri getDestination(Context context) {
    String value = preferences(context).getString(DESTINATION, "");
    return value.length() == 0 ? null : Uri.parse(value);
  }

  public static byte[] getRecoveryKey(Context context) throws GeneralSecurityException, IOException {
    String encoded = preferences(context).getString(WRAPPED_RECOVERY_KEY, "");
    if (encoded.length() == 0) throw new GeneralSecurityException("Recovery key is missing");
    return MasterSecretUtil.unwrapWithDeviceKey(context, Base64.decode(encoded));
  }

  public static boolean hasRecoveryKey(Context context) {
    return preferences(context).contains(WRAPPED_RECOVERY_KEY);
  }

  public static boolean isEnabled(Context context) {
    return getDestination(context) != null &&
           preferences(context).contains(WRAPPED_RECOVERY_KEY);
  }

  public static void schedule(Context context) {
    Constraints constraints = new Constraints.Builder().setRequiresCharging(true).build();
    PeriodicWorkRequest request =
        new PeriodicWorkRequest.Builder(AutomaticBackupWorker.class, 24, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build();
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        UNIQUE_WORK, ExistingPeriodicWorkPolicy.UPDATE, request);
  }

  private static SharedPreferences preferences(Context context) {
    return context.getSharedPreferences(MasterSecretUtil.DEVICE_PREFERENCES_NAME, 0);
  }
}