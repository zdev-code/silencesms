package org.smssecure.smssecure.jobs.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import android.app.Application;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.util.ArrayList;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class SmsAttemptCallbacksTest {
  @Test
  public void everyPartAndActionHasAStableDistinctIdentity() {
    Context context = ApplicationProvider.getApplicationContext();
    ArrayList<PendingIntent> sent = SmsAttemptCallbacks.create(context, "attempt-one", 1, 7, 3, false);
    ArrayList<PendingIntent> delivered = SmsAttemptCallbacks.create(context, "attempt-one", 1, 7, 3, true);

    assertThat(sent).hasSize(3).doesNotHaveDuplicates();
    assertThat(delivered).hasSize(3).doesNotHaveDuplicates();
    assertThat(sent).doesNotContainAnyElementsOf(delivered);
    assertThat(Shadows.shadowOf(sent.get(0)).getFlags() & PendingIntent.FLAG_IMMUTABLE).isNotZero();
    assertThat(Shadows.shadowOf(sent.get(0)).getFlags() & PendingIntent.FLAG_MUTABLE).isZero();
    assertThat(Shadows.shadowOf(delivered.get(0)).getFlags() & PendingIntent.FLAG_MUTABLE).isNotZero();
    assertThat(Shadows.shadowOf(delivered.get(0)).getFlags() & PendingIntent.FLAG_IMMUTABLE).isZero();
    assertThat(SmsAttemptCallbacks.create(context, "attempt-two", 2, 7, 3, false))
        .doesNotContainAnyElementsOf(sent);
  }

  @Test
  public void metadataIsBoundToAUniqueUriNotOnlyExtras() {
    Context context = ApplicationProvider.getApplicationContext();
    Intent first = SmsAttemptCallbacks.intent(context, "attempt-one", 1, 7, 0, 2, false);
    Intent next = SmsAttemptCallbacks.intent(context, "attempt-one", 1, 7, 1, 2, false);

    assertThat(first.filterEquals(next)).isFalse();
    assertThat(first.getStringExtra(SmsAttemptCallbacks.ATTEMPT_ID)).isEqualTo("attempt-one");
    assertThat(first.getIntExtra(SmsAttemptCallbacks.PART_INDEX, -1)).isZero();
    assertThat(first.getIntExtra(SmsAttemptCallbacks.PART_COUNT, -1)).isEqualTo(2);
    assertThat(first.getLongExtra("message_id", -1)).isEqualTo(7);
    assertThat(SmsAttemptCallbacks.isValid(first)).isTrue();
    assertThat(SmsAttemptCallbacks.isValid(SmsAttemptCallbacks.intent(context, "attempt-one", 1, 7, 0, 2, false)
        .putExtra(SmsAttemptCallbacks.PART_INDEX, 1)))
      .isFalse();
    assertThat(SmsAttemptCallbacks.isValid(SmsAttemptCallbacks.intent(context, "attempt-one", 1, 7, 0, 2, false)
        .putExtra("message_id", 8)))
      .isFalse();
    assertThat(SmsAttemptCallbacks.isValid(SmsAttemptCallbacks.intent(context, "attempt-one", 1, 7, 0, 2, false)
        .setData(next.getData())))
      .isFalse();
    assertThatThrownBy(() -> SmsAttemptCallbacks.intent(context, "attempt-one", 1, 7, 2, 2, false))
        .isInstanceOf(IllegalArgumentException.class);
  }
}