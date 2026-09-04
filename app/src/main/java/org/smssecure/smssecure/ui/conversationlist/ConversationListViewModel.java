package org.smssecure.smssecure.ui.conversationlist;

import androidx.lifecycle.ViewModel;

import dagger.hilt.android.lifecycle.HiltViewModel;

import kotlinx.coroutines.flow.MutableStateFlow;
import kotlinx.coroutines.flow.StateFlow;
import kotlinx.coroutines.flow.StateFlowKt;

import org.smssecure.smssecure.data.conversation.ConversationListEntry;
import org.smssecure.smssecure.data.conversation.ConversationListQuery;
import org.smssecure.smssecure.data.conversation.ConversationListSnapshot;
import org.smssecure.smssecure.data.conversation.ConversationRepository;
import org.smssecure.smssecure.domain.conversation.ConversationUnlockCapability;
import org.smssecure.smssecure.domain.conversation.ConversationListReminderPolicy;
import org.smssecure.smssecure.domain.conversation.SendSelectedDrafts;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor.TaskHandle;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import javax.inject.Inject;

@HiltViewModel
public final class ConversationListViewModel extends ViewModel {
  private final ConversationRepository repository;
  private final SendSelectedDrafts      sendSelectedDrafts;
  private final ConversationListStateStore stateStore;
  private final ConversationListReminderPolicy reminderPolicy;
  private final MutableStateFlow<ConversationListUiState> state;
  private final boolean archived;

  private ConversationRepository.Subscription subscription;
  private String filter;
  private List<ConversationListEntry> entries = List.of();
  private int archivedCount;
  private final LinkedHashSet<Long> selectedThreadIds = new LinkedHashSet<>();
  private ConversationListUiState.Mutation activeMutation = ConversationListUiState.Mutation.NONE;
  private ConversationListUiState.Error error = ConversationListUiState.Error.NONE;
  private PendingMutation pendingUndo;
  private TaskHandle activeTask;
  private TaskHandle reminderTask;
  private ConversationListReminderPolicy.Kind reminderKind = ConversationListReminderPolicy.Kind.NONE;
  private long reminderGeneration;

  @Inject
  ConversationListViewModel(ConversationRepository repository, SendSelectedDrafts sendSelectedDrafts,
                            ConversationListReminderPolicy reminderPolicy,
                            ConversationListStateStore stateStore) {
    this.repository = Objects.requireNonNull(repository);
    this.sendSelectedDrafts = Objects.requireNonNull(sendSelectedDrafts);
    this.stateStore = Objects.requireNonNull(stateStore);
    this.reminderPolicy = Objects.requireNonNull(reminderPolicy);
    this.archived   = stateStore.isArchived();
    this.filter     = stateStore.getFilter();
    this.selectedThreadIds.addAll(stateStore.getSelectedThreadIds());
    stateStore.save(filter, selectedThreadIds);
    state = StateFlowKt.MutableStateFlow(createState(true));
    observeCurrentQuery();
    refreshReminder();
  }

  public StateFlow<ConversationListUiState> getState() {
    return state;
  }

  public void setFilter(String filter) {
    String normalized = filter == null ? "" : filter.trim();
    if (this.filter.equals(normalized)) return;
    this.filter = normalized;
    selectedThreadIds.clear();
    error = ConversationListUiState.Error.NONE;
    if (subscription != null) subscription.close();
    publish(true);
    observeCurrentQuery();
  }

  public void toggleSelection(long threadId) {
    if (threadId <= 0 || entries.stream().noneMatch(entry -> entry.getThreadId() == threadId)) return;
    if (!selectedThreadIds.remove(threadId)) selectedThreadIds.add(threadId);
    publish(false);
  }

  public void selectAll() {
    selectedThreadIds.clear();
    for (ConversationListEntry entry : entries) selectedThreadIds.add(entry.getThreadId());
    publish(false);
  }

  public void clearSelection() {
    if (selectedThreadIds.isEmpty()) return;
    selectedThreadIds.clear();
    publish(false);
  }

  public void acknowledgeError() {
    if (error == ConversationListUiState.Error.NONE) return;
    error = ConversationListUiState.Error.NONE;
    publish(false);
  }

  public void markAllRead(ConversationUnlockCapability unlockCapability,
                          ConversationRepository.MutationCallback callback) {
    Objects.requireNonNull(unlockCapability);
    activeTask = repository.markAllRead(unlockCapability, callback);
  }

  public void archiveSelected() {
    if (archived) return;
    mutateSelection(ConversationListUiState.Mutation.ARCHIVE, false);
  }

  public void unarchiveSelected() {
    if (!archived) return;
    mutateSelection(ConversationListUiState.Mutation.UNARCHIVE, true);
  }

  public void undoLastArchiveMutation(ConversationUnlockCapability unlockCapability) {
    if (pendingUndo == null || activeMutation != ConversationListUiState.Mutation.NONE) return;
    PendingMutation undo = pendingUndo;
    if (undo.swipe && unlockCapability == null) return;
    activeMutation = ConversationListUiState.Mutation.UNDO;
    error = ConversationListUiState.Error.NONE;
    publish(false);
    ConversationRepository.MutationCallback callback = mutationCallback(null);
    if (undo.swipe) {
      activeTask = repository.setArchivedFromSwipe(undo.threadIds.iterator().next(), undo.archive,
                                                   undo.updateReadState, unlockCapability, callback);
    } else if (undo.archive) {
      activeTask = repository.archive(undo.threadIds, callback);
    } else {
      activeTask = repository.unarchive(undo.threadIds, callback);
    }
  }

  public void archiveFromSwipe(long threadId, boolean wasRead,
                               ConversationUnlockCapability unlockCapability) {
    Objects.requireNonNull(unlockCapability);
    if (activeMutation != ConversationListUiState.Mutation.NONE ||
        entries.stream().noneMatch(entry -> entry.getThreadId() == threadId)) return;
    boolean targetArchived = !archived;
    boolean updateReadState = !archived && !wasRead;
    activeMutation = targetArchived ? ConversationListUiState.Mutation.ARCHIVE
                                    : ConversationListUiState.Mutation.UNARCHIVE;
    error = ConversationListUiState.Error.NONE;
    publish(false);
    activeTask = repository.setArchivedFromSwipe(threadId, targetArchived, updateReadState,
      unlockCapability,
      mutationCallback(new PendingMutation(Set.of(threadId), archived, true, updateReadState)));
  }

  public void sendSelectedDrafts(ConversationUnlockCapability unlockCapability) {
    Objects.requireNonNull(unlockCapability);
    if (selectedThreadIds.isEmpty() || activeMutation != ConversationListUiState.Mutation.NONE) return;
    List<SendSelectedDrafts.Target> targets = new ArrayList<>();
    for (ConversationListEntry entry : entries) {
      if (selectedThreadIds.contains(entry.getThreadId())) {
        targets.add(new SendSelectedDrafts.Target(entry.getThreadId(), entry.getRecipientIds()));
      }
    }
    if (targets.isEmpty()) return;
    activeMutation = ConversationListUiState.Mutation.SEND_DRAFTS;
    error = ConversationListUiState.Error.NONE;
    publish(false);
    activeTask = sendSelectedDrafts.execute(new SendSelectedDrafts.Input(targets), unlockCapability,
        new SendSelectedDrafts.Callback() {
          @Override
          public void onSuccess(SendSelectedDrafts.Result result) {
            activeTask = null;
            activeMutation = ConversationListUiState.Mutation.NONE;
            selectedThreadIds.clear();
            publish(false);
          }

          @Override
          public void onFailure(Exception exception) {
            activeTask = null;
            activeMutation = ConversationListUiState.Mutation.NONE;
            error = operationError(exception, ConversationListUiState.Error.SEND_DRAFTS_FAILED);
            publish(false);
          }
        });
  }

  public void deleteSelected(ConversationUnlockCapability unlockCapability) {
    Objects.requireNonNull(unlockCapability);
    if (selectedThreadIds.isEmpty() || activeMutation != ConversationListUiState.Mutation.NONE) return;
    Set<Long> ids = Set.copyOf(selectedThreadIds);
    activeMutation = ConversationListUiState.Mutation.DELETE;
    error = ConversationListUiState.Error.NONE;
    publish(false);
    activeTask = repository.delete(ids, unlockCapability, new ConversationRepository.MutationCallback() {
      @Override
      public void onSuccess() {
        activeTask = null;
        activeMutation = ConversationListUiState.Mutation.NONE;
        selectedThreadIds.clear();
        publish(false);
      }

      @Override
      public void onFailure(Exception exception) {
        activeTask = null;
        activeMutation = ConversationListUiState.Mutation.NONE;
        error = operationError(exception, ConversationListUiState.Error.DELETE_FAILED);
        publish(false);
      }
    });
  }

  public void refreshReminder() {
    if (reminderTask != null) reminderTask.cancel();
    long generation = ++reminderGeneration;
    reminderTask = reminderPolicy.refresh(new ConversationListReminderPolicy.Callback() {
      @Override
      public void onResult(ConversationListReminderPolicy.Kind kind) {
        if (generation != reminderGeneration) return;
        reminderTask = null;
        reminderKind = Objects.requireNonNull(kind);
        publish(false);
      }

      @Override
      public void onFailure(Exception exception) {
        if (generation != reminderGeneration) return;
        reminderTask = null;
      }
    });
  }

  @Override
  protected void onCleared() {
    if (subscription != null) subscription.close();
    if (activeTask != null) activeTask.cancel();
    if (reminderTask != null) reminderTask.cancel();
    reminderGeneration++;
  }

  private void observeCurrentQuery() {
    subscription = repository.observe(new ConversationListQuery(archived, filter),
        new ConversationRepository.Observer() {
          @Override
          public void onSnapshot(ConversationListSnapshot snapshot) {
            entries = snapshot.getEntries();
            archivedCount = snapshot.getArchivedCount();
            selectedThreadIds.removeIf(id -> entries.stream().noneMatch(entry -> entry.getThreadId() == id));
            error = ConversationListUiState.Error.NONE;
            publish(false);
          }

          @Override
          public void onError(Exception exception) {
            error = ConversationListUiState.Error.LOAD_FAILED;
            publish(false);
          }
        });
  }

  private void mutateSelection(ConversationListUiState.Mutation mutation, boolean undoArchives) {
    if (selectedThreadIds.isEmpty() || activeMutation != ConversationListUiState.Mutation.NONE) return;
    Set<Long> ids = Set.copyOf(selectedThreadIds);
    activeMutation = mutation;
    error = ConversationListUiState.Error.NONE;
    publish(false);
    ConversationRepository.MutationCallback callback = mutationCallback(
      new PendingMutation(ids, undoArchives, false, false));
    if (mutation == ConversationListUiState.Mutation.ARCHIVE) {
      activeTask = repository.archive(ids, callback);
    } else {
      activeTask = repository.unarchive(ids, callback);
    }
  }

  private ConversationRepository.MutationCallback mutationCallback(PendingMutation undoOnSuccess) {
    return new ConversationRepository.MutationCallback() {
      @Override
      public void onSuccess() {
        activeTask = null;
        activeMutation = ConversationListUiState.Mutation.NONE;
        selectedThreadIds.clear();
        pendingUndo = undoOnSuccess;
        publish(false);
      }

      @Override
      public void onFailure(Exception exception) {
        activeTask = null;
        activeMutation = ConversationListUiState.Mutation.NONE;
        error = operationError(exception, ConversationListUiState.Error.MUTATION_FAILED);
        publish(false);
      }
    };
  }

  private void publish(boolean loading) {
    stateStore.save(filter, selectedThreadIds);
    state.setValue(createState(loading));
  }

  private ConversationListUiState createState(boolean loading) {
    return new ConversationListUiState(archived, filter, entries, archivedCount, selectedThreadIds,
        loading, activeMutation, pendingUndo != null, error, reminderKind);
  }

  private static ConversationListUiState.Error operationError(
      Exception exception, ConversationListUiState.Error fallback) {
    return exception instanceof ConversationUnlockCapability.LockedException
        ? ConversationListUiState.Error.LOCKED : fallback;
  }

  private static final class PendingMutation {
    private final Set<Long> threadIds;
    private final boolean   archive;
    private final boolean   swipe;
    private final boolean   updateReadState;

    private PendingMutation(Set<Long> threadIds, boolean archive, boolean swipe,
                            boolean updateReadState) {
      this.threadIds = Set.copyOf(threadIds);
      this.archive   = archive;
      this.swipe     = swipe;
      this.updateReadState = updateReadState;
    }
  }
}