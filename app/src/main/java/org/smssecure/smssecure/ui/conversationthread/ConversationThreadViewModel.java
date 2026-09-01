package org.smssecure.smssecure.ui.conversationthread;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import org.smssecure.smssecure.data.conversationthread.ConversationMessageRow;
import org.smssecure.smssecure.data.conversationthread.ConversationThreadQuery;
import org.smssecure.smssecure.data.conversationthread.ConversationThreadRepository;
import org.smssecure.smssecure.data.conversationthread.ConversationThreadSnapshot;
import org.smssecure.smssecure.domain.conversation.ConversationUnlockCapability;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor.TaskHandle;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class ConversationThreadViewModel extends ViewModel {
  public static final long PARTIAL_CONVERSATION_LIMIT = 500L;

  private final ConversationThreadRepository repository;
  private final ConversationThreadStateStore stateStore;
  private final MutableLiveData<ConversationThreadUiState> state = new MutableLiveData<>();
  private final long threadId;
  private final LinkedHashSet<String> selectedMessageIds = new LinkedHashSet<>();

  private ConversationThreadRepository.Subscription subscription;
  private List<ConversationMessageRow> messages = List.of();
  private long lastSeen;
  private boolean fullHistory;
  private boolean limited;
  private boolean loading = true;
  private boolean threadDeleted;
  private ConversationThreadUiState.Mutation mutation = ConversationThreadUiState.Mutation.NONE;
  private ConversationThreadUiState.Error error = ConversationThreadUiState.Error.NONE;
  private TaskHandle activeTask;

  ConversationThreadViewModel(ConversationThreadRepository repository,
                              ConversationThreadStateStore stateStore) {
    this.repository = Objects.requireNonNull(repository);
    this.stateStore = Objects.requireNonNull(stateStore);
    this.threadId = stateStore.getThreadId();
    this.lastSeen = stateStore.getLastSeen();
    this.fullHistory = stateStore.isFullHistory();
    this.selectedMessageIds.addAll(stateStore.getSelectedMessageIds());
    publish();
    observe();
  }

  public LiveData<ConversationThreadUiState> getState() { return state; }

  public void loadMore() {
    if (fullHistory) return;
    fullHistory = true;
    loading = true;
    if (subscription != null) subscription.close();
    publish();
    observe();
  }

  public void refresh() {
    if (subscription != null) subscription.refresh();
  }

  public void toggleSelection(String stableId) {
    if (messages.stream().noneMatch(message -> message.getStableId().equals(stableId))) return;
    if (!selectedMessageIds.remove(stableId)) selectedMessageIds.add(stableId);
    publish();
  }

  public void clearSelection() {
    if (selectedMessageIds.isEmpty()) return;
    selectedMessageIds.clear();
    publish();
  }

  public void deleteSelected(ConversationUnlockCapability unlockCapability) {
    Objects.requireNonNull(unlockCapability);
    if (selectedMessageIds.isEmpty() || mutation != ConversationThreadUiState.Mutation.NONE) return;
    LinkedHashSet<ConversationThreadRepository.MessageReference> references = new LinkedHashSet<>();
    for (ConversationMessageRow message : messages) {
      if (selectedMessageIds.contains(message.getStableId())) {
        references.add(new ConversationThreadRepository.MessageReference(
            message.getMessageId(), message.isMms()));
      }
    }
    if (references.isEmpty()) return;
    mutation = ConversationThreadUiState.Mutation.DELETE;
    error = ConversationThreadUiState.Error.NONE;
    publish();
    activeTask = repository.delete(references, unlockCapability,
        new ConversationThreadRepository.MutationCallback() {
          @Override
          public void onSuccess(boolean deleted) {
            activeTask = null;
            mutation = ConversationThreadUiState.Mutation.NONE;
            selectedMessageIds.clear();
            threadDeleted = deleted;
            publish();
          }

          @Override
          public void onFailure(Exception exception) {
            activeTask = null;
            mutation = ConversationThreadUiState.Mutation.NONE;
            error = exception instanceof ConversationUnlockCapability.LockedException
              ? ConversationThreadUiState.Error.LOCKED
                                                        : ConversationThreadUiState.Error.DELETE_FAILED;
            publish();
          }
        });
  }

  public void acknowledgeError() {
    if (error == ConversationThreadUiState.Error.NONE) return;
    error = ConversationThreadUiState.Error.NONE;
    publish();
  }

  public void acknowledgeThreadDeleted() {
    if (!threadDeleted) return;
    threadDeleted = false;
    publish();
  }

  private void observe() {
    subscription = repository.observe(
        new ConversationThreadQuery(threadId, fullHistory ? 0 : PARTIAL_CONVERSATION_LIMIT, lastSeen),
        new ConversationThreadRepository.Observer() {
          @Override
          public void onSnapshot(ConversationThreadSnapshot snapshot) {
            messages = snapshot.getMessages();
            lastSeen = snapshot.getLastSeen();
            limited = snapshot.isLimited();
            loading = false;
            error = ConversationThreadUiState.Error.NONE;
            selectedMessageIds.removeIf(selected ->
                messages.stream().noneMatch(message -> message.getStableId().equals(selected)));
            publish();
          }

          @Override
          public void onError(Exception exception) {
            loading = false;
            error = ConversationThreadUiState.Error.LOAD_FAILED;
            publish();
          }
        });
  }

  private void publish() {
    stateStore.save(lastSeen, fullHistory, selectedMessageIds);
    state.setValue(new ConversationThreadUiState(threadId, messages, selectedMessageIds, lastSeen,
        loading, limited, mutation, error, threadDeleted));
  }

  @Override
  protected void onCleared() {
    if (subscription != null) subscription.close();
    if (activeTask != null) activeTask.cancel();
  }
}
