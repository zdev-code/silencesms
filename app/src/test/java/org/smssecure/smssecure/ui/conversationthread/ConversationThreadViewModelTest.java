package org.smssecure.smssecure.ui.conversationthread;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.database.Cursor;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.SavedStateHandle;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.data.conversationthread.ConversationMessageRow;
import org.smssecure.smssecure.data.conversationthread.ConversationThreadQuery;
import org.smssecure.smssecure.data.conversationthread.ConversationThreadRepository;
import org.smssecure.smssecure.data.conversationthread.ConversationThreadSnapshot;
import org.smssecure.smssecure.database.MmsSmsColumns;
import org.smssecure.smssecure.database.MmsSmsDatabase;
import org.smssecure.smssecure.domain.conversation.ConversationUnlockCapability;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor;

import java.util.List;
import java.util.Set;

import javax.crypto.spec.SecretKeySpec;

public class ConversationThreadViewModelTest {
  @Rule public final InstantTaskExecutorRule executorRule = new InstantTaskExecutorRule();

  private FakeRepository repository;
  private ConversationThreadViewModel viewModel;

  @Before
  public void setUp() {
    repository = new FakeRepository();
    viewModel = new ConversationThreadViewModel(repository,
        new ConversationThreadStateStore(new SavedStateHandle(), 7L, -1L));
  }

  @Test
  public void initialSnapshotPreservesPartialLimitAndResolvesLastSeen() {
    assertThat(repository.query).isEqualTo(
        new ConversationThreadQuery(7L, ConversationThreadViewModel.PARTIAL_CONVERSATION_LIMIT, -1L));

    repository.emit(new ConversationThreadSnapshot(List.of(message(4L, false)), 33L, true));

    ConversationThreadUiState state = viewModel.getState().getValue();
    assertThat(state.isLoading()).isFalse();
    assertThat(state.isLimited()).isTrue();
    assertThat(state.getLastSeen()).isEqualTo(33L);
  }

  @Test
  public void loadMoreReobservesWithoutLimitAndSurvivesSavedState() {
    viewModel.loadMore();

    assertThat(repository.query.getLimit()).isZero();
    assertThat(repository.closedSubscriptions).isEqualTo(1);
  }

  @Test
  public void selectionIsStableAndDeleteUsesTypedReferences() {
    ConversationMessageRow message = message(4L, false);
    repository.emit(new ConversationThreadSnapshot(List.of(message), 0L, false));
    viewModel.toggleSelection(message.getStableId());

    viewModel.deleteSelected(unlockCapability());

    assertThat(repository.deleted).containsExactly(
        new ConversationThreadRepository.MessageReference(4L, false));
    assertThat(viewModel.getState().getValue().getMutation())
        .isEqualTo(ConversationThreadUiState.Mutation.DELETE);
    repository.succeedDelete(true);
    assertThat(viewModel.getState().getValue().isThreadDeleted()).isTrue();
    assertThat(viewModel.getState().getValue().getSelectedMessageIds()).isEmpty();
  }

  private static ConversationMessageRow message(long id, boolean mms) {
    Cursor cursor = mock(Cursor.class);
    String[] columns = {MmsSmsColumns.ID, MmsSmsColumns.UNIQUE_ROW_ID, MmsSmsDatabase.TRANSPORT};
    String transport = mms ? MmsSmsDatabase.MMS_TRANSPORT : MmsSmsDatabase.SMS_TRANSPORT;
    when(cursor.getColumnNames()).thenReturn(columns);
    when(cursor.getType(0)).thenReturn(Cursor.FIELD_TYPE_INTEGER);
    when(cursor.getType(1)).thenReturn(Cursor.FIELD_TYPE_STRING);
    when(cursor.getType(2)).thenReturn(Cursor.FIELD_TYPE_STRING);
    when(cursor.getLong(0)).thenReturn(id);
    when(cursor.getString(1)).thenReturn(transport.toUpperCase() + "::" + id + "::10");
    when(cursor.getString(2)).thenReturn(transport);
    when(cursor.getColumnIndexOrThrow(MmsSmsColumns.ID)).thenReturn(0);
    when(cursor.getColumnIndexOrThrow(MmsSmsColumns.UNIQUE_ROW_ID)).thenReturn(1);
    when(cursor.getColumnIndexOrThrow(MmsSmsDatabase.TRANSPORT)).thenReturn(2);
    return ConversationMessageRow.copyCurrent(cursor);
  }

  private static ConversationUnlockCapability unlockCapability() {
    MasterSecret secret = new MasterSecret(new SecretKeySpec(new byte[16], "AES"),
                                           new SecretKeySpec(new byte[16], "HmacSHA1"));
    return new ConversationUnlockCapability(secret, () -> secret);
  }

  private static final class FakeRepository implements ConversationThreadRepository {
    private ConversationThreadQuery query;
    private Observer observer;
    private int closedSubscriptions;
    private Set<MessageReference> deleted;
    private MutationCallback mutationCallback;

    @Override
    public Subscription observe(ConversationThreadQuery query, Observer observer) {
      this.query = query;
      this.observer = observer;
      return new Subscription() {
        @Override public void refresh() {}
        @Override public void close() { closedSubscriptions++; }
      };
    }

    @Override
    public AppTaskExecutor.TaskHandle delete(Set<MessageReference> messages,
                                             ConversationUnlockCapability unlockCapability,
                                             MutationCallback callback) {
      this.deleted = messages;
      this.mutationCallback = callback;
      return mock(AppTaskExecutor.TaskHandle.class);
    }

    void emit(ConversationThreadSnapshot snapshot) { observer.onSnapshot(snapshot); }
    void succeedDelete(boolean threadDeleted) { mutationCallback.onSuccess(threadDeleted); }
  }
}
