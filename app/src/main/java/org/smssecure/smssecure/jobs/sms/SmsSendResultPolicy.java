package org.smssecure.smssecure.jobs.sms;

import android.app.Activity;
import android.telephony.SmsManager;

public final class SmsSendResultPolicy {
  private SmsSendResultPolicy() {}

  public static SmsSendAttempt.Result classify(int resultCode) {
    switch (resultCode) {
      case Activity.RESULT_OK:
        return SmsSendAttempt.Result.SUCCESS;
      case SmsManager.RESULT_ERROR_NO_SERVICE:
      case SmsManager.RESULT_ERROR_RADIO_OFF:
        return SmsSendAttempt.Result.RETRYABLE_NOT_SUBMITTED;
      case SmsManager.RESULT_ERROR_NULL_PDU:
      case SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE:
        return SmsSendAttempt.Result.TERMINAL;
      default:
        return SmsSendAttempt.Result.AMBIGUOUS;
    }
  }
}