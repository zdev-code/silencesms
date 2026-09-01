package org.smssecure.smssecure.data.conversationscreen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Before;
import org.junit.Test;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.database.DraftDatabase.Drafts;
import org.smssecure.smssecure.domain.conversation.ConversationUnlockCapability;
import org.smssecure.smssecure.recipients.Recipients;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor;

import java.util.concurrent.Callable;

import javax.crypto.spec.SecretKeySpec;

public class DefaultConversationScreenRepositoryTest {
  private AppTaskExecutor executor;
  private DefaultConversationScreenRepository.DataSource dataSource;
  private DefaultConversationScreenRepository repository;

  @Before
  public void setUp() throws Exception {
    executor = mock(AppTaskExecutor.class);
    dataSource = mock(DefaultConversationScreenRepository.DataSource.class);
    repository = new DefaultConversationScreenRepository(executor, dataSource);
    when(executor.submitSerial(any(), any(), any())).thenAnswer(invocation -> {
      Callable<?> work = invocation.getArgument(0);
      AppTaskExecutor.SuccessCallback<Object> success = invocation.getArgument(1);
      try {
        success.onSuccess(work.call());
      } catch (Exception exception) {
        AppTaskExecutor.FailureCallback failure = invocation.getArgument(2);
        failure.onFailure(exception);
      }
      return mock(AppTaskExecutor.TaskHandle.class);
    });
  }

  @Test
  public void recipientIdsAreCopiedBeforeExecution() {
    long[] ids = {4L};
    Recipients recipients = mock(Recipients.class);
    when(dataSource.recipients(any())).thenReturn(recipients);

    repository.setMuted(ids, 20L, callback());
    ids[0] = 9L;

    org.mockito.ArgumentCaptor<long[]> captured = org.mockito.ArgumentCaptor.forClass(long[].class);
    verify(dataSource).recipients(captured.capture());
    assertThat(captured.getValue()).containsExactly(4L);
    verify(dataSource).setMuted(recipients, 20L);
  }

  @Test
  public void saveDraftsDelegatesAsOneAtomicDataSourceOperation() {
    Recipients recipients = mock(Recipients.class);
    Drafts drafts = new Drafts();
    when(dataSource.recipients(any())).thenReturn(recipients);
    when(dataSource.saveDrafts(any(), org.mockito.ArgumentMatchers.eq(-1L),
        org.mockito.ArgumentMatchers.eq(recipients), org.mockito.ArgumentMatchers.eq(2),
        org.mockito.ArgumentMatchers.same(drafts))).thenReturn(12L);
    ConversationScreenRepository.Callback<Long> callback = mock(ConversationScreenRepository.Callback.class);

    repository.saveDrafts(-1L, new long[]{4L}, 2, drafts, unlockCapability(), callback);

    verify(callback).onSuccess(12L);
  }

  @Test
  public void relockPreventsReadMutationAndNotificationRefresh() {
    MasterSecret original = secret((byte) 1);
    MasterSecret replacement = secret((byte) 2);
    ConversationUnlockCapability capability = new ConversationUnlockCapability(original, () -> replacement);
    ConversationScreenRepository.Callback<Void> callback = callback();

    repository.markRead(7L, capability, callback);

    verify(dataSource, never()).markRead(7L);
    verify(dataSource, never()).updateNotification(any());
    verify(callback).onFailure(any(ConversationUnlockCapability.LockedException.class));
  }

  @SuppressWarnings("unchecked")
  private static <T> ConversationScreenRepository.Callback<T> callback() {
    return mock(ConversationScreenRepository.Callback.class);
  }

  private static ConversationUnlockCapability unlockCapability() {
    MasterSecret secret = secret((byte) 0);
    return new ConversationUnlockCapability(secret, () -> secret);
  }

  private static MasterSecret secret(byte value) {
    byte[] key = new byte[16];
    java.util.Arrays.fill(key, value);
    return new MasterSecret(new SecretKeySpec(key, "AES"), new SecretKeySpec(key, "HmacSHA1"));
  }
}
