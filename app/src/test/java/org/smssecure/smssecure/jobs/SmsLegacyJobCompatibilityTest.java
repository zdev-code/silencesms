package org.smssecure.smssecure.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import android.app.Application;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.smssecure.smssecure.jobs.persistence.EncryptingJobSerializer;
import org.smssecure.smssecure.jobs.requirements.MasterSecretRequirement;
import org.smssecure.smssecure.jobs.requirements.ServiceRequirement;
import org.smssecure.smssecure.service.SmsDeliveryListener;
import org.whispersystems.jobqueue.Job;

import java.lang.reflect.Field;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class SmsLegacyJobCompatibilityTest {
  @Test
  public void messageScopedSendJobRetainsLegacySerializedRequirement() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    EncryptingJobSerializer serializer = new EncryptingJobSerializer();
    Job original = new SmsSendJob(context, 77, "legacy-group");

    Job restored = serializer.deserialize(null, false, serializer.serialize(original));

    assertThat(restored).isInstanceOf(SmsSendJob.class);
    assertThat(restored.getGroupId()).isEqualTo("legacy-group");
    assertThat(restored.isPersistent()).isTrue();
    assertThat(restored.getRequirements()).anyMatch(MasterSecretRequirement.class::isInstance);
    assertThat(restored.getRequirements()).anyMatch(ServiceRequirement.class::isInstance);
    Field messageId = SmsSendJob.class.getDeclaredField("messageId");
    messageId.setAccessible(true);
    assertThat(messageId.getLong(restored)).isEqualTo(77);
  }

  @Test
  public void messageScopedResultJobRetainsActionAndResult() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    EncryptingJobSerializer serializer = new EncryptingJobSerializer();
    Job original = new SmsSentJob(context, 78, SmsDeliveryListener.SENT_SMS_ACTION, -1);

    Job restored = serializer.deserialize(null, false, serializer.serialize(original));

    assertThat(restored).isInstanceOf(SmsSentJob.class);
    assertThat(restored.isPersistent()).isTrue();
    assertThat(restored.getRequirements()).anyMatch(MasterSecretRequirement.class::isInstance);
    Field messageId = SmsSentJob.class.getDeclaredField("messageId");
    Field action = SmsSentJob.class.getDeclaredField("action");
    Field result = SmsSentJob.class.getDeclaredField("result");
    messageId.setAccessible(true);
    action.setAccessible(true);
    result.setAccessible(true);
    assertThat(messageId.getLong(restored)).isEqualTo(78);
    assertThat(action.get(restored)).isEqualTo(SmsDeliveryListener.SENT_SMS_ACTION);
    assertThat(result.getInt(restored)).isEqualTo(-1);
  }

  @Test
  public void attemptSendJobRetainsClaimedRetryIdentity() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    EncryptingJobSerializer serializer = new EncryptingJobSerializer();
    Job restored = serializer.deserialize(null, false,
        serializer.serialize(new SmsAttemptSendJob(context, 79, "recipient", "retry-id")));

    assertThat(restored).isInstanceOf(SmsAttemptSendJob.class);
    assertThat(restored.getGroupId()).isEqualTo("recipient");
    assertThat(restored.isPersistent()).isTrue();
    assertThat(restored.getRequirements()).anyMatch(MasterSecretRequirement.class::isInstance);
    Field claimedId = SmsAttemptSendJob.class.getDeclaredField("claimedAttemptId");
    Field messageId = SmsAttemptSendJob.class.getDeclaredField("messageId");
    claimedId.setAccessible(true);
    messageId.setAccessible(true);
    assertThat(claimedId.get(restored)).isEqualTo("retry-id");
    assertThat(messageId.getLong(restored)).isEqualTo(79);
  }

  @Test
  public void attemptResultJobRetainsPartAndAttemptIdentity() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    EncryptingJobSerializer serializer = new EncryptingJobSerializer();
    Job original = new SmsAttemptResultJob(context, "attempt-id", 2, 80, 1, 3,
      SmsDeliveryListener.SENT_SMS_ACTION, -1, 1234);
    Job restored = serializer.deserialize(null, false, serializer.serialize(original));

    assertThat(restored).isInstanceOf(SmsAttemptResultJob.class);
    assertThat(restored.getGroupId()).isEqualTo("attempt-id");
    assertThat(restored.isPersistent()).isTrue();
    assertThat(restored.getRequirements()).noneMatch(MasterSecretRequirement.class::isInstance);
    for (String fieldName : new String[]{"attemptId", "attemptNumber", "messageId", "partIndex",
        "partCount", "action", "result", "callbackAt"}) {
      Field field = SmsAttemptResultJob.class.getDeclaredField(fieldName);
      field.setAccessible(true);
        assertThat(field.get(restored)).isEqualTo(field.get(original));
    }
  }
}