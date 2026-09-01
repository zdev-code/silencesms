package org.smssecure.smssecure.notifications;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class LocaleChangedReceiver extends BroadcastReceiver {

  @Override
  public void onReceive(Context context, Intent intent) {
    if (intent == null) {
      return;
    }

    if (Intent.ACTION_LOCALE_CHANGED.equals(intent.getAction())) {
      NotificationChannels.create(context);
    }
  }
}
