package org.smssecure.smssecure.ui.newconversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.mockito.ArgumentCaptor;

import org.smssecure.smssecure.data.conversationscreen.ConversationScreenRepository;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor.TaskHandle;

public class NewConversationViewModelTest {
  @Test
  public void publishesStableRecipientIdsAndExistingThread() {
    ConversationScreenRepository repository = mock(ConversationScreenRepository.class);
    when(repository.findExistingThread(any(long[].class), any())).thenReturn(new TaskHandle());
    NewConversationViewModel viewModel = new NewConversationViewModel(repository);

    viewModel.selectRecipient(new long[] {3L, 8L});

    @SuppressWarnings("unchecked")
    ArgumentCaptor<ConversationScreenRepository.Callback<Long>> callback =
        ArgumentCaptor.forClass(ConversationScreenRepository.Callback.class);
    verify(repository).findExistingThread(any(long[].class), callback.capture());
    callback.getValue().onSuccess(21L);

    NewConversationUiState state = viewModel.getState().getValue();
    assertThat(state.getRecipientIds()).containsExactly(3L, 8L);
    assertThat(state.getThreadId()).isEqualTo(21L);
  }

  @Test
  public void newerSelectionSuppressesLateResultAndCancelsPriorLookup() {
    ConversationScreenRepository repository = mock(ConversationScreenRepository.class);
    TaskHandle firstHandle = mock(TaskHandle.class);
    TaskHandle secondHandle = mock(TaskHandle.class);
    when(repository.findExistingThread(any(long[].class), any()))
        .thenReturn(firstHandle, secondHandle);
    NewConversationViewModel viewModel = new NewConversationViewModel(repository);

    viewModel.selectRecipient(new long[] {3L});
    viewModel.selectRecipient(new long[] {8L});

    @SuppressWarnings("unchecked")
    ArgumentCaptor<ConversationScreenRepository.Callback<Long>> callback =
        ArgumentCaptor.forClass(ConversationScreenRepository.Callback.class);
    verify(repository, org.mockito.Mockito.times(2))
        .findExistingThread(any(long[].class), callback.capture());
    callback.getAllValues().get(0).onSuccess(20L);
    assertThat(viewModel.getState().getValue().hasResult()).isFalse();
    callback.getAllValues().get(1).onSuccess(30L);

    assertThat(viewModel.getState().getValue().getRecipientIds()).containsExactly(8L);
    assertThat(viewModel.getState().getValue().getThreadId()).isEqualTo(30L);
    verify(firstHandle).cancel();
  }

  @Test
  public void relockClearsResultAndSuppressesLateLookup() {
    ConversationScreenRepository repository = mock(ConversationScreenRepository.class);
    TaskHandle handle = mock(TaskHandle.class);
    when(repository.findExistingThread(any(long[].class), any())).thenReturn(handle);
    NewConversationViewModel viewModel = new NewConversationViewModel(repository);
    viewModel.selectRecipient(new long[] {3L});

    @SuppressWarnings("unchecked")
    ArgumentCaptor<ConversationScreenRepository.Callback<Long>> callback =
        ArgumentCaptor.forClass(ConversationScreenRepository.Callback.class);
    verify(repository).findExistingThread(any(long[].class), callback.capture());
    viewModel.clearSensitiveState();
    callback.getValue().onSuccess(20L);

    assertThat(viewModel.getState().getValue().hasResult()).isFalse();
    verify(handle).cancel();
  }
}