package org.smssecure.smssecure.crypto;

import android.content.Context;
import androidx.annotation.NonNull;

import org.smssecure.smssecure.crypto.storage.SilenceSessionStore;
import org.smssecure.smssecure.recipients.Recipient;
import org.smssecure.smssecure.util.dualsim.SubscriptionInfoCompat;
import org.smssecure.smssecure.util.dualsim.SubscriptionManagerCompat;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.state.SessionStore;

import java.util.List;
import java.util.LinkedList;

public class SessionUtil {

  public static boolean hasSession(Context context, MasterSecret masterSecret, @NonNull String number, int subscriptionId) {
    SessionStore   sessionStore   = new SilenceSessionStore(context, masterSecret, subscriptionId);
    SignalProtocolAddress axolotlAddress = new SignalProtocolAddress(number, 1);

    return sessionStore.containsSession(axolotlAddress);
  }

  public static boolean hasSession(Context context, MasterSecret masterSecret, @NonNull String number, List<SubscriptionInfoCompat> activeSubscriptions) {
    for (SubscriptionInfoCompat subscriptionInfo : activeSubscriptions) {
      if (!hasSession(context, masterSecret, number, subscriptionInfo.getSubscriptionId())) return false;
    }
    return true;
  }

  public static boolean hasAtLeastOneSession(Context context, MasterSecret masterSecret, @NonNull String number, List<SubscriptionInfoCompat> activeSubscriptions) {
    for (SubscriptionInfoCompat subscriptionInfo : activeSubscriptions) {
      if (hasSession(context, masterSecret, number, subscriptionInfo.getSubscriptionId())) return true;
    }
    return false;
  }

  public static List<Integer> getSubscriptionIdWithoutSession(Context context, MasterSecret masterSecret, @NonNull String number, List<SubscriptionInfoCompat> activeSubscriptions) {
  LinkedList<Integer> list = new LinkedList<>();

    for (SubscriptionInfoCompat subscriptionInfo : activeSubscriptions) {
      int subscriptionId = subscriptionInfo.getSubscriptionId();
      if (!hasSession(context, masterSecret, number, subscriptionId)) list.add(subscriptionId);
    }
    return list;
  }
}
