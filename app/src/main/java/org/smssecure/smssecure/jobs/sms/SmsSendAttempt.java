package org.smssecure.smssecure.jobs.sms;

import java.util.Arrays;
import java.util.Objects;

public final class SmsSendAttempt {
  public enum Result {
    SUCCESS, RETRYABLE_NOT_SUBMITTED, TERMINAL, AMBIGUOUS
  }

  public enum Decision {
    PENDING, SUCCEEDED, RETRYABLE_FAILURE, TERMINAL_FAILURE, PARTIAL_FAILURE, UNKNOWN
  }

  private final Result[] parts;
  private final boolean timedOut;

  public SmsSendAttempt(int partCount) {
    if (partCount <= 0) {
      throw new IllegalArgumentException("partCount must be positive");
    }

    this.parts = new Result[partCount];
    this.timedOut = false;
  }

  private SmsSendAttempt(Result[] parts, boolean timedOut) {
    this.parts = parts;
    this.timedOut = timedOut;
  }

  public SmsSendAttempt recordResult(int partIndex, Result result) {
    if (partIndex < 0 || partIndex >= parts.length) {
      throw new IllegalArgumentException("partIndex out of range");
    }

    Objects.requireNonNull(result, "result");
    if (parts[partIndex] != null) {
      if (parts[partIndex] != result) {
        throw new IllegalStateException("Conflicting result for part " + partIndex);
      }
      return this;
    }

    if (timedOut) {
      return this;
    }

    Result[] updated = Arrays.copyOf(parts, parts.length);
    updated[partIndex] = result;
    return new SmsSendAttempt(updated, false);
  }

  public SmsSendAttempt timeOut() {
    return decision() == Decision.PENDING ? new SmsSendAttempt(parts, true) : this;
  }

  public Decision decision() {
    if (timedOut) {
      return Decision.UNKNOWN;
    }

    int successes = 0;
    int retryable = 0;
    int terminal = 0;
    int ambiguous = 0;

    for (Result part : parts) {
      if (part == null) {
        return Decision.PENDING;
      }

      switch (part) {
        case SUCCESS: successes++; break;
        case RETRYABLE_NOT_SUBMITTED: retryable++; break;
        case TERMINAL: terminal++; break;
        case AMBIGUOUS: ambiguous++; break;
      }
    }

    if (successes == parts.length) return Decision.SUCCEEDED;
    if (ambiguous > 0) return Decision.UNKNOWN;
    if (successes > 0) return Decision.PARTIAL_FAILURE;
    if (terminal > 0) return Decision.TERMINAL_FAILURE;
    if (retryable == parts.length) return Decision.RETRYABLE_FAILURE;
    throw new IllegalStateException("Unclassified result");
  }
}