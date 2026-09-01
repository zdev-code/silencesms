package org.smssecure.smssecure.ui.conversationscreen;

import static org.assertj.core.api.Assertions.assertThat;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.SavedStateHandle;

import org.junit.Rule;
import org.junit.Test;

public class ConversationScreenViewModelTest {
  @Rule public final InstantTaskExecutorRule executorRule = new InstantTaskExecutorRule();

  @Test
  public void conversationIdentityRestoresWithoutTransientSensitiveState() {
    SavedStateHandle handle = new SavedStateHandle();
    ConversationScreenViewModel original = new ConversationScreenViewModel(
        new ConversationScreenStateStore(handle));
    original.setConversation(new long[]{4L, 8L}, 12L, 2, true);
    original.setSecurity(true, true);
    original.setBlocked(true);
    original.setComposeStatus(true, false);

    ConversationScreenViewModel restored = new ConversationScreenViewModel(
        new ConversationScreenStateStore(handle));
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
        new ConversationScreenStateStore(new SavedStateHandle()));
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
}
