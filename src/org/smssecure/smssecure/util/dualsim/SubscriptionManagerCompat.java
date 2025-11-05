package org.smssecure.smssecure.util.dualsim;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;
import android.telephony.SmsManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import org.smssecure.smssecure.util.ServiceUtil;

import org.whispersystems.libsignal.util.guava.Optional;

import java.util.LinkedList;
import java.util.List;

public class SubscriptionManagerCompat {

  private static SubscriptionManagerCompat instance;

  private final Context                      context;
  private       List<String>                 displayNameList;
  private       List<SubscriptionInfoCompat> compatList;
  private static final String TAG = SubscriptionManagerCompat.class.getSimpleName();

  public static SubscriptionManagerCompat from(Context context) {
    if (instance == null) {
      instance = new SubscriptionManagerCompat(context);
    }
    return instance;
  }

  private SubscriptionManagerCompat(Context context) {
    this.context = context.getApplicationContext();
    this.displayNameList = new LinkedList<String>();
  }

  public Optional<SubscriptionInfoCompat> getActiveSubscriptionInfo(int subscriptionId) {
    if (getActiveSubscriptionInfoList().size() <= 0) {
      return Optional.absent();
    }

    for (SubscriptionInfoCompat subscriptionInfo : getActiveSubscriptionInfoList()) {
      if (subscriptionInfo.getSubscriptionId() == subscriptionId) return Optional.of(subscriptionInfo);
    }

    return Optional.absent();
  }

  public Optional<SubscriptionInfoCompat> getActiveSubscriptionInfoFromDeviceSubscriptionId(int subscriptionId) {
    if (getActiveSubscriptionInfoList().size() <= 0) {
      return Optional.absent();
    }

    for (SubscriptionInfoCompat subscriptionInfo : getActiveSubscriptionInfoList()) {
      if (subscriptionInfo.getDeviceSubscriptionId() == subscriptionId) return Optional.of(subscriptionInfo);
    }

    return Optional.absent();
  }

  @RequiresApi(22)
  private void updateDisplayNameList(List<SubscriptionInfo> activeSubscriptions) {
    displayNameList = new LinkedList<String>();

    if (activeSubscriptions != null) {
      for (SubscriptionInfo subscriptionInfo : activeSubscriptions) {
        displayNameList.add(subscriptionInfo.getDisplayName().toString());
      }
    }
  }

  public boolean knowThisDisplayNameTwice(CharSequence displayName) {
    if (displayName == null) return false;

    boolean found = false;

    for (String potentialDisplayName : displayNameList) {
      if (found && potentialDisplayName != null && potentialDisplayName.equals(displayName.toString()))
        return true;
      if (potentialDisplayName != null && potentialDisplayName.equals(displayName.toString()))
        found = true;
    }
    return false;
  }

  public @NonNull List<SubscriptionInfoCompat> getActiveSubscriptionInfoList() {
    if (compatList == null) return updateActiveSubscriptionInfoList();
    return compatList;
  }

  @SuppressLint("HardwareIds")
  public @NonNull List<SubscriptionInfoCompat> updateActiveSubscriptionInfoList() {
    compatList = new LinkedList<>();

    if (Build.VERSION.SDK_INT < 22) {
      TelephonyManager telephonyManager = ServiceUtil.getTelephonyManager(context);

      String lineNumber = null;
  String simSerial  = null;

      if (telephonyManager != null) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
          try {
            lineNumber = telephonyManager.getLine1Number();
          } catch (SecurityException securityException) {
            Log.w(TAG, "Unable to read line1 number", securityException);
          }

        } else {
          Log.w(TAG, "READ_PHONE_STATE permission missing; omitting line number and SIM serial");
        }
      }

      compatList.add(new SubscriptionInfoCompat(context,
                                                -1,
                                                telephonyManager != null ? telephonyManager.getSimOperatorName() : null,
                                                lineNumber,
                                                simSerial,
                                                1,
                                                -1,
                                                -1,
                                                false));
      return compatList;
    }

    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
      Log.w(TAG, "READ_PHONE_STATE permission missing; returning fallback subscription info");

      TelephonyManager telephonyManager = ServiceUtil.getTelephonyManager(context);
      CharSequence displayName = telephonyManager != null ? telephonyManager.getSimOperatorName() : null;

      compatList.add(new SubscriptionInfoCompat(context,
                                                -1,
                                                displayName,
                                                null,
                                                null,
                                                1,
                                                -1,
                                                -1,
                                                false));
      return compatList;
    }

    SubscriptionManager subscriptionManager = SubscriptionManager.from(context);

    if (subscriptionManager == null) {
      Log.w(TAG, "SubscriptionManager is null; returning empty list");
      return compatList;
    }

    List<SubscriptionInfo> subscriptionInfos;

    try {
      subscriptionInfos = subscriptionManager.getActiveSubscriptionInfoList();
    } catch (SecurityException securityException) {
      Log.w(TAG, "Unable to query active subscriptions", securityException);
      return compatList;
    }

    updateDisplayNameList(subscriptionInfos);

    if (subscriptionInfos == null || subscriptionInfos.isEmpty()) {
      return compatList;
    }

    for (SubscriptionInfo subscriptionInfo : subscriptionInfos) {
      compatList.add(new SubscriptionInfoCompat(context,
                                                subscriptionInfo.getSubscriptionId(),
                                                subscriptionInfo.getDisplayName(),
                                                subscriptionInfo.getNumber(),
                                                subscriptionInfo.getIccId(),
                                                subscriptionInfo.getSimSlotIndex()+1,
                                                subscriptionInfo.getMcc(),
                                                subscriptionInfo.getMnc(),
                                                knowThisDisplayNameTwice(subscriptionInfo.getDisplayName())));
    }

    return compatList;
  }

  public static Optional<Integer> getDefaultMessagingSubscriptionId() {
    if (Build.VERSION.SDK_INT < 22) {
      return Optional.absent();
    }
    if(SmsManager.getDefaultSmsSubscriptionId() < 0) {
      return Optional.absent();
    }

    return Optional.of(SmsManager.getDefaultSmsSubscriptionId());
  }

}
