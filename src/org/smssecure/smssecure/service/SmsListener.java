/**
 * Copyright (C) 2011 Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.smssecure.smssecure.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import android.util.Log;

import org.smssecure.smssecure.ApplicationContext;
import org.smssecure.smssecure.jobs.SmsReceiveJob;
import org.smssecure.smssecure.protocol.WirePrefix;
import org.smssecure.smssecure.sms.IncomingTextMessage;
import org.smssecure.smssecure.util.SilencePreferences;
import org.smssecure.smssecure.util.Util;

import java.util.ArrayList;
import java.util.Locale;

public class SmsListener extends BroadcastReceiver {

  private static final String SMS_RECEIVED_ACTION  = Telephony.Sms.Intents.SMS_RECEIVED_ACTION;
  private static final String SMS_DELIVERED_ACTION = Telephony.Sms.Intents.SMS_DELIVER_ACTION;

  private boolean isExemption(SmsMessage message, String messageBody) {

    if (message == null || messageBody == null) return false;

    // ignore CLASS0 ("flash") messages
    if (message.getMessageClass() == SmsMessage.MessageClass.CLASS_0)
      return true;

    // ignore OTP messages from Sparebank1 (Norwegian bank)
    if (messageBody.startsWith("Sparebank1://otp?")) {
      return true;
    }

    return
      message.getOriginatingAddress().length() < 7 &&
  (messageBody.toUpperCase(Locale.ROOT).startsWith("//ANDROID:") || // Sprint Visual Voicemail
       messageBody.startsWith("//BREW:")); //BREW stands for “Binary Runtime Environment for Wireless"
  }

  private SmsMessage getSmsMessageFromIntent(Intent intent) {
    Bundle bundle = intent.getExtras();

    if (bundle == null) return null;

    Object[] pdus = (Object[]) bundle.get("pdus");
    if (pdus == null || pdus.length == 0) return null;

    String format = bundle.getString("format");

    return createSmsMessageFromPdu(pdus[0], format);
  }

  private String getSmsMessageBodyFromIntent(Intent intent) {
    Bundle bundle = intent.getExtras();
    if (bundle == null) return null;

    Object[] pdus = (Object[]) bundle.get("pdus");
    if (pdus == null || pdus.length == 0) return null;

    String format = bundle.getString("format");
    StringBuilder bodyBuilder = new StringBuilder();

    for (Object pdu : pdus) {
      SmsMessage part = createSmsMessageFromPdu(pdu, format);
      if (part != null) bodyBuilder.append(part.getDisplayMessageBody());
    }

    return bodyBuilder.length() == 0 ? null : bodyBuilder.toString();
  }

  private SmsMessage createSmsMessageFromPdu(Object pdu, String format) {
    if (!(pdu instanceof byte[])) return null;

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      return SmsMessage.createFromPdu((byte[]) pdu, format);
    } else {
      return SmsMessage.createFromPdu((byte[]) pdu);
    }
  }

  private boolean isRelevant(Context context, Intent intent) {
    SmsMessage message = getSmsMessageFromIntent(intent);
    String messageBody = getSmsMessageBodyFromIntent(intent);

    if (message == null && messageBody == null)
      return false;

    if (isExemption(message, messageBody))
      return false;

    if (!ApplicationMigrationService.isDatabaseImported(context))
      return false;

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT &&
        SMS_RECEIVED_ACTION.equals(intent.getAction()) &&
        Util.isDefaultSmsProvider(context))
    {
      return false;
    }

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT &&
        SilencePreferences.isInterceptAllSmsEnabled(context))
    {
      return true;
    }

    return WirePrefix.isPrefixedMessage(messageBody);
  }

  @Override
  public void onReceive(Context context, Intent intent) {
    Log.w("SMSListener", "Got SMS broadcast...");

    if ((intent.getAction().equals(SMS_DELIVERED_ACTION)) ||
               (intent.getAction().equals(SMS_RECEIVED_ACTION)) && isRelevant(context, intent))
    {
      Object[] pdus           = (Object[]) intent.getExtras().get("pdus");
      int      subscriptionId = intent.getExtras().getInt("subscription", -1);
      String   format          = intent.getStringExtra("format");

      ApplicationContext.getInstance(context).getJobManager().add(new SmsReceiveJob(context, pdus, subscriptionId, format));

      abortBroadcast();
    }
  }
}
