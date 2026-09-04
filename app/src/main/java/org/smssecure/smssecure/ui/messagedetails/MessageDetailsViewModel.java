package org.smssecure.smssecure.ui.messagedetails;

import androidx.lifecycle.ViewModel;

import org.smssecure.smssecure.data.message.MessageDetailsRepository;
import org.smssecure.smssecure.domain.conversation.ConversationUnlockCapability;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor.TaskHandle;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import kotlinx.coroutines.flow.MutableStateFlow;
import kotlinx.coroutines.flow.StateFlow;
import kotlinx.coroutines.flow.StateFlowKt;

@HiltViewModel
public final class MessageDetailsViewModel extends ViewModel {
  public interface SensitiveResultSink {
    void render(MessageDetailsRepository.Result result);
  }

  private final MessageDetailsRepository repository;
  private final MutableStateFlow<MessageDetailsUiState> state =
      StateFlowKt.MutableStateFlow(new MessageDetailsUiState(false, false));
  private TaskHandle loadTask;
  private int generation;

  @Inject
  MessageDetailsViewModel(MessageDetailsRepository repository) {
    this.repository = repository;
  }

  public StateFlow<MessageDetailsUiState> getState() {
    return state;
  }

  public void load(String transport, long messageId, ConversationUnlockCapability unlockCapability,
                   SensitiveResultSink resultSink) {
    if (loadTask != null) loadTask.cancel();
    int requestGeneration = ++generation;
    state.setValue(new MessageDetailsUiState(true, false));
    loadTask = repository.load(transport, messageId, unlockCapability,
        new MessageDetailsRepository.Callback() {
          @Override public void onSuccess(MessageDetailsRepository.Result result) {
            if (requestGeneration != generation) return;
            loadTask = null;
            state.setValue(new MessageDetailsUiState(false, false));
            resultSink.render(result);
          }

          @Override public void onFailure(Exception exception) {
            if (requestGeneration != generation) return;
            loadTask = null;
            state.setValue(new MessageDetailsUiState(false, true));
          }
        });
  }

  public void clearSensitiveState() {
    generation++;
    if (loadTask != null) loadTask.cancel();
    loadTask = null;
    state.setValue(new MessageDetailsUiState(false, false));
  }

  @Override protected void onCleared() {
    clearSensitiveState();
  }
}