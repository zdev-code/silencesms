package org.smssecure.smssecure.util;

import android.content.Context;
import android.os.Build;
import android.telephony.SmsManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

/**
 * Helper for retrieving {@link SmsManager} instances without relying on deprecated static
 * factory methods on modern Android versions, while still supporting legacy releases.
 */
public final class SmsManagerUtil {

  private static final String TAG = SmsManagerUtil.class.getSimpleName();

  private SmsManagerUtil() {}

  public static @NonNull SmsManager getSystemSmsManager(@NonNull Context context) {
    SmsManager smsManager = null;

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      smsManager = context.getSystemService(SmsManager.class);
    }

    if (smsManager == null) {
      smsManager = legacyGetDefault();
    }

    return smsManager;
  }

  public static @NonNull SmsManager getSystemSmsManager(@NonNull Context context, int subscriptionId) {
    if (subscriptionId == -1) {
      return getSystemSmsManager(context);
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      SmsManager smsManager = getSystemSmsManager(context);
      SmsManager forSubscription = tryCreateForSubscription(smsManager, subscriptionId);
      if (forSubscription != null) {
        return forSubscription;
      }
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
      return legacyGetForSubscriptionId(subscriptionId);
    }

    return getSystemSmsManager(context);
  }

  @RequiresApi(Build.VERSION_CODES.S)
  private static @Nullable SmsManager tryCreateForSubscription(@NonNull SmsManager smsManager, int subscriptionId) {
    try {
      return smsManager.createForSubscriptionId(subscriptionId);
    } catch (NoSuchMethodError | RuntimeException exception) {
      Log.w(TAG, "Unable to create SmsManager for subscription " + subscriptionId, exception);
      return null;
    }
  }

  @SuppressWarnings("deprecation")
  private static @NonNull SmsManager legacyGetDefault() {
    return SmsManager.getDefault();
  }

  @SuppressWarnings("deprecation")
  private static @NonNull SmsManager legacyGetForSubscriptionId(int subscriptionId) {
    return SmsManager.getSmsManagerForSubscriptionId(subscriptionId);
  }
}
