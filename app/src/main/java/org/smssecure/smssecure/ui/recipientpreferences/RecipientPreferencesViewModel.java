package org.smssecure.smssecure.ui.recipientpreferences;

import android.net.Uri;

import androidx.lifecycle.ViewModel;

import org.smssecure.smssecure.color.MaterialColor;
import org.smssecure.smssecure.data.recipient.RecipientPreferencesRepository;
import org.smssecure.smssecure.database.RecipientPreferenceDatabase.VibrateState;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor.TaskHandle;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import kotlinx.coroutines.flow.MutableStateFlow;
import kotlinx.coroutines.flow.StateFlow;
import kotlinx.coroutines.flow.StateFlowKt;

@HiltViewModel
public final class RecipientPreferencesViewModel extends ViewModel {
  private interface Mutation {
    TaskHandle start(RecipientPreferencesRepository.Callback callback);
  }

  private final RecipientPreferencesRepository repository;
  private final MutableStateFlow<RecipientPreferencesUiState> state =
      StateFlowKt.MutableStateFlow(new RecipientPreferencesUiState(0, false));
  private final List<TaskHandle> tasks = new ArrayList<>();
  private int pendingMutations;

  @Inject
  RecipientPreferencesViewModel(RecipientPreferencesRepository repository) {
    this.repository = repository;
  }

  public StateFlow<RecipientPreferencesUiState> getState() { return state; }

  public void setRingtone(long[] recipientIds, Uri ringtone) {
    mutate(callback -> repository.setRingtone(recipientIds, ringtone, callback));
  }

  public void setVibrate(long[] recipientIds, VibrateState vibrate) {
    mutate(callback -> repository.setVibrate(recipientIds, vibrate, callback));
  }

  public void setMuted(long[] recipientIds, long until) {
    mutate(callback -> repository.setMuted(recipientIds, until, callback));
  }

  public void setBlocked(long[] recipientIds, boolean blocked) {
    mutate(callback -> repository.setBlocked(recipientIds, blocked, callback));
  }

  public void setColor(long[] recipientIds, MaterialColor color) {
    mutate(callback -> repository.setColor(recipientIds, color, callback));
  }

  public void acknowledgeFailure() {
    state.setValue(new RecipientPreferencesUiState(pendingMutations, false));
  }

  public void clearSensitiveState() {
    tasks.forEach(TaskHandle::cancel);
    tasks.clear();
    pendingMutations = 0;
    state.setValue(new RecipientPreferencesUiState(0, false));
  }

  private void mutate(Mutation mutation) {
    tasks.removeIf(TaskHandle::isDone);
    pendingMutations++;
    state.setValue(new RecipientPreferencesUiState(pendingMutations, false));
    tasks.add(mutation.start(new RecipientPreferencesRepository.Callback() {
      @Override public void onComplete() { complete(false); }
      @Override public void onFailure(Exception exception) { complete(true); }
    }));
  }

  private void complete(boolean failed) {
    pendingMutations = Math.max(0, pendingMutations - 1);
    state.setValue(new RecipientPreferencesUiState(pendingMutations, failed));
  }

  @Override protected void onCleared() {
    clearSensitiveState();
  }
}