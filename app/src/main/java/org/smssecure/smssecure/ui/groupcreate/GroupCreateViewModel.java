package org.smssecure.smssecure.ui.groupcreate;

import androidx.lifecycle.ViewModel;

import dagger.hilt.android.lifecycle.HiltViewModel;

import kotlinx.coroutines.flow.MutableStateFlow;
import kotlinx.coroutines.flow.StateFlow;
import kotlinx.coroutines.flow.StateFlowKt;

import org.smssecure.smssecure.data.conversationscreen.ConversationScreenRepository;
import org.smssecure.smssecure.database.ThreadDatabase;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor.TaskHandle;

import java.util.LinkedHashMap;
import java.util.List;

import javax.inject.Inject;

@HiltViewModel
public final class GroupCreateViewModel extends ViewModel {
  private final ConversationScreenRepository repository;
  private final LinkedHashMap<Long, GroupMember> members = new LinkedHashMap<>();
  private final MutableStateFlow<GroupCreateUiState> state;

  private TaskHandle createTask;
  private boolean creating;
  private long createdThreadId = -1;
  private long[] createdRecipientIds = new long[0];
  private GroupCreateUiState.Error error = GroupCreateUiState.Error.NONE;
  private long generation;

  @Inject
  GroupCreateViewModel(ConversationScreenRepository repository) {
    this.repository = repository;
    state = StateFlowKt.MutableStateFlow(createState());
  }

  public StateFlow<GroupCreateUiState> getState() { return state; }

  public void addMembers(List<GroupMember> additions) {
    if (creating) return;
    for (GroupMember member : additions) members.put(member.getRecipientId(), member);
    error = GroupCreateUiState.Error.NONE;
    publish();
  }

  public void removeMember(long recipientId) {
    if (creating || members.remove(recipientId) == null) return;
    publish();
  }

  public void createGroup() {
    if (creating) return;
    if (members.isEmpty()) {
      error = GroupCreateUiState.Error.NO_MEMBERS;
      publish();
      return;
    }

    long[] recipientIds = members.keySet().stream().mapToLong(Long::longValue).toArray();
    long requestGeneration = ++generation;
    creating = true;
    error = GroupCreateUiState.Error.NONE;
    publish();
    createTask = repository.getOrCreateThread(recipientIds,
        ThreadDatabase.DistributionTypes.CONVERSATION,
        new ConversationScreenRepository.Callback<Long>() {
          @Override public void onSuccess(Long threadId) {
            if (requestGeneration != generation) return;
            createTask = null;
            creating = false;
            if (threadId == null || threadId < 0) {
              error = GroupCreateUiState.Error.CREATE_FAILED;
            } else {
              createdThreadId = threadId;
              createdRecipientIds = recipientIds;
            }
            publish();
          }

          @Override public void onFailure(Exception exception) {
            if (requestGeneration != generation) return;
            createTask = null;
            creating = false;
            error = GroupCreateUiState.Error.CREATE_FAILED;
            publish();
          }
        });
  }

  public void acknowledgeResult() {
    if (createdThreadId < 0 && error == GroupCreateUiState.Error.NONE) return;
    createdThreadId = -1;
    createdRecipientIds = new long[0];
    error = GroupCreateUiState.Error.NONE;
    publish();
  }

  public void clearSensitiveState() {
    generation++;
    if (createTask != null) createTask.cancel();
    createTask = null;
    creating = false;
    members.clear();
    createdThreadId = -1;
    createdRecipientIds = new long[0];
    error = GroupCreateUiState.Error.NONE;
    publish();
  }

  private GroupCreateUiState createState() {
    return new GroupCreateUiState(List.copyOf(members.values()), creating, createdThreadId,
                                  createdRecipientIds, error);
  }

  private void publish() { state.setValue(createState()); }

  @Override
  protected void onCleared() {
    clearSensitiveState();
  }
}