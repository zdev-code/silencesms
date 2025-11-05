package org.smssecure.smssecure.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.smssecure.smssecure.WelcomeActivity;

public class BootReceiver extends BroadcastReceiver {
  private static final String TAG = BootReceiver.class.getSimpleName();
  private static final String ACTION_RESTART = "org.smssecure.smssecure.RESTART";

  @Override
  public void onReceive(Context context, Intent intent) {
    if (intent == null) {
      Log.w(TAG, "Received null intent");
      return;
    }

    String action = intent.getAction();

    if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
        Intent.ACTION_MY_PACKAGE_REPLACED.equals(action) ||
        ACTION_RESTART.equals(action)) {
      Log.w(TAG, "onReceive(): " + action);
      WelcomeActivity.checkForPermissions(context, intent);
    } else {
      Log.w(TAG, "Ignoring unexpected action: " + action);
    }
  }

}
