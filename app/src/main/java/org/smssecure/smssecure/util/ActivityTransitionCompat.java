package org.smssecure.smssecure.util;

import android.app.Activity;
import android.os.Build;

public final class ActivityTransitionCompat {

  private ActivityTransitionCompat() {}

  public static void overrideOpen(Activity activity, int enterAnimation, int exitAnimation) {
    if (Build.VERSION.SDK_INT >= 34) {
      activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, enterAnimation, exitAnimation);
    } else {
      overridePendingTransition(activity, enterAnimation, exitAnimation);
    }
  }

  public static void overrideClose(Activity activity, int enterAnimation, int exitAnimation) {
    if (Build.VERSION.SDK_INT >= 34) {
      activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, enterAnimation, exitAnimation);
    } else {
      overridePendingTransition(activity, enterAnimation, exitAnimation);
    }
  }

  @SuppressWarnings("deprecation")
  private static void overridePendingTransition(Activity activity, int enterAnimation, int exitAnimation) {
    activity.overridePendingTransition(enterAnimation, exitAnimation);
  }
}