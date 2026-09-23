package org.smssecure.smssecure.jobs.sms;

public final class SmsSendRetryPolicy {
  public static final int MAX_ATTEMPTS = 3;

  private SmsSendRetryPolicy() {}

  public static boolean canRetry(SmsSendAttempt.Decision decision, int completedAttempts) {
    return decision == SmsSendAttempt.Decision.RETRYABLE_FAILURE &&
           completedAttempts > 0 && completedAttempts < MAX_ATTEMPTS;
  }

  public static long delayMillis(int completedAttempts, double jitterFraction) {
    if (completedAttempts <= 0 || completedAttempts >= MAX_ATTEMPTS ||
        !Double.isFinite(jitterFraction) || jitterFraction < 0 || jitterFraction > 1) {
      throw new IllegalArgumentException("Invalid retry delay arguments");
    }

    long baseDelay = completedAttempts == 1 ? 30_000L : 120_000L;
    return Math.round(baseDelay * (0.75 + 0.5 * jitterFraction));
  }
}