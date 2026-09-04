package org.smssecure.smssecure;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import org.smssecure.smssecure.notifications.NotificationChannels;
import org.smssecure.smssecure.util.SilencePreferences;
import org.smssecure.smssecure.util.Util;

public final class WelcomePermissions {
  private static final String TAG = WelcomePermissions.class.getSimpleName();
  private static final int NOTIFICATION_ID = 1339;

  private WelcomePermissions() {}

  public static void checkForPermissions(Context context, Intent intent) {
    if (intent == null) return;

    boolean missingMandatoryPermissions = !Util.hasMandatoryPermissions(context);
    boolean missingNotificationPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED;

    if ((missingMandatoryPermissions || missingNotificationPermission) &&
        !SilencePreferences.isFirstRun(context)) {
      displayPermissionsNotification(context);
    }
  }

  @SuppressLint({"MissingPermission", "NotificationPermission"})
  private static void displayPermissionsNotification(Context context) {
    Intent targetIntent = context.getPackageManager()
        .getLaunchIntentForPackage(context.getPackageName());
    Notification notification = new NotificationCompat.Builder(context, NotificationChannels.OTHER)
        .setPriority(NotificationCompat.PRIORITY_MAX)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setSmallIcon(R.drawable.icon_notification)
        .setColor(ContextCompat.getColor(context, R.color.silence_primary))
        .setContentTitle(context.getString(R.string.WelcomeActivity_action_required))
        .setContentText(context.getString(
            R.string.WelcomeActivity_you_need_to_grant_some_permissions_to_silence))
        .setStyle(new NotificationCompat.BigTextStyle().bigText(context.getString(
            R.string.WelcomeActivity_you_need_to_grant_some_permissions_to_silence_in_order_to_continue_to_use_it)))
        .setAutoCancel(false)
        .setContentIntent(PendingIntent.getActivity(context, 0, targetIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
        .build();

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) {
      Log.w(TAG, "Skipping permissions notification; missing POST_NOTIFICATIONS permission");
      return;
    }

    NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
    if (!notificationManager.areNotificationsEnabled()) {
      Log.w(TAG, "Skipping permissions notification; notifications disabled by user");
      return;
    }
    notificationManager.notify(NOTIFICATION_ID, notification);
  }
}