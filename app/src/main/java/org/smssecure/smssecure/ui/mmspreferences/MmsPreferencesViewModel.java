package org.smssecure.smssecure.ui.mmspreferences;

import androidx.lifecycle.ViewModel;

import org.smssecure.smssecure.data.settings.ApnDefaultsRepository;
import org.smssecure.smssecure.mms.LegacyMmsConnection;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor.TaskHandle;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import kotlinx.coroutines.flow.MutableStateFlow;
import kotlinx.coroutines.flow.StateFlow;
import kotlinx.coroutines.flow.StateFlowKt;

@HiltViewModel
public final class MmsPreferencesViewModel extends ViewModel {
  private final ApnDefaultsRepository repository;
  private final MutableStateFlow<MmsPreferencesUiState> state =
      StateFlowKt.MutableStateFlow(new MmsPreferencesUiState(false, null, false));
  private TaskHandle loadTask;
  private int generation;

  @Inject
  MmsPreferencesViewModel(ApnDefaultsRepository repository) {
    this.repository = repository;
  }

  public StateFlow<MmsPreferencesUiState> getState() { return state; }

  public void load() {
    if (loadTask != null) loadTask.cancel();
    int requestGeneration = ++generation;
    state.setValue(new MmsPreferencesUiState(true, null, false));
    loadTask = repository.load(new ApnDefaultsRepository.Callback() {
      @Override public void onSuccess(LegacyMmsConnection.Apn apn) {
        if (requestGeneration != generation) return;
        loadTask = null;
        state.setValue(new MmsPreferencesUiState(false, apn, false));
      }

      @Override public void onFailure(Exception exception) {
        if (requestGeneration != generation) return;
        loadTask = null;
        state.setValue(new MmsPreferencesUiState(false, null, true));
      }
    });
  }

  @Override protected void onCleared() {
    generation++;
    if (loadTask != null) loadTask.cancel();
    loadTask = null;
  }
}