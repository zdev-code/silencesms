package org.smssecure.smssecure.data.conversationthread;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.database.Cursor;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.database.MmsSmsColumns;
import org.smssecure.smssecure.database.MmsSmsDatabase;
import org.smssecure.smssecure.domain.conversation.ConversationUnlockCapability;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Callable;

import javax.crypto.spec.SecretKeySpec;

public class DefaultConversationThreadRepositoryTest {
  private AppTaskExecutor executor;
  private DefaultConversationThreadRepository.DataSource dataSource;
  private DefaultConversationThreadRepository.InvalidationSource invalidationSource;
  private DefaultConversationThreadRepository repository;

  @Before
  public void setUp() throws Exception {
    executor = mock(AppTaskExecutor.class);
    dataSource = mock(DefaultConversationThreadRepository.DataSource.class);
    invalidationSource = mock(DefaultConversationThreadRepository.InvalidationSource.class);
    repository = new DefaultConversationThreadRepository(executor, dataSource, invalidationSource);
    runSubmittedWorkImmediately();
  }

  @Test
  public void observationCopiesRowsClosesCursorAndResolvesUnknownLastSeen() {
    Cursor cursor = messageCursor();
    when(dataSource.query(9L, 500L)).thenReturn(cursor);
    when(dataSource.getLastSeen(9L)).thenReturn(123L);
    ConversationThreadRepository.Observer observer = mock(ConversationThreadRepository.Observer.class);

    ConversationThreadRepository.Subscription subscription = repository.observe(
        new ConversationThreadQuery(9L, 500L, -1L), observer);

    ArgumentCaptor<ConversationThreadSnapshot> snapshot =
        ArgumentCaptor.forClass(ConversationThreadSnapshot.class);
    verify(observer).onSnapshot(snapshot.capture());
    assertThat(snapshot.getValue().getMessages()).hasSize(1);
    assertThat(snapshot.getValue().getMessages().get(0).getStableId()).isEqualTo("SMS::4::10");
    assertThat(snapshot.getValue().getLastSeen()).isEqualTo(123L);
    assertThat(snapshot.getValue().isLimited()).isFalse();
    verify(cursor).close();
    verify(invalidationSource).register(org.mockito.ArgumentMatchers.eq(9L), any());

    subscription.close();
    verify(invalidationSource).unregister(any());
  }

  @Test
  public void invalidationRefreshesAndPreservesProvidedLastSeen() {
    Cursor initialCursor = messageCursor();
    Cursor refreshedCursor = messageCursor();
    when(dataSource.query(7L, 0L)).thenReturn(initialCursor, refreshedCursor);
    ConversationThreadRepository.Observer observer = mock(ConversationThreadRepository.Observer.class);
    repository.observe(new ConversationThreadQuery(7L, 0L, 55L), observer);
    ArgumentCaptor<Runnable> invalidation = ArgumentCaptor.forClass(Runnable.class);
    verify(invalidationSource).register(org.mockito.ArgumentMatchers.eq(7L), invalidation.capture());

    invalidation.getValue().run();

    verify(dataSource, org.mockito.Mockito.times(2)).query(7L, 0L);
    verify(dataSource, org.mockito.Mockito.never()).getLastSeen(7L);
    verify(observer, org.mockito.Mockito.times(2)).onSnapshot(any());
  }

  @Test
  public void deleteChecksUnlockAndRunsInSelectionOrder() {
    ConversationThreadRepository.MessageReference sms =
        new ConversationThreadRepository.MessageReference(4L, false);
    ConversationThreadRepository.MessageReference mms =
        new ConversationThreadRepository.MessageReference(8L, true);
    LinkedHashSet<ConversationThreadRepository.MessageReference> selected = new LinkedHashSet<>();
    selected.add(sms);
    selected.add(mms);
    when(dataSource.delete(sms)).thenReturn(false);
    when(dataSource.delete(mms)).thenReturn(true);
    ConversationThreadRepository.MutationCallback callback =
        mock(ConversationThreadRepository.MutationCallback.class);

    repository.delete(selected, unlockCapability(), callback);

    InOrder order = inOrder(dataSource);
    order.verify(dataSource).delete(sms);
    order.verify(dataSource).delete(mms);
    verify(callback).onSuccess(true);
  }

  private Cursor messageCursor() {
    Cursor cursor = mock(Cursor.class);
    String[] columns = {MmsSmsColumns.ID, MmsSmsColumns.UNIQUE_ROW_ID, MmsSmsDatabase.TRANSPORT};
    when(cursor.getColumnNames()).thenReturn(columns);
    when(cursor.moveToNext()).thenReturn(true, false);
    when(cursor.getType(0)).thenReturn(Cursor.FIELD_TYPE_INTEGER);
    when(cursor.getType(1)).thenReturn(Cursor.FIELD_TYPE_STRING);
    when(cursor.getType(2)).thenReturn(Cursor.FIELD_TYPE_STRING);
    when(cursor.getLong(0)).thenReturn(4L);
    when(cursor.getString(1)).thenReturn("SMS::4::10");
    when(cursor.getString(2)).thenReturn(MmsSmsDatabase.SMS_TRANSPORT);
    when(cursor.getColumnIndexOrThrow(MmsSmsColumns.ID)).thenReturn(0);
    when(cursor.getColumnIndexOrThrow(MmsSmsColumns.UNIQUE_ROW_ID)).thenReturn(1);
    when(cursor.getColumnIndexOrThrow(MmsSmsDatabase.TRANSPORT)).thenReturn(2);
    return cursor;
  }

  private void runSubmittedWorkImmediately() throws Exception {
    when(executor.submitSerial(any(), any(), any())).thenAnswer(invocation -> {
      Callable<?> work = invocation.getArgument(0);
      AppTaskExecutor.SuccessCallback<Object> success = invocation.getArgument(1);
      success.onSuccess(work.call());
      return mock(AppTaskExecutor.TaskHandle.class);
    });
  }

  private static ConversationUnlockCapability unlockCapability() {
    MasterSecret secret = new MasterSecret(new SecretKeySpec(new byte[16], "AES"),
                                           new SecretKeySpec(new byte[16], "HmacSHA1"));
    return new ConversationUnlockCapability(secret, () -> secret);
  }
}
