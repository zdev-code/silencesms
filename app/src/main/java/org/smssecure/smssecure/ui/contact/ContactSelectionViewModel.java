package org.smssecure.smssecure.ui.contact;

import androidx.lifecycle.ViewModel;

import org.smssecure.smssecure.data.contact.ContactRepository;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor.TaskHandle;

import java.util.List;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import kotlinx.coroutines.flow.MutableStateFlow;
import kotlinx.coroutines.flow.StateFlow;
import kotlinx.coroutines.flow.StateFlowKt;

@HiltViewModel
public final class ContactSelectionViewModel extends ViewModel {
  private final ContactRepository repository;
  private final MutableStateFlow<ContactSelectionUiState> state =
      StateFlowKt.MutableStateFlow(new ContactSelectionUiState(false, List.of()));
  private String filter = "";
  private TaskHandle loadTask;
  private long generation;

  @Inject ContactSelectionViewModel(ContactRepository repository) {
    this.repository = repository;
  }

  public StateFlow<ContactSelectionUiState> getState() { return state; }

  public void setFilter(String filter) {
    this.filter = filter == null ? "" : filter;
    refresh();
  }

  public void refresh() {
    if (loadTask != null) loadTask.cancel();
    long requestGeneration = ++generation;
    state.setValue(new ContactSelectionUiState(true, state.getValue().getContacts()));
    loadTask = repository.load(filter, new ContactRepository.Callback() {
      @Override public void onSuccess(List<org.smssecure.smssecure.data.contact.ContactEntry> contacts) {
        if (requestGeneration != generation) return;
        loadTask = null;
        state.setValue(new ContactSelectionUiState(false, contacts));
      }
      @Override public void onFailure(Exception exception) {
        if (requestGeneration != generation) return;
        loadTask = null;
        state.setValue(new ContactSelectionUiState(false, List.of()));
      }
    });
  }

  public void clearSensitiveState() {
    generation++;
    if (loadTask != null) loadTask.cancel();
    loadTask = null;
    filter = "";
    state.setValue(new ContactSelectionUiState(false, List.of()));
  }

  @Override protected void onCleared() {
    clearSensitiveState();
  }
}