package org.smssecure.smssecure.ui.notificationpreferences;

import androidx.lifecycle.ViewModel;

import org.smssecure.smssecure.data.settings.NotificationRefreshRepository;
import org.smssecure.smssecure.domain.conversation.ConversationUnlockCapability;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor.TaskHandle;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import kotlinx.coroutines.flow.MutableStateFlow;
import kotlinx.coroutines.flow.StateFlow;
import kotlinx.coroutines.flow.StateFlowKt;

@HiltViewModel
public final class NotificationPreferencesViewModel extends ViewModel {
  private final NotificationRefreshRepository repository;
  private final MutableStateFlow<NotificationPreferencesUiState> state =
      StateFlowKt.MutableStateFlow(new NotificationPreferencesUiState(false, false));
  private TaskHandle refreshTask;
  private int generation;

  @Inject
  NotificationPreferencesViewModel(NotificationRefreshRepository repository) {
    this.repository = repository;
  }

  public StateFlow<NotificationPreferencesUiState> getState() { return state; }

  public void refresh(ConversationUnlockCapability unlockCapability) {
    if (refreshTask != null) refreshTask.cancel();
    int requestGeneration = ++generation;
    state.setValue(new NotificationPreferencesUiState(true, false));
    refreshTask = repository.refresh(unlockCapability, new NotificationRefreshRepository.Callback() {
      @Override public void onComplete() { complete(requestGeneration, false); }
      @Override public void onFailure(Exception exception) { complete(requestGeneration, true); }
    });
  }

  public void acknowledgeFailure() {
    state.setValue(new NotificationPreferencesUiState(false, false));
  }

  private void complete(int requestGeneration, boolean failed) {
    if (requestGeneration != generation) return;
    refreshTask = null;
    state.setValue(new NotificationPreferencesUiState(false, failed));
  }

  @Override protected void onCleared() {
    generation++;
    if (refreshTask != null) refreshTask.cancel();
    refreshTask = null;
  }
}