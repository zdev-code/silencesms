package org.smssecure.smssecure.ui.groupcreate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.mockito.ArgumentCaptor;

import org.smssecure.smssecure.data.conversationscreen.ConversationScreenRepository;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor.TaskHandle;

import java.util.List;

public class GroupCreateViewModelTest {
  @Test
  public void deduplicatesMembersAndPublishesDurableCompletion() {
    ConversationScreenRepository repository = mock(ConversationScreenRepository.class);
    TaskHandle handle = new TaskHandle();
    when(repository.getOrCreateThread(any(long[].class), anyInt(), any())).thenReturn(handle);
    GroupCreateViewModel viewModel = new GroupCreateViewModel(repository);

    viewModel.addMembers(List.of(member(4, "First"), member(9, "Second"), member(4, "Updated")));
    viewModel.createGroup();

    assertThat(viewModel.getState().getValue().getMembers())
        .extracting(GroupMember::getName).containsExactly("Updated", "Second");
    assertThat(viewModel.getState().getValue().isCreating()).isTrue();

    ArgumentCaptor<long[]> ids = ArgumentCaptor.forClass(long[].class);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<ConversationScreenRepository.Callback<Long>> callback =
        ArgumentCaptor.forClass(ConversationScreenRepository.Callback.class);
    verify(repository).getOrCreateThread(ids.capture(), anyInt(), callback.capture());
    assertThat(ids.getValue()).containsExactly(4L, 9L);

    callback.getValue().onSuccess(27L);

    GroupCreateUiState completed = viewModel.getState().getValue();
    assertThat(completed.isCreating()).isFalse();
    assertThat(completed.getCreatedThreadId()).isEqualTo(27L);
    assertThat(completed.getCreatedRecipientIds()).containsExactly(4L, 9L);
  }

  @Test
  public void rejectsEmptyGroupWithoutCallingRepository() {
    ConversationScreenRepository repository = mock(ConversationScreenRepository.class);
    GroupCreateViewModel viewModel = new GroupCreateViewModel(repository);

    viewModel.createGroup();

    assertThat(viewModel.getState().getValue().getError())
        .isEqualTo(GroupCreateUiState.Error.NO_MEMBERS);
  }

  @Test
  public void cancelsInFlightCreationWhenCleared() {
    ConversationScreenRepository repository = mock(ConversationScreenRepository.class);
    TaskHandle handle = mock(TaskHandle.class);
    when(repository.getOrCreateThread(any(long[].class), anyInt(), any())).thenReturn(handle);
    GroupCreateViewModel viewModel = new GroupCreateViewModel(repository);
    viewModel.addMembers(List.of(member(4, "First")));
    viewModel.createGroup();

    viewModel.onCleared();

    verify(handle).cancel();
  }

  @Test
  public void relockClearsMembersAndInFlightCreation() {
    ConversationScreenRepository repository = mock(ConversationScreenRepository.class);
    TaskHandle handle = mock(TaskHandle.class);
    when(repository.getOrCreateThread(any(long[].class), anyInt(), any())).thenReturn(handle);
    GroupCreateViewModel viewModel = new GroupCreateViewModel(repository);
    viewModel.addMembers(List.of(member(4, "First")));
    viewModel.createGroup();

    viewModel.clearSensitiveState();

    @SuppressWarnings("unchecked")
    ArgumentCaptor<ConversationScreenRepository.Callback<Long>> callback =
      ArgumentCaptor.forClass(ConversationScreenRepository.Callback.class);
    verify(repository).getOrCreateThread(any(long[].class), anyInt(), callback.capture());
    callback.getValue().onSuccess(27L);

    assertThat(viewModel.getState().getValue().getMembers()).isEmpty();
    assertThat(viewModel.getState().getValue().isCreating()).isFalse();
    assertThat(viewModel.getState().getValue().getCreatedThreadId()).isEqualTo(-1L);
    verify(handle).cancel();
  }

  private static GroupMember member(long id, String name) {
    return new GroupMember(id, name, "+1555000" + id);
  }
}