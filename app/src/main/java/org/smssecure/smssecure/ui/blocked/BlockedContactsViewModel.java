package org.smssecure.smssecure.ui.blocked;

import androidx.lifecycle.ViewModel;

import org.smssecure.smssecure.data.recipient.RecipientPreferencesRepository;

import java.util.List;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import kotlinx.coroutines.flow.MutableStateFlow;
import kotlinx.coroutines.flow.StateFlow;
import kotlinx.coroutines.flow.StateFlowKt;

@HiltViewModel
public final class BlockedContactsViewModel extends ViewModel {
  private final MutableStateFlow<List<BlockedContactEntry>> state =
      StateFlowKt.MutableStateFlow(List.of());
  private final RecipientPreferencesRepository.Subscription subscription;

  @Inject
  BlockedContactsViewModel(RecipientPreferencesRepository repository) {
    subscription = repository.observeBlocked(new RecipientPreferencesRepository.Listener() {
      @Override public void onChanged(List<long[]> ids) {
        state.setValue(ids.stream().map(BlockedContactEntry::new).toList());
      }
      @Override public void onFailure(Exception exception) {
        state.setValue(List.of());
      }
    });
  }

  public StateFlow<List<BlockedContactEntry>> getState() {
    return state;
  }

  @Override protected void onCleared() {
    subscription.close();
  }
}