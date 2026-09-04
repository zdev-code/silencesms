package org.smssecure.smssecure.ui.messagedetails;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.mockito.ArgumentCaptor;

import org.smssecure.smssecure.data.message.MessageDetailsRepository;
import org.smssecure.smssecure.domain.conversation.ConversationUnlockCapability;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor.TaskHandle;

import java.util.concurrent.atomic.AtomicReference;

public class MessageDetailsViewModelTest {
  @Test
  public void deliversDecryptedResultOnlyToCurrentSink() {
    MessageDetailsRepository repository = mock(MessageDetailsRepository.class);
    when(repository.load(anyString(), anyLong(), any(), any())).thenReturn(new TaskHandle());
    MessageDetailsViewModel viewModel = new MessageDetailsViewModel(repository);
    AtomicReference<MessageDetailsRepository.Result> delivered = new AtomicReference<>();

    viewModel.load("sms", 7L, mock(ConversationUnlockCapability.class), delivered::set);
    assertThat(viewModel.getState().getValue().isLoading()).isTrue();

    ArgumentCaptor<MessageDetailsRepository.Callback> callback =
        ArgumentCaptor.forClass(MessageDetailsRepository.Callback.class);
    verify(repository).load(anyString(), anyLong(), any(), callback.capture());
    MessageDetailsRepository.Result result = mock(MessageDetailsRepository.Result.class);
    callback.getValue().onSuccess(result);

    assertThat(delivered.get()).isSameAs(result);
    assertThat(viewModel.getState().getValue().isLoading()).isFalse();
    assertThat(viewModel.getState().getValue().isFailed()).isFalse();
  }

  @Test
  public void failurePublishesNonSensitiveState() {
    MessageDetailsRepository repository = mock(MessageDetailsRepository.class);
    when(repository.load(anyString(), anyLong(), any(), any())).thenReturn(new TaskHandle());
    MessageDetailsViewModel viewModel = new MessageDetailsViewModel(repository);

    viewModel.load("mms", 8L, mock(ConversationUnlockCapability.class), ignored -> {});

    ArgumentCaptor<MessageDetailsRepository.Callback> callback =
        ArgumentCaptor.forClass(MessageDetailsRepository.Callback.class);
    verify(repository).load(anyString(), anyLong(), any(), callback.capture());
    callback.getValue().onFailure(new IllegalStateException("failure"));

    assertThat(viewModel.getState().getValue().isLoading()).isFalse();
    assertThat(viewModel.getState().getValue().isFailed()).isTrue();
  }

  @Test
  public void replacementCancelsPriorLoadAndSuppressesItsLateResult() {
    MessageDetailsRepository repository = mock(MessageDetailsRepository.class);
    TaskHandle firstHandle = mock(TaskHandle.class);
    when(repository.load(anyString(), anyLong(), any(), any()))
        .thenReturn(firstHandle, new TaskHandle());
    MessageDetailsViewModel viewModel = new MessageDetailsViewModel(repository);
    AtomicReference<MessageDetailsRepository.Result> delivered = new AtomicReference<>();

    viewModel.load("sms", 7L, mock(ConversationUnlockCapability.class), delivered::set);
    viewModel.load("sms", 8L, mock(ConversationUnlockCapability.class), delivered::set);

    ArgumentCaptor<MessageDetailsRepository.Callback> callback =
        ArgumentCaptor.forClass(MessageDetailsRepository.Callback.class);
    verify(repository, org.mockito.Mockito.times(2))
        .load(anyString(), anyLong(), any(), callback.capture());
    callback.getAllValues().get(0).onSuccess(mock(MessageDetailsRepository.Result.class));

    verify(firstHandle).cancel();
    assertThat(delivered.get()).isNull();
    assertThat(viewModel.getState().getValue().isLoading()).isTrue();
  }

  @Test
  public void clearCancelsLoadAndSuppressesDecryptedResult() {
    MessageDetailsRepository repository = mock(MessageDetailsRepository.class);
    TaskHandle handle = mock(TaskHandle.class);
    when(repository.load(anyString(), anyLong(), any(), any())).thenReturn(handle);
    MessageDetailsViewModel viewModel = new MessageDetailsViewModel(repository);
    AtomicReference<MessageDetailsRepository.Result> delivered = new AtomicReference<>();

    viewModel.load("sms", 7L, mock(ConversationUnlockCapability.class), delivered::set);
    ArgumentCaptor<MessageDetailsRepository.Callback> callback =
        ArgumentCaptor.forClass(MessageDetailsRepository.Callback.class);
    verify(repository).load(anyString(), anyLong(), any(), callback.capture());

    viewModel.clearSensitiveState();
    callback.getValue().onSuccess(mock(MessageDetailsRepository.Result.class));

    verify(handle).cancel();
    assertThat(delivered.get()).isNull();
    assertThat(viewModel.getState().getValue().isLoading()).isFalse();
  }
}