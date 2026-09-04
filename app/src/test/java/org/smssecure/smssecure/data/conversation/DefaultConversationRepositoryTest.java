package org.smssecure.smssecure.data.conversation;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.smssecure.smssecure.database.ThreadDatabase;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.domain.conversation.ConversationUnlockCapability;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Callable;

import javax.crypto.spec.SecretKeySpec;

public class DefaultConversationRepositoryTest {
  private AppTaskExecutor                         executor;
  private DefaultConversationRepository.DataSource dataSource;
  private DefaultConversationRepository.InvalidationSource invalidationSource;
  private ConversationRepository.MutationCallback callback;
  private DefaultConversationRepository.NotificationUpdater notificationUpdater;
  private DefaultConversationRepository          repository;

  @Before
  public void setUp() {
    executor   = mock(AppTaskExecutor.class);
    dataSource = mock(DefaultConversationRepository.DataSource.class);
    invalidationSource = mock(DefaultConversationRepository.InvalidationSource.class);
    callback   = mock(ConversationRepository.MutationCallback.class);
    notificationUpdater = mock(DefaultConversationRepository.NotificationUpdater.class);
    repository = new DefaultConversationRepository(executor, dataSource, invalidationSource,
                            notificationUpdater);
  }

  @Test
  public void archiveRunsInSelectionOrderAndReportsSuccess() throws Exception {
    runSubmittedWorkImmediately();
    LinkedHashSet<Long> selected = new LinkedHashSet<>();
    selected.add(7L);
    selected.add(3L);

    repository.archive(selected, callback);

    InOrder order = inOrder(dataSource);
    order.verify(dataSource).archive(7L);
    order.verify(dataSource).archive(3L);
    verify(callback).onSuccess();
  }

  @Test
  public void mutationCopiesIdsBeforeSubmitting() throws Exception {
    @SuppressWarnings("unchecked")
    final Callable<Void>[] submitted = new Callable[1];
    when(executor.submitSerial(any(), any(), any())).thenAnswer(invocation -> {
      submitted[0] = invocation.getArgument(0);
      return mock(AppTaskExecutor.TaskHandle.class);
    });
    Set<Long> selected = new LinkedHashSet<>(Set.of(5L));

    repository.unarchive(selected, callback);
    selected.clear();
    submitted[0].call();

    verify(dataSource).unarchive(5L);
  }

  @Test
  public void archiveShortcutCannotBeMutated() {
    assertThatThrownBy(() -> repository.archive(Set.of(-1L), callback))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  public void executorFailureIsForwarded() {
    RuntimeException failure = new RuntimeException("failed");
    when(executor.submitSerial(any(), any(), any())).thenAnswer(invocation -> {
      AppTaskExecutor.FailureCallback failureCallback = invocation.getArgument(2);
      failureCallback.onFailure(failure);
      return mock(AppTaskExecutor.TaskHandle.class);
    });

    repository.archive(Set.of(9L), callback);

    verify(callback).onFailure(failure);
  }

  @Test
  public void deleteRefreshesNotificationsAfterDatabaseMutation() throws Exception {
    runSubmittedWorkImmediately();
    ConversationUnlockCapability capability = unlockCapability();

    repository.delete(Set.of(6L), capability, callback);

    InOrder order = inOrder(dataSource, notificationUpdater);
    order.verify(dataSource).delete(Set.of(6L));
    order.verify(notificationUpdater).update(any());
    verify(callback).onSuccess();
  }

  @Test
  public void markAllReadRefreshesNotificationsAfterDatabaseMutation() throws Exception {
    runSubmittedWorkImmediately();

    repository.markAllRead(unlockCapability(), callback);

    InOrder order = inOrder(dataSource, notificationUpdater);
    order.verify(dataSource).markAllRead();
    order.verify(notificationUpdater).update(any());
    verify(callback).onSuccess();
  }

  @Test
  public void swipeArchiveUpdatesReadBeforeNotifications() throws Exception {
    runSubmittedWorkImmediately();

    repository.setArchivedFromSwipe(7L, true, true, unlockCapability(), callback);

    InOrder order = inOrder(dataSource, notificationUpdater);
    order.verify(dataSource).archive(7L);
    order.verify(dataSource).setRead(7L);
    order.verify(notificationUpdater).update(any());
  }

  @Test
  public void swipeUnarchiveWithoutReadChangeSkipsNotificationRefresh() throws Exception {
    runSubmittedWorkImmediately();

    repository.setArchivedFromSwipe(8L, false, false, unlockCapability(), callback);

    verify(dataSource).unarchive(8L);
    verify(notificationUpdater, org.mockito.Mockito.never()).update(any());
  }

  @Test
  public void observationMapsMetadataAndClosesCursor() throws Exception {
    Cursor cursor = conversationCursor();
    when(dataSource.query(new ConversationListQuery(false, ""))).thenReturn(cursor);
    when(dataSource.getArchivedCount()).thenReturn(4);
    runSubmittedWorkImmediately();
    ConversationRepository.Observer observer = mock(ConversationRepository.Observer.class);

    ConversationRepository.Subscription subscription =
        repository.observe(new ConversationListQuery(false, null), observer);

    ArgumentCaptor<ConversationListSnapshot> snapshotCaptor =
        ArgumentCaptor.forClass(ConversationListSnapshot.class);
    verify(observer).onSnapshot(snapshotCaptor.capture());
    ConversationListSnapshot snapshot = snapshotCaptor.getValue();
    org.assertj.core.api.Assertions.assertThat(snapshot.getEntries()).hasSize(1);
    org.assertj.core.api.Assertions.assertThat(snapshot.getEntries().get(0).getThreadId()).isEqualTo(12L);
    org.assertj.core.api.Assertions.assertThat(snapshot.getEntries().get(0).getRecipientIds()).isEqualTo("8 9");
    org.assertj.core.api.Assertions.assertThat(snapshot.getArchivedCount()).isEqualTo(4);
    verify(cursor).close();
    verify(invalidationSource).register(any());

    subscription.close();
    verify(invalidationSource).unregister(any());
  }

  @Test
  public void closeSuppressesLateSnapshot() {
    @SuppressWarnings("unchecked")
    final AppTaskExecutor.SuccessCallback<ConversationListSnapshot>[] success =
        new AppTaskExecutor.SuccessCallback[1];
    when(executor.submitSerial(any(), any(), any())).thenAnswer(invocation -> {
      success[0] = invocation.getArgument(1);
      return mock(AppTaskExecutor.TaskHandle.class);
    });
    ConversationRepository.Observer observer = mock(ConversationRepository.Observer.class);
    ConversationRepository.Subscription subscription =
        repository.observe(new ConversationListQuery(false, null), observer);

    subscription.close();
    success[0].onSuccess(new ConversationListSnapshot(java.util.List.of(), 0));

    verify(observer, org.mockito.Mockito.never()).onSnapshot(any());
  }

  private Cursor conversationCursor() {
    Cursor cursor = mock(Cursor.class);
    when(cursor.moveToNext()).thenReturn(true, false);
    when(cursor.getColumnIndexOrThrow(ThreadDatabase.ID)).thenReturn(0);
    when(cursor.getColumnIndexOrThrow(ThreadDatabase.RECIPIENT_IDS)).thenReturn(1);
    when(cursor.getColumnIndexOrThrow(ThreadDatabase.DATE)).thenReturn(2);
    when(cursor.getColumnIndexOrThrow(ThreadDatabase.MESSAGE_COUNT)).thenReturn(3);
    when(cursor.getColumnIndexOrThrow(ThreadDatabase.READ)).thenReturn(4);
    when(cursor.getColumnIndexOrThrow(ThreadDatabase.SNIPPET)).thenReturn(11);
    when(cursor.getColumnIndexOrThrow(ThreadDatabase.SNIPPET_TYPE)).thenReturn(5);
    when(cursor.getColumnIndexOrThrow(ThreadDatabase.SNIPPET_URI)).thenReturn(6);
    when(cursor.getColumnIndexOrThrow(ThreadDatabase.TYPE)).thenReturn(7);
    when(cursor.getColumnIndexOrThrow(ThreadDatabase.ARCHIVED)).thenReturn(8);
    when(cursor.getColumnIndexOrThrow(ThreadDatabase.STATUS)).thenReturn(9);
    when(cursor.getColumnIndexOrThrow(ThreadDatabase.LAST_SEEN)).thenReturn(10);
    when(cursor.getLong(0)).thenReturn(12L);
    when(cursor.getString(1)).thenReturn("8 9");
    when(cursor.getLong(2)).thenReturn(100L);
    when(cursor.getLong(3)).thenReturn(2L);
    when(cursor.getInt(4)).thenReturn(1);
    when(cursor.getString(11)).thenReturn("snippet");
    when(cursor.getLong(5)).thenReturn(20L);
    when(cursor.isNull(6)).thenReturn(true);
    when(cursor.getInt(7)).thenReturn(ThreadDatabase.DistributionTypes.CONVERSATION);
    when(cursor.getInt(8)).thenReturn(0);
    when(cursor.getInt(9)).thenReturn(0);
    when(cursor.getLong(10)).thenReturn(90L);
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