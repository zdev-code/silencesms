package org.smssecure.smssecure.ui.share;

import androidx.lifecycle.ViewModel;

import org.smssecure.smssecure.data.conversation.ConversationListEntry;
import org.smssecure.smssecure.data.conversation.ConversationListQuery;
import org.smssecure.smssecure.data.conversation.ConversationListSnapshot;
import org.smssecure.smssecure.data.conversation.ConversationRepository;

import java.util.List;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import kotlinx.coroutines.flow.MutableStateFlow;
import kotlinx.coroutines.flow.StateFlow;
import kotlinx.coroutines.flow.StateFlowKt;

@HiltViewModel
public final class ShareTargetsViewModel extends ViewModel {
  private final MutableStateFlow<List<ShareTarget>> state = StateFlowKt.MutableStateFlow(List.of());
  private final ConversationRepository.Subscription subscription;

  @Inject
  ShareTargetsViewModel(ConversationRepository repository) {
    subscription = repository.observe(new ConversationListQuery(false, ""),
        new ConversationRepository.Observer() {
          @Override public void onSnapshot(ConversationListSnapshot snapshot) {
            state.setValue(snapshot.getEntries().stream().map(ShareTargetsViewModel::target).toList());
          }
          @Override public void onError(Exception exception) { state.setValue(List.of()); }
        });
  }

  public StateFlow<List<ShareTarget>> getState() { return state; }

  private static ShareTarget target(ConversationListEntry entry) {
    return new ShareTarget(entry.getThreadId(), entry.getRecipientIds(), entry.getDistributionType());
  }

  @Override protected void onCleared() { subscription.close(); }
}