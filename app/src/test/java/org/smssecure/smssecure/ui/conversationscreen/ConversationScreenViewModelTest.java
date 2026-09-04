package org.smssecure.smssecure.ui.conversationscreen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.SavedStateHandle;

import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.data.conversationscreen.ConversationScreenRepository;
import org.smssecure.smssecure.data.conversationscreen.ConversationScreenRepository.TextSendRequest;
import org.smssecure.smssecure.domain.conversation.ConversationUnlockCapability;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor.TaskHandle;

import javax.crypto.spec.SecretKeySpec;

public class ConversationScreenViewModelTest {
  @Rule public final InstantTaskExecutorRule executorRule = new InstantTaskExecutorRule();

  @Test
  public void conversationIdentityRestoresWithoutTransientSensitiveState() {
    SavedStateHandle handle = new SavedStateHandle();
    ConversationScreenViewModel original = new ConversationScreenViewModel(
      new ConversationScreenStateStore(handle), mock(ConversationScreenRepository.class));
    original.setConversation(new long[]{4L, 8L}, 12L, 2, true);
    original.setSecurity(true, true);
    original.setBlocked(true);
    original.setComposeStatus(true, false);

    ConversationScreenViewModel restored = new ConversationScreenViewModel(
      new ConversationScreenStateStore(handle), mock(ConversationScreenRepository.class));
    ConversationScreenUiState state = restored.getState().getValue();

    assertThat(state.getRecipientIds()).containsExactly(4L, 8L);
    assertThat(state.getThreadId()).isEqualTo(12L);
    assertThat(state.getDistributionType()).isEqualTo(2);
    assertThat(state.isArchived()).isTrue();
    assertThat(state.isSecureDestination()).isFalse();
    assertThat(state.isDraftPresent()).isFalse();
  }

  @Test
  public void newConversationReplacesIdentityAndResetsTransientState() {
    ConversationScreenViewModel viewModel = new ConversationScreenViewModel(
      new ConversationScreenStateStore(new SavedStateHandle()), mock(ConversationScreenRepository.class));
    viewModel.setConversation(new long[]{4L}, 12L, 2, true);
    viewModel.setSecurity(true, true);
    viewModel.setComposeStatus(true, true);

    viewModel.setConversation(new long[]{9L}, -1L, 1, false);

    ConversationScreenUiState state = viewModel.getState().getValue();
    assertThat(state.getRecipientIds()).containsExactly(9L);
    assertThat(state.getThreadId()).isEqualTo(-1L);
    assertThat(state.isArchived()).isFalse();
    assertThat(state.isEncryptedConversation()).isFalse();
    assertThat(state.isSendReady()).isFalse();
  }

  @Test
  public void sendPublishesResultAndClearingSuppressesLateCallback() {
    ConversationScreenRepository repository = mock(ConversationScreenRepository.class);
    TaskHandle task = mock(TaskHandle.class);
    when(repository.sendText(any(), any(), any())).thenReturn(task);
    ConversationScreenViewModel viewModel = new ConversationScreenViewModel(
        new ConversationScreenStateStore(new SavedStateHandle()), repository);

    viewModel.sendText(new TextSendRequest(new long[]{4L}, "body", true, 2, -1L), unlockCapability());
    assertThat(viewModel.getState().getValue().isSending()).isTrue();

    ArgumentCaptor<ConversationScreenRepository.Callback<Long>> callback =
        ArgumentCaptor.forClass(ConversationScreenRepository.Callback.class);
    verify(repository).sendText(any(), any(), callback.capture());
    viewModel.onCleared();
    callback.getValue().onSuccess(33L);

    verify(task).cancel();
    assertThat(viewModel.getState().getValue().getSentThreadId()).isEqualTo(-1L);
  }

  private static ConversationUnlockCapability unlockCapability() {
    MasterSecret secret = new MasterSecret(new SecretKeySpec(new byte[16], "AES"),
                                           new SecretKeySpec(new byte[16], "HmacSHA1"));
    return new ConversationUnlockCapability(secret, () -> secret);
  }
}
