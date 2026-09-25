package org.smssecure.smssecure.jobs.sms;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.smssecure.smssecure.service.SmsDeliveryListener;

import java.util.ArrayList;

public final class SmsAttemptCallbacks {
  public static final String ATTEMPT_ID = "sms_attempt_id";
  public static final String PART_INDEX = "sms_part_index";
  public static final String PART_COUNT = "sms_part_count";
  public static final String ATTEMPT_NUMBER = "sms_attempt_number";

  private SmsAttemptCallbacks() {}

  public static boolean isValid(Intent callback) {
    if (callback == null) return false;
    String attemptId = callback.getStringExtra(ATTEMPT_ID);
    int attemptNumber = callback.getIntExtra(ATTEMPT_NUMBER, -1);
    long messageId = callback.getLongExtra("message_id", -1);
    int partIndex = callback.getIntExtra(PART_INDEX, -1);
    int partCount = callback.getIntExtra(PART_COUNT, -1);
    boolean delivery = SmsDeliveryListener.DELIVERED_SMS_ACTION.equals(callback.getAction());
    if (attemptId == null || attemptId.isEmpty() || attemptNumber < 1 || messageId < 0 ||
        partCount < 1 || partIndex < 0 || partIndex >= partCount ||
        (!delivery && !SmsDeliveryListener.SENT_SMS_ACTION.equals(callback.getAction()))) return false;
    return identity(attemptId, attemptNumber, messageId, partIndex, delivery)
        .equals(callback.getData());
  }

  private static Uri identity(String attemptId, int attemptNumber, long messageId,
                              int index, boolean delivery) {
    return new Uri.Builder().scheme("sms-attempt").authority(attemptId)
        .appendPath(delivery ? "delivery" : "sent").appendPath(Integer.toString(attemptNumber))
        .appendPath(Long.toString(messageId)).appendPath(Integer.toString(index)).build();
  }

  public static ArrayList<PendingIntent> create(Context context, String attemptId, int attemptNumber,
                                                 long messageId, int partCount, boolean delivery) {
    if (attemptId == null || attemptId.isEmpty() || attemptNumber < 1 || partCount < 1) {
      throw new IllegalArgumentException("Invalid callback metadata");
    }
    ArrayList<PendingIntent> callbacks = new ArrayList<>(partCount);
    for (int index = 0; index < partCount; index++) {
      callbacks.add(PendingIntent.getBroadcast(context, 0,
          intent(context, attemptId, attemptNumber, messageId, index, partCount, delivery),
          delivery ? PendingIntent.FLAG_MUTABLE : PendingIntent.FLAG_IMMUTABLE));
    }
    return callbacks;
  }

  public static Intent intent(Context context, String attemptId, int attemptNumber, long messageId,
                              int index, int count, boolean delivery) {
    if (attemptId == null || attemptId.isEmpty() || attemptNumber < 1 || count < 1 || index < 0 || index >= count) {
      throw new IllegalArgumentException("Invalid callback metadata");
    }
    String action = delivery ? SmsDeliveryListener.DELIVERED_SMS_ACTION : SmsDeliveryListener.SENT_SMS_ACTION;
    return new Intent(action, identity(attemptId, attemptNumber, messageId, index, delivery),
      context, SmsDeliveryListener.class)
        .putExtra(ATTEMPT_ID, attemptId)
        .putExtra(ATTEMPT_NUMBER, attemptNumber)
        .putExtra(PART_INDEX, index)
        .putExtra(PART_COUNT, count)
        .putExtra("message_id", messageId);
  }
}