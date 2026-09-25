package org.smssecure.smssecure.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.SmsMessage;
import android.util.Log;

import org.smssecure.smssecure.ApplicationContext;
import org.smssecure.smssecure.jobs.SmsSentJob;
import org.smssecure.smssecure.jobs.SmsAttemptResultJob;
import org.smssecure.smssecure.jobs.sms.SmsAttemptCallbacks;
import org.whispersystems.jobqueue.JobManager;

public class SmsDeliveryListener extends BroadcastReceiver {

  private static final String TAG = SmsDeliveryListener.class.getSimpleName();

  public static final String SENT_SMS_ACTION      = "org.smssecure.smssecure.SendReceiveService.SENT_SMS_ACTION";
  public static final String DELIVERED_SMS_ACTION = "org.smssecure.smssecure.SendReceiveService.DELIVERED_SMS_ACTION";

  @Override
  public void onReceive(Context context, Intent intent) {
    if (intent == null || intent.getAction() == null) return;
    JobManager jobManager = ApplicationContext.getInstance(context).getJobManager();
    long       messageId  = intent.getLongExtra("message_id", -1);

    if (intent.hasExtra(SmsAttemptCallbacks.ATTEMPT_ID) ||
        intent.hasExtra(SmsAttemptCallbacks.PART_INDEX) ||
        (intent.getData() != null && "sms-attempt".equals(intent.getData().getScheme()))) {
      String attemptId = intent.getStringExtra(SmsAttemptCallbacks.ATTEMPT_ID);
      int attemptNumber = intent.getIntExtra(SmsAttemptCallbacks.ATTEMPT_NUMBER, -1);
      int partIndex = intent.getIntExtra(SmsAttemptCallbacks.PART_INDEX, -1);
      int partCount = intent.getIntExtra(SmsAttemptCallbacks.PART_COUNT, -1);
        boolean sent = SENT_SMS_ACTION.equals(intent.getAction());
        if (!SmsAttemptCallbacks.isValid(intent)) {
        Log.w(TAG, "Invalid attempt callback metadata");
        return;
      }
      int result;
      if (sent) {
        result = getResultCode();
      } else {
        byte[] pdu = intent.getByteArrayExtra("pdu");
        if (pdu == null) {
          Log.w(TAG, "No PDU in attempt delivery receipt");
          return;
        }
        SmsMessage message = SmsMessage.createFromPdu(pdu, intent.getStringExtra("format"));
        if (message == null) {
          Log.w(TAG, "Invalid attempt delivery receipt");
          return;
        }
        result = message.getStatus();
      }
      long callbackAt = System.currentTimeMillis();
      try {
        SmsAttemptResultJob.persistEvidence(context, attemptId, attemptNumber, messageId,
            partIndex, partCount, intent.getAction(), result, callbackAt);
      } catch (RuntimeException error) {
        Log.w(TAG, "Unable to persist SMS callback evidence immediately", error);
      }
      jobManager.add(new SmsAttemptResultJob(context, attemptId, attemptNumber, messageId,
          partIndex, partCount, intent.getAction(), result, callbackAt));
      return;
    }

    switch (intent.getAction()) {
      case SENT_SMS_ACTION:
        int result = getResultCode();

        jobManager.add(new SmsSentJob(context, messageId, SENT_SMS_ACTION, result));
        break;
      case DELIVERED_SMS_ACTION:
        byte[] pdu = intent.getByteArrayExtra("pdu");
        String format = intent.getStringExtra("format");

        if (pdu == null) {
          Log.w(TAG, "No PDU in delivery receipt!");
          break;
        }

        SmsMessage message = SmsMessage.createFromPdu(pdu, format);

        if (message == null) {
          Log.w(TAG, "Delivery receipt failed to parse!");
          break;
        }

        jobManager.add(new SmsSentJob(context, messageId, DELIVERED_SMS_ACTION, message.getStatus()));
        break;
      default:
        Log.w(TAG, "Unknown action: " + intent.getAction());
    }
  }
}
