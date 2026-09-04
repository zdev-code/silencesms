package org.smssecure.smssecure.ui.conversationthread;

import androidx.lifecycle.ViewModel;

import dagger.hilt.android.lifecycle.HiltViewModel;

import kotlinx.coroutines.flow.MutableStateFlow;
import kotlinx.coroutines.flow.StateFlow;
import kotlinx.coroutines.flow.StateFlowKt;

import org.smssecure.smssecure.data.conversationthread.ConversationMessageRow;
import org.smssecure.smssecure.data.conversationthread.ConversationThreadQuery;
import org.smssecure.smssecure.data.conversationthread.ConversationThreadRepository;
import org.smssecure.smssecure.data.conversationthread.ConversationThreadSnapshot;
import org.smssecure.smssecure.database.model.MessageRecord;
import org.smssecure.smssecure.domain.conversation.ConversationUnlockCapability;
import org.smssecure.smssecure.util.SaveAttachmentTask.Attachment;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor.TaskHandle;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import javax.inject.Inject;

@HiltViewModel
public final class ConversationThreadViewModel extends ViewModel {
  public static final long PARTIAL_CONVERSATION_LIMIT = 500L;

  private final ConversationThreadRepository repository;
  private final ConversationThreadStateStore stateStore;
  private final MutableStateFlow<ConversationThreadUiState> state;
  private final long threadId;
  private final LinkedHashSet<String> selectedMessageIds = new LinkedHashSet<>();

  private ConversationThreadRepository.Subscription subscription;
  private List<ConversationMessageRow> messages = List.of();
  private long lastSeen;
  private boolean fullHistory;
  private boolean limited;
  private boolean loading = true;
  private boolean threadDeleted;
  private int attachmentSaveResult = -1;
  private ConversationThreadUiState.Mutation mutation = ConversationThreadUiState.Mutation.NONE;
  private ConversationThreadUiState.Error error = ConversationThreadUiState.Error.NONE;
  private TaskHandle activeTask;
  private int mutationGeneration;

  @Inject
  ConversationThreadViewModel(ConversationThreadRepository repository,
                              ConversationThreadStateStore stateStore) {
    this.repository = Objects.requireNonNull(repository);
    this.stateStore = Objects.requireNonNull(stateStore);
    this.threadId = stateStore.getThreadId();
    this.lastSeen = stateStore.getLastSeen();
    this.fullHistory = stateStore.isFullHistory();
    this.selectedMessageIds.addAll(stateStore.getSelectedMessageIds());
    stateStore.save(lastSeen, fullHistory, selectedMessageIds);
    state = StateFlowKt.MutableStateFlow(createState());
    observe();
  }

  public StateFlow<ConversationThreadUiState> getState() { return state; }

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
    int requestGeneration = ++mutationGeneration;
    activeTask = repository.delete(references, unlockCapability,
        new ConversationThreadRepository.MutationCallback() {
          @Override
          public void onSuccess(boolean deleted) {
            if (requestGeneration != mutationGeneration) return;
            activeTask = null;
            mutation = ConversationThreadUiState.Mutation.NONE;
            selectedMessageIds.clear();
            threadDeleted = deleted;
            publish();
          }

          @Override
          public void onFailure(Exception exception) {
            if (requestGeneration != mutationGeneration) return;
            activeTask = null;
            mutation = ConversationThreadUiState.Mutation.NONE;
            error = exception instanceof ConversationUnlockCapability.LockedException
              ? ConversationThreadUiState.Error.LOCKED
                                                        : ConversationThreadUiState.Error.DELETE_FAILED;
            publish();
          }
        });
  }

  public void resend(MessageRecord message, ConversationUnlockCapability unlockCapability) {
    Objects.requireNonNull(message);
    Objects.requireNonNull(unlockCapability);
    if (mutation != ConversationThreadUiState.Mutation.NONE) return;
    mutation = ConversationThreadUiState.Mutation.RESEND;
    error = ConversationThreadUiState.Error.NONE;
    publish();
    int requestGeneration = ++mutationGeneration;
    activeTask = repository.resend(message, unlockCapability,
        new ConversationThreadRepository.OperationCallback() {
          @Override public void onSuccess() {
            if (requestGeneration != mutationGeneration) return;
            activeTask = null;
            mutation = ConversationThreadUiState.Mutation.NONE;
            publish();
          }

          @Override public void onFailure(Exception exception) {
            if (requestGeneration != mutationGeneration) return;
            activeTask = null;
            mutation = ConversationThreadUiState.Mutation.NONE;
            error = operationError(exception, ConversationThreadUiState.Error.RESEND_FAILED);
            publish();
          }
        });
  }

  public void saveAttachment(Attachment attachment, ConversationUnlockCapability unlockCapability) {
    Objects.requireNonNull(attachment);
    Objects.requireNonNull(unlockCapability);
    if (mutation != ConversationThreadUiState.Mutation.NONE) return;
    mutation = ConversationThreadUiState.Mutation.SAVE_ATTACHMENT;
    error = ConversationThreadUiState.Error.NONE;
    attachmentSaveResult = -1;
    publish();
    int requestGeneration = ++mutationGeneration;
    activeTask = repository.saveAttachment(attachment, unlockCapability,
        new ConversationThreadRepository.AttachmentCallback() {
          @Override public void onSuccess(int result) {
            if (requestGeneration != mutationGeneration) return;
            activeTask = null;
            mutation = ConversationThreadUiState.Mutation.NONE;
            attachmentSaveResult = result;
            publish();
          }

          @Override public void onFailure(Exception exception) {
            if (requestGeneration != mutationGeneration) return;
            activeTask = null;
            mutation = ConversationThreadUiState.Mutation.NONE;
            error = operationError(exception, ConversationThreadUiState.Error.SAVE_ATTACHMENT_FAILED);
            publish();
          }
        });
  }

  public void acknowledgeAttachmentSaveResult() {
    if (attachmentSaveResult == -1) return;
    attachmentSaveResult = -1;
    publish();
  }

  private ConversationThreadUiState.Error operationError(
      Exception exception, ConversationThreadUiState.Error fallback) {
    return exception instanceof ConversationUnlockCapability.LockedException
        ? ConversationThreadUiState.Error.LOCKED : fallback;
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
    state.setValue(createState());
  }

  private ConversationThreadUiState createState() {
    return new ConversationThreadUiState(threadId, messages, selectedMessageIds, lastSeen, loading,
      limited, mutation, error, threadDeleted, attachmentSaveResult);
  }

  public void clearSensitiveState() {
    mutationGeneration++;
    if (subscription != null) subscription.close();
    subscription = null;
    if (activeTask != null) activeTask.cancel();
    activeTask = null;
    messages = List.of();
    selectedMessageIds.clear();
    mutation = ConversationThreadUiState.Mutation.NONE;
    error = ConversationThreadUiState.Error.NONE;
    threadDeleted = false;
    attachmentSaveResult = -1;
    loading = false;
    publish();
  }

  @Override
  protected void onCleared() {
    clearSensitiveState();
  }
}
