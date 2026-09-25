package org.smssecure.smssecure.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.smssecure.smssecure.ApplicationContext;
import org.smssecure.smssecure.jobs.SmsAttemptSendJob;
import org.smssecure.smssecure.jobs.requirements.MasterSecretRequirement;
import org.smssecure.smssecure.jobs.requirements.ServiceRequirement;
import org.smssecure.smssecure.recipients.Recipient;
import org.smssecure.smssecure.recipients.Recipients;
import org.whispersystems.jobqueue.Job;
import org.whispersystems.jobqueue.JobManager;

import java.lang.reflect.Method;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class MessageSenderAttemptTest {
  @Test
  public void newSmsEnqueuesAttemptJobWithoutServiceReadiness() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    ApplicationContext application = mock(ApplicationContext.class);
    JobManager jobs = mock(JobManager.class);
    Recipients recipients = mock(Recipients.class);
    Recipient recipient = mock(Recipient.class);
    when(application.getJobManager()).thenReturn(jobs);
    when(recipients.getPrimaryRecipient()).thenReturn(recipient);
    when(recipient.getNumber()).thenReturn("+15551234567");

    Method enqueue = MessageSender.class.getDeclaredMethod("sendTextMessage", Context.class,
        Recipients.class, long.class);
    enqueue.setAccessible(true);
    try (MockedStatic<ApplicationContext> app = mockStatic(ApplicationContext.class)) {
      app.when(() -> ApplicationContext.getInstance(context)).thenReturn(application);
      enqueue.invoke(null, context, recipients, 42L);
    }

    ArgumentCaptor<Job> captured = ArgumentCaptor.forClass(Job.class);
    verify(jobs).add(captured.capture());
    Job send = captured.getValue();
    assertThat(send).isInstanceOf(SmsAttemptSendJob.class);
    assertThat(send.getGroupId()).isEqualTo("+15551234567");
    assertThat(send.getRequirements()).anyMatch(MasterSecretRequirement.class::isInstance);
    assertThat(send.getRequirements()).noneMatch(ServiceRequirement.class::isInstance);
  }
}