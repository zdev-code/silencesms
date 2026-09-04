package org.smssecure.smssecure.ui.notificationpreferences;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.mockito.ArgumentCaptor;

import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.data.settings.NotificationRefreshRepository;
import org.smssecure.smssecure.domain.conversation.ConversationUnlockCapability;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor.TaskHandle;

public class NotificationPreferencesViewModelTest {
  @Test
  public void refreshPublishesCompletionAndConsumableFailure() {
    NotificationRefreshRepository repository = mock(NotificationRefreshRepository.class);
    when(repository.refresh(any(), any())).thenReturn(new TaskHandle());
    NotificationPreferencesViewModel viewModel = new NotificationPreferencesViewModel(repository);

    viewModel.refresh(mock(ConversationUnlockCapability.class));
    assertThat(viewModel.getState().getValue().isRefreshing()).isTrue();

    ArgumentCaptor<NotificationRefreshRepository.Callback> callback =
        ArgumentCaptor.forClass(NotificationRefreshRepository.Callback.class);
    verify(repository).refresh(any(), callback.capture());
    callback.getValue().onFailure(new IllegalStateException("failure"));

    assertThat(viewModel.getState().getValue().isFailed()).isTrue();
    viewModel.acknowledgeFailure();
    assertThat(viewModel.getState().getValue().isFailed()).isFalse();
  }

  @Test
  public void replacementCancelsPriorRefreshAndSuppressesLateCompletion() {
    NotificationRefreshRepository repository = mock(NotificationRefreshRepository.class);
    TaskHandle firstHandle = mock(TaskHandle.class);
    when(repository.refresh(any(), any())).thenReturn(firstHandle, new TaskHandle());
    NotificationPreferencesViewModel viewModel = new NotificationPreferencesViewModel(repository);

    viewModel.refresh(mock(ConversationUnlockCapability.class));
    viewModel.refresh(mock(ConversationUnlockCapability.class));

    ArgumentCaptor<NotificationRefreshRepository.Callback> callback =
        ArgumentCaptor.forClass(NotificationRefreshRepository.Callback.class);
    verify(repository, org.mockito.Mockito.times(2)).refresh(any(), callback.capture());
    callback.getAllValues().get(0).onComplete();

    verify(firstHandle).cancel();
    assertThat(viewModel.getState().getValue().isRefreshing()).isTrue();
  }
}