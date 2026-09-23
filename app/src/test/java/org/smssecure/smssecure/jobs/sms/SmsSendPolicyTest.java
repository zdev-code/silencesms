package org.smssecure.smssecure.jobs.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import android.app.Activity;
import android.telephony.SmsManager;

import org.junit.Test;

public class SmsSendPolicyTest {
  @Test
  public void onlyReviewedPreSubmissionCodesCanRetry() {
    assertThat(SmsSendResultPolicy.classify(Activity.RESULT_OK))
        .isEqualTo(SmsSendAttempt.Result.SUCCESS);
    assertThat(SmsSendResultPolicy.classify(SmsManager.RESULT_ERROR_NO_SERVICE))
        .isEqualTo(SmsSendAttempt.Result.RETRYABLE_NOT_SUBMITTED);
    assertThat(SmsSendResultPolicy.classify(SmsManager.RESULT_ERROR_RADIO_OFF))
        .isEqualTo(SmsSendAttempt.Result.RETRYABLE_NOT_SUBMITTED);
    assertThat(SmsSendResultPolicy.classify(SmsManager.RESULT_ERROR_NULL_PDU))
        .isEqualTo(SmsSendAttempt.Result.TERMINAL);
    assertThat(SmsSendResultPolicy.classify(SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE))
        .isEqualTo(SmsSendAttempt.Result.TERMINAL);
    assertThat(SmsSendResultPolicy.classify(SmsManager.RESULT_ERROR_GENERIC_FAILURE))
        .isEqualTo(SmsSendAttempt.Result.AMBIGUOUS);
    assertThat(SmsSendResultPolicy.classify(Integer.MAX_VALUE))
        .isEqualTo(SmsSendAttempt.Result.AMBIGUOUS);
  }

  @Test
  public void retryRequiresAllPartsUnsubmittedAndAvailableBudget() {
    assertThat(SmsSendRetryPolicy.canRetry(SmsSendAttempt.Decision.RETRYABLE_FAILURE, 1)).isTrue();
    assertThat(SmsSendRetryPolicy.canRetry(SmsSendAttempt.Decision.RETRYABLE_FAILURE, 2)).isTrue();
    assertThat(SmsSendRetryPolicy.canRetry(SmsSendAttempt.Decision.RETRYABLE_FAILURE, 3)).isFalse();
    assertThat(SmsSendRetryPolicy.canRetry(SmsSendAttempt.Decision.RETRYABLE_FAILURE, 0)).isFalse();
    assertThat(SmsSendRetryPolicy.canRetry(SmsSendAttempt.Decision.PARTIAL_FAILURE, 1)).isFalse();
    assertThat(SmsSendRetryPolicy.canRetry(SmsSendAttempt.Decision.UNKNOWN, 1)).isFalse();
  }

  @Test
  public void retryDelayIsBoundedAndDeterministicWithInjectedJitter() {
    assertThat(SmsSendRetryPolicy.delayMillis(1, 0)).isEqualTo(22_500L);
    assertThat(SmsSendRetryPolicy.delayMillis(1, 0.5)).isEqualTo(30_000L);
    assertThat(SmsSendRetryPolicy.delayMillis(1, 1)).isEqualTo(37_500L);
    assertThat(SmsSendRetryPolicy.delayMillis(2, 0.5)).isEqualTo(120_000L);
    assertThatThrownBy(() -> SmsSendRetryPolicy.delayMillis(3, 0.5))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SmsSendRetryPolicy.delayMillis(1, Double.NaN))
        .isInstanceOf(IllegalArgumentException.class);
  }
}