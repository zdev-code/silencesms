package org.smssecure.smssecure.ui.conversationlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.SavedStateHandle;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.smssecure.smssecure.data.conversation.ConversationListEntry;
import org.smssecure.smssecure.data.conversation.ConversationListQuery;
import org.smssecure.smssecure.data.conversation.ConversationListSnapshot;
import org.smssecure.smssecure.data.conversation.ConversationRepository;
import org.smssecure.smssecure.domain.conversation.SendSelectedDrafts;
import org.smssecure.smssecure.domain.conversation.ConversationUnlockCapability;
import org.smssecure.smssecure.domain.conversation.ConversationListReminderPolicy;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.spec.SecretKeySpec;

public class ConversationListViewModelTest {
  @Rule public final InstantTaskExecutorRule instantTaskExecutorRule = new InstantTaskExecutorRule();

  private FakeConversationRepository repository;
  private SendSelectedDrafts          sendSelectedDrafts;
  private ConversationListReminderPolicy reminderPolicy;
  private ConversationListViewModel  viewModel;

  @Before
  public void setUp() {
    repository = new FakeConversationRepository();
    sendSelectedDrafts = mock(SendSelectedDrafts.class);
    reminderPolicy = mock(ConversationListReminderPolicy.class);
    viewModel  = new ConversationListViewModel(repository, sendSelectedDrafts, reminderPolicy,
      new ConversationListStateStore(new SavedStateHandle(), false));
  }

  @Test
  public void initialLoadingTransitionsToContent() {
    assertThat(state().isLoading()).isTrue();
    assertThat(repository.queries).containsExactly(new ConversationListQuery(false, ""));

    repository.emit(new ConversationListSnapshot(List.of(entry(11L)), 3));

    assertThat(state().isLoading()).isFalse();
    assertThat(state().getEntries()).extracting(ConversationListEntry::getThreadId).containsExactly(11L);
    assertThat(state().getArchivedCount()).isEqualTo(3);
  }

  @Test
  public void changingFilterClosesOldSubscriptionAndStartsNewQuery() {
    FakeSubscription initial = repository.activeSubscription;

    viewModel.setFilter(" Alice ");

    assertThat(initial.closed).isTrue();
    assertThat(state().getFilter()).isEqualTo("Alice");
    assertThat(state().isLoading()).isTrue();
    assertThat(repository.queries).containsExactly(
        new ConversationListQuery(false, ""), new ConversationListQuery(false, "Alice"));
  }

  @Test
  public void restoresModeAndSelectionButPurgesSensitiveFilter() {
    SavedStateHandle handle = new SavedStateHandle();
    handle.set(ConversationListStateStore.KEY_ARCHIVED, true);
    handle.set(ConversationListStateStore.LEGACY_KEY_FILTER, "+15551234567");
    handle.set(ConversationListStateStore.KEY_SELECTED_THREAD_IDS, new long[] {2L});

    viewModel = new ConversationListViewModel(repository, sendSelectedDrafts, reminderPolicy,
        new ConversationListStateStore(handle, false));

    assertThat(repository.queries).last().isEqualTo(new ConversationListQuery(true, ""));
    assertThat(state().getSelectedThreadIds()).containsExactly(2L);
    assertThat(handle.keys()).doesNotContain(ConversationListStateStore.LEGACY_KEY_FILTER);
  }

  @Test
  public void refreshRemovesSelectionsForMissingThreads() {
    repository.emit(new ConversationListSnapshot(List.of(entry(1L), entry(2L)), 0));
    viewModel.toggleSelection(1L);
    viewModel.toggleSelection(2L);

    repository.emit(new ConversationListSnapshot(List.of(entry(2L)), 0));

    assertThat(state().getSelectedThreadIds()).containsExactly(2L);
  }

  @Test
  public void archiveSuccessOffersUndoAndUndoReversesMutation() {
    repository.emit(new ConversationListSnapshot(List.of(entry(7L)), 0));
    viewModel.toggleSelection(7L);

    viewModel.archiveSelected();
    repository.succeedMutation();

    assertThat(repository.archivedIds).containsExactly(7L);
    assertThat(state().isUndoAvailable()).isTrue();
    assertThat(state().getSelectedThreadIds()).isEmpty();

    viewModel.undoLastArchiveMutation(unlockCapability());
    repository.succeedMutation();

    assertThat(repository.unarchivedIds).containsExactly(7L);
    assertThat(state().isUndoAvailable()).isFalse();
  }

  @Test
  public void mutationFailureIsRecoverableState() {
    repository.emit(new ConversationListSnapshot(List.of(entry(4L)), 0));
    viewModel.toggleSelection(4L);

    viewModel.archiveSelected();
    repository.failMutation();

    assertThat(state().getActiveMutation()).isEqualTo(ConversationListUiState.Mutation.NONE);
    assertThat(state().getError()).isEqualTo(ConversationListUiState.Error.MUTATION_FAILED);
    assertThat(state().getSelectedThreadIds()).containsExactly(4L);
  }

  @Test
  public void sendDraftsMapsSelectionAndClearsItOnSuccess() {
    AtomicReference<SendSelectedDrafts.Input> input = new AtomicReference<>();
    AtomicReference<SendSelectedDrafts.Callback> callback = new AtomicReference<>();
    when(sendSelectedDrafts.execute(any(), any(), any())).thenAnswer(invocation -> {
      input.set(invocation.getArgument(0));
      callback.set(invocation.getArgument(2));
      return mock(AppTaskExecutor.TaskHandle.class);
    });
    repository.emit(new ConversationListSnapshot(List.of(entry(8L)), 0));
    viewModel.toggleSelection(8L);

    viewModel.sendSelectedDrafts(unlockCapability());

    assertThat(state().getActiveMutation()).isEqualTo(ConversationListUiState.Mutation.SEND_DRAFTS);
    assertThat(input.get().getTargets()).extracting(SendSelectedDrafts.Target::getThreadId)
        .containsExactly(8L);
    callback.get().onSuccess(null);
    assertThat(state().getActiveMutation()).isEqualTo(ConversationListUiState.Mutation.NONE);
    assertThat(state().getSelectedThreadIds()).isEmpty();
  }

  @Test
  public void sendDraftFailureKeepsSelectionAndReportsError() {
    AtomicReference<SendSelectedDrafts.Callback> callback = new AtomicReference<>();
    when(sendSelectedDrafts.execute(any(), any(), any())).thenAnswer(invocation -> {
      callback.set(invocation.getArgument(2));
      return mock(AppTaskExecutor.TaskHandle.class);
    });
    repository.emit(new ConversationListSnapshot(List.of(entry(9L)), 0));
    viewModel.toggleSelection(9L);

    viewModel.sendSelectedDrafts(unlockCapability());
    callback.get().onFailure(new RuntimeException("failed"));

    assertThat(state().getError()).isEqualTo(ConversationListUiState.Error.SEND_DRAFTS_FAILED);
    assertThat(state().getSelectedThreadIds()).containsExactly(9L);
  }

  @Test
  public void deleteFailureKeepsSelectionAndReportsError() {
    repository.emit(new ConversationListSnapshot(List.of(entry(10L)), 0));
    viewModel.toggleSelection(10L);

    viewModel.deleteSelected(unlockCapability());
    repository.failMutation();

    assertThat(state().getError()).isEqualTo(ConversationListUiState.Error.DELETE_FAILED);
    assertThat(state().getSelectedThreadIds()).containsExactly(10L);
  }

  @Test
  public void unreadInboxSwipeRestoresUnreadOnUndo() {
    repository.emit(new ConversationListSnapshot(List.of(entry(15L)), 0));

    viewModel.archiveFromSwipe(15L, false, unlockCapability());
    assertThat(repository.swipeArchived).isTrue();
    assertThat(repository.swipeUpdatesReadState).isTrue();
    repository.succeedMutation();

    viewModel.undoLastArchiveMutation(unlockCapability());
    assertThat(repository.swipeArchived).isFalse();
    assertThat(repository.swipeUpdatesReadState).isTrue();
  }

  @Test
  public void reminderPolicyPublishesImmutableKind() {
    AtomicReference<ConversationListReminderPolicy.Callback> callback = new AtomicReference<>();
    ConversationListReminderPolicy policy = policyCallback -> {
      callback.set(policyCallback);
      return mock(AppTaskExecutor.TaskHandle.class);
    };

    viewModel = new ConversationListViewModel(repository, sendSelectedDrafts, policy,
        new ConversationListStateStore(new SavedStateHandle(), false));
    callback.get().onResult(ConversationListReminderPolicy.Kind.DELIVERY_REPORTS);

    assertThat(state().getReminderKind())
        .isEqualTo(ConversationListReminderPolicy.Kind.DELIVERY_REPORTS);
  }

  @Test
  public void injectedDependenciesSelectArchivedRepositoryQuery() {
    ConversationListViewModel created = new ConversationListViewModel(repository,
        mock(SendSelectedDrafts.class), reminderPolicy,
        new ConversationListStateStore(new SavedStateHandle(), true));

    assertThat(repository.queries).last().isEqualTo(new ConversationListQuery(true, ""));
    assertThat(created.getState().getValue().isArchived()).isTrue();
  }

  private ConversationListUiState state() {
    return viewModel.getState().getValue();
  }

  private static ConversationListEntry entry(long threadId) {
    return new ConversationListEntry(threadId, "1", 10L, 1L, true, "snippet", 0L, null, 2, false, 0, 0L);
  }

  private static ConversationUnlockCapability unlockCapability() {
    return new ConversationUnlockCapability(new MasterSecret(
        new SecretKeySpec(new byte[16], "AES"), new SecretKeySpec(new byte[16], "HmacSHA1")));
  }

  private static final class FakeConversationRepository implements ConversationRepository {
    private final List<ConversationListQuery> queries = new ArrayList<>();
    private Observer                         observer;
    private FakeSubscription                 activeSubscription;
    private MutationCallback                 mutationCallback;
    private Set<Long>                        archivedIds = Set.of();
    private Set<Long>                        unarchivedIds = Set.of();
    private boolean                          swipeArchived;
    private boolean                          swipeUpdatesReadState;

    @Override
    public Subscription observe(ConversationListQuery query, Observer observer) {
      queries.add(query);
      this.observer = observer;
      this.activeSubscription = new FakeSubscription();
      return activeSubscription;
    }

    @Override
    public AppTaskExecutor.TaskHandle archive(Set<Long> threadIds, MutationCallback callback) {
      archivedIds = Set.copyOf(threadIds);
      mutationCallback = callback;
      return mock(AppTaskExecutor.TaskHandle.class);
    }

    @Override
    public AppTaskExecutor.TaskHandle unarchive(Set<Long> threadIds, MutationCallback callback) {
      unarchivedIds = Set.copyOf(threadIds);
      mutationCallback = callback;
      return mock(AppTaskExecutor.TaskHandle.class);
    }

    @Override
    public AppTaskExecutor.TaskHandle delete(Set<Long> threadIds,
        ConversationUnlockCapability unlockCapability, MutationCallback callback) {
      mutationCallback = callback;
      return mock(AppTaskExecutor.TaskHandle.class);
    }

    @Override
    public AppTaskExecutor.TaskHandle markAllRead(ConversationUnlockCapability unlockCapability,
        MutationCallback callback) {
      mutationCallback = callback;
      return mock(AppTaskExecutor.TaskHandle.class);
    }

    @Override
    public AppTaskExecutor.TaskHandle setArchivedFromSwipe(long threadId, boolean archived,
        boolean updateReadState, ConversationUnlockCapability unlockCapability,
        MutationCallback callback) {
      swipeArchived = archived;
      swipeUpdatesReadState = updateReadState;
      mutationCallback = callback;
      return mock(AppTaskExecutor.TaskHandle.class);
    }

    private void emit(ConversationListSnapshot snapshot) {
      observer.onSnapshot(snapshot);
    }

    private void succeedMutation() {
      mutationCallback.onSuccess();
    }

    private void failMutation() {
      mutationCallback.onFailure(new RuntimeException("failed"));
    }
  }

  private static final class FakeSubscription implements ConversationRepository.Subscription {
    private boolean closed;

    @Override
    public void refresh() {}

    @Override
    public void close() {
      closed = true;
    }
  }
}