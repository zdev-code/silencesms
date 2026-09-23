package org.smssecure.smssecure.jobs.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.smssecure.smssecure.jobs.sms.SmsSendAttempt.Decision.*;
import static org.smssecure.smssecure.jobs.sms.SmsSendAttempt.Result.*;

import org.junit.Test;

public class SmsSendAttemptTest {
  @Test
  public void waitsForAllPartsBeforeSuccess() {
    SmsSendAttempt attempt = new SmsSendAttempt(2);
    SmsSendAttempt first = attempt.recordResult(1, SUCCESS);

    assertThat(attempt.decision()).isEqualTo(PENDING);
    assertThat(first.decision()).isEqualTo(PENDING);
    assertThat(first.recordResult(0, SUCCESS).decision()).isEqualTo(SUCCEEDED);
  }

  @Test
  public void onlyAllRetryableFailuresAuthorizeRetry() {
    SmsSendAttempt attempt = new SmsSendAttempt(2).recordResult(0, RETRYABLE_NOT_SUBMITTED);

    assertThat(attempt.decision()).isEqualTo(PENDING);
    assertThat(attempt.recordResult(1, RETRYABLE_NOT_SUBMITTED).decision()).isEqualTo(RETRYABLE_FAILURE);
    assertThat(attempt.recordResult(1, TERMINAL).decision()).isEqualTo(TERMINAL_FAILURE);
    assertThat(attempt.recordResult(1, SUCCESS).decision()).isEqualTo(PARTIAL_FAILURE);
    assertThat(attempt.recordResult(1, AMBIGUOUS).decision()).isEqualTo(UNKNOWN);
  }

  @Test
  public void ambiguityNeverBecomesRetryable() {
    assertThat(new SmsSendAttempt(2).recordResult(0, SUCCESS)
                                    .recordResult(1, AMBIGUOUS).decision()).isEqualTo(UNKNOWN);
    assertThat(new SmsSendAttempt(2).recordResult(0, TERMINAL)
                                    .recordResult(1, AMBIGUOUS).decision()).isEqualTo(UNKNOWN);
  }

  @Test
  public void duplicatesAreIdempotentAndConflictsAreRejected() {
    SmsSendAttempt attempt = new SmsSendAttempt(2).recordResult(0, SUCCESS);

    assertThat(attempt.recordResult(0, SUCCESS)).isSameAs(attempt);
    assertThatThrownBy(() -> attempt.recordResult(0, TERMINAL))
        .isInstanceOf(IllegalStateException.class);
    assertThat(attempt.decision()).isEqualTo(PENDING);
  }

  @Test
  public void timeoutCannotAuthorizeRetryOrLateSuccess() {
    SmsSendAttempt attempt = new SmsSendAttempt(2).recordResult(0, SUCCESS).timeOut();

    assertThat(attempt.decision()).isEqualTo(UNKNOWN);
    assertThat(attempt.recordResult(1, SUCCESS)).isSameAs(attempt);
    assertThat(attempt.timeOut()).isSameAs(attempt);
  }

  @Test
  public void callbackOrderDoesNotChangeTheAggregateDecision() {
    SmsSendAttempt.Result[] results = {SUCCESS, RETRYABLE_NOT_SUBMITTED, TERMINAL, AMBIGUOUS};

    for (SmsSendAttempt.Result first : results) {
      for (SmsSendAttempt.Result second : results) {
        SmsSendAttempt forward = new SmsSendAttempt(2).recordResult(0, first).recordResult(1, second);
        SmsSendAttempt reverse = new SmsSendAttempt(2).recordResult(1, second).recordResult(0, first);
        assertThat(reverse.decision()).isEqualTo(forward.decision());
        assertThat(forward.recordResult(1, second).decision()).isEqualTo(forward.decision());
      }
    }
  }

  @Test
  public void timeoutDoesNotUndoACompletedAttempt() {
    SmsSendAttempt completed = new SmsSendAttempt(1).recordResult(0, SUCCESS);

    assertThat(completed.timeOut()).isSameAs(completed);
    assertThat(completed.decision()).isEqualTo(SUCCEEDED);
  }

  @Test
  public void rejectsInvalidPartMetadata() {
    assertThatThrownBy(() -> new SmsSendAttempt(0)).isInstanceOf(IllegalArgumentException.class);
    SmsSendAttempt attempt = new SmsSendAttempt(2);
    assertThatThrownBy(() -> attempt.recordResult(-1, SUCCESS)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> attempt.recordResult(2, SUCCESS)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> attempt.recordResult(0, null)).isInstanceOf(NullPointerException.class);
  }
}