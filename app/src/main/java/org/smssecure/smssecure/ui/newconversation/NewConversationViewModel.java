package org.smssecure.smssecure.ui.newconversation;

import androidx.lifecycle.ViewModel;

import dagger.hilt.android.lifecycle.HiltViewModel;

import kotlinx.coroutines.flow.MutableStateFlow;
import kotlinx.coroutines.flow.StateFlow;
import kotlinx.coroutines.flow.StateFlowKt;

import org.smssecure.smssecure.data.conversationscreen.ConversationScreenRepository;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor.TaskHandle;

import java.util.Arrays;

import javax.inject.Inject;

@HiltViewModel
public final class NewConversationViewModel extends ViewModel {
  private final ConversationScreenRepository repository;
  private final MutableStateFlow<NewConversationUiState> state =
      StateFlowKt.MutableStateFlow(new NewConversationUiState(false, -1, new long[0]));

  private TaskHandle lookupTask;
  private long generation;

  @Inject
  NewConversationViewModel(ConversationScreenRepository repository) {
    this.repository = repository;
  }

  public StateFlow<NewConversationUiState> getState() { return state; }

  public void selectRecipient(long[] recipientIds) {
    if (recipientIds == null || recipientIds.length == 0) return;
    if (lookupTask != null) lookupTask.cancel();
    long[] stableIds = Arrays.copyOf(recipientIds, recipientIds.length);
    long requestGeneration = ++generation;
    state.setValue(new NewConversationUiState(true, -1, new long[0]));
    lookupTask = repository.findExistingThread(stableIds,
        new ConversationScreenRepository.Callback<Long>() {
          @Override public void onSuccess(Long threadId) {
            complete(requestGeneration, stableIds, threadId == null ? -1 : threadId);
          }

          @Override public void onFailure(Exception exception) {
            complete(requestGeneration, stableIds, -1);
          }
        });
  }

  public void acknowledgeResult() {
    state.setValue(new NewConversationUiState(false, -1, new long[0]));
  }

  public void clearSensitiveState() {
    generation++;
    if (lookupTask != null) lookupTask.cancel();
    lookupTask = null;
    acknowledgeResult();
  }

  private void complete(long requestGeneration, long[] recipientIds, long threadId) {
    if (requestGeneration != generation) return;
    lookupTask = null;
    state.setValue(new NewConversationUiState(false, threadId, recipientIds));
  }

  @Override
  protected void onCleared() {
    clearSensitiveState();
  }
}