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

import java.util.Optional;

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
      return Optional.empty();
    }

    for (SubscriptionInfoCompat subscriptionInfo : getActiveSubscriptionInfoList()) {
      if (subscriptionInfo.getSubscriptionId() == subscriptionId) return Optional.of(subscriptionInfo);
    }

    return Optional.empty();
  }

  public Optional<SubscriptionInfoCompat> getActiveSubscriptionInfoFromDeviceSubscriptionId(int subscriptionId) {
    if (getActiveSubscriptionInfoList().size() <= 0) {
      return Optional.empty();
    }

    for (SubscriptionInfoCompat subscriptionInfo : getActiveSubscriptionInfoList()) {
      if (subscriptionInfo.getDeviceSubscriptionId() == subscriptionId) return Optional.of(subscriptionInfo);
    }

    return Optional.empty();
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

    SubscriptionManager subscriptionManager = context.getSystemService(SubscriptionManager.class);

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
                                                getPhoneNumber(subscriptionManager, subscriptionInfo),
                                                subscriptionInfo.getIccId(),
                                                subscriptionInfo.getSimSlotIndex()+1,
                                                getMcc(subscriptionInfo),
                                                getMnc(subscriptionInfo),
                                                knowThisDisplayNameTwice(subscriptionInfo.getDisplayName())));
    }

    return compatList;
  }

  @SuppressWarnings("deprecation")
  private String getPhoneNumber(SubscriptionManager subscriptionManager, SubscriptionInfo subscriptionInfo) {
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        return subscriptionManager.getPhoneNumber(subscriptionInfo.getSubscriptionId());
      }
      return subscriptionInfo.getNumber();
    } catch (SecurityException securityException) {
      Log.w(TAG, "Unable to read subscription phone number", securityException);
      return null;
    }
  }

  @SuppressWarnings("deprecation")
  private int getMcc(SubscriptionInfo subscriptionInfo) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      return parseOperatorCode(subscriptionInfo.getMccString());
    }
    return subscriptionInfo.getMcc();
  }

  @SuppressWarnings("deprecation")
  private int getMnc(SubscriptionInfo subscriptionInfo) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      return parseOperatorCode(subscriptionInfo.getMncString());
    }
    return subscriptionInfo.getMnc();
  }

  private int parseOperatorCode(String value) {
    if (value == null) return -1;
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException exception) {
      Log.w(TAG, "Unable to parse operator code: " + value, exception);
      return -1;
    }
  }

  public static Optional<Integer> getDefaultMessagingSubscriptionId() {
    if(SmsManager.getDefaultSmsSubscriptionId() < 0) {
      return Optional.empty();
    }

    return Optional.of(SmsManager.getDefaultSmsSubscriptionId());
  }

}
