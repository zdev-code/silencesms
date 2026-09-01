package org.smssecure.smssecure.util;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Configuration;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.telephony.TelephonyManager;
import android.telephony.SubscriptionManager;
import android.text.TextUtils;
import android.util.Log;
import android.content.pm.PackageManager;

import androidx.core.content.ContextCompat;

import org.smssecure.smssecure.util.ServiceUtil;

import java.util.Locale;

public class TelephonyUtil {
  private static final String TAG = TelephonyUtil.class.getSimpleName();

  public static TelephonyManager getManager(final Context context) {
    return (TelephonyManager)context.getSystemService(Context.TELEPHONY_SERVICE);
  }

  public static String getMccMnc(final Context context) {
    final TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
    final int configMcc = context.getResources().getConfiguration().mcc;
    final int configMnc = context.getResources().getConfiguration().mnc;
    if (tm.getSimState() == TelephonyManager.SIM_STATE_READY) {
      Log.w(TAG, "Choosing MCC+MNC info from TelephonyManager.getSimOperator()");
      return tm.getSimOperator();
    } else if (!TextUtils.isEmpty(tm.getNetworkOperator())) {
      Log.w(TAG, "Choosing MCC+MNC info from TelephonyManager.getNetworkOperator()");
      return tm.getNetworkOperator();
    } else if (configMcc != 0 && configMnc != 0) {
      Log.w(TAG, "Choosing MCC+MNC info from current context's Configuration");
    return String.format(Locale.US, "%03d%d",
          configMcc,
          configMnc == Configuration.MNC_ZERO ? 0 : configMnc);
    } else {
      return null;
    }
  }

  @SuppressWarnings("deprecation") // Carrier MMS fallback; removal requires the real-carrier validation matrix.
  public static String getApn(final Context context) {
    final ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    android.net.NetworkInfo networkInfo = cm.getNetworkInfo(ConnectivityManager.TYPE_MOBILE_MMS);
    return networkInfo != null ? networkInfo.getExtraInfo() : null;
  }

  public static boolean isMyPhoneNumber(final Context context, String number){
    return PhoneNumberFormatter.areSameNumber(getPhoneNumber(context), number);
  }

  @SuppressLint("HardwareIds")
  public static String getPhoneNumber(final Context context){
    final TelephonyManager tm = (TelephonyManager)context.getSystemService(Context.TELEPHONY_SERVICE);

    if (tm == null) {
      return null;
    }

    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
      Log.w(TAG, "READ_PHONE_STATE permission not granted; returning null phone number");
      return null;
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_NUMBERS) != PackageManager.PERMISSION_GRANTED) {
      Log.w(TAG, "READ_PHONE_NUMBERS permission not granted; returning null phone number");
      return null;
    }

    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        SubscriptionManager subscriptionManager = context.getSystemService(SubscriptionManager.class);
        return subscriptionManager != null
        ? subscriptionManager.getPhoneNumber(SubscriptionManager.getDefaultSubscriptionId())
        : null;
      }

      return getLegacyLine1Number(tm);
    } catch (SecurityException securityException) {
      Log.w(TAG, "Unable to read line1 number", securityException);
      return null;
    }
  }

  @SuppressWarnings("deprecation")
  @SuppressLint("HardwareIds")
  private static String getLegacyLine1Number(TelephonyManager telephonyManager) {
    return telephonyManager.getLine1Number();
  }

  public static boolean isConnectedRoaming(final Context context) {
    ConnectivityManager connectivityManager = ServiceUtil.getConnectivityManager(context);
    Network activeNetwork = connectivityManager.getActiveNetwork();
    NetworkCapabilities capabilities = activeNetwork != null
        ? connectivityManager.getNetworkCapabilities(activeNetwork)
        : null;

    return capabilities != null &&
           capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
           getManager(context).isNetworkRoaming();
  }
}
