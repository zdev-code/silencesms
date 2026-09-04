package org.smssecure.smssecure.ui.recipientpreferences;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.net.Uri;

import org.junit.Test;
import org.mockito.ArgumentCaptor;

import org.smssecure.smssecure.color.MaterialColor;
import org.smssecure.smssecure.data.recipient.RecipientPreferencesRepository;
import org.smssecure.smssecure.database.RecipientPreferenceDatabase.VibrateState;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor.TaskHandle;

public class RecipientPreferencesViewModelTest {
  @Test
  public void delegatesEveryMutationAndPublishesCompletion() {
    RecipientPreferencesRepository repository = repositoryWithHandles();
    RecipientPreferencesViewModel viewModel = new RecipientPreferencesViewModel(repository);
    long[] ids = {3L, 8L};
    Uri ringtone = mock(Uri.class);
    MaterialColor color = mock(MaterialColor.class);

    viewModel.setRingtone(ids, ringtone);
    viewModel.setVibrate(ids, VibrateState.ENABLED);
    viewModel.setMuted(ids, 100L);
    viewModel.setBlocked(ids, true);
    viewModel.setColor(ids, color);

    assertThat(viewModel.getState().getValue().getPendingMutations()).isEqualTo(5);
    verify(repository).setRingtone(eq(ids), eq(ringtone), any());
    verify(repository).setVibrate(eq(ids), eq(VibrateState.ENABLED), any());
    verify(repository).setMuted(eq(ids), eq(100L), any());
    verify(repository).setBlocked(eq(ids), eq(true), any());
    verify(repository).setColor(eq(ids), eq(color), any());

    ArgumentCaptor<RecipientPreferencesRepository.Callback> callbacks =
        ArgumentCaptor.forClass(RecipientPreferencesRepository.Callback.class);
    verify(repository).setBlocked(any(long[].class), anyBoolean(), callbacks.capture());
    callbacks.getValue().onComplete();
    assertThat(viewModel.getState().getValue().getPendingMutations()).isEqualTo(4);
  }

  @Test
  public void failureIsConsumableAndOutstandingTaskIsCancelled() {
    RecipientPreferencesRepository repository = repositoryWithHandles();
    TaskHandle handle = mock(TaskHandle.class);
    when(repository.setMuted(any(long[].class), anyLong(), any())).thenReturn(handle);
    RecipientPreferencesViewModel viewModel = new RecipientPreferencesViewModel(repository);

    viewModel.setMuted(new long[] {4L}, 20L);
    ArgumentCaptor<RecipientPreferencesRepository.Callback> callback =
        ArgumentCaptor.forClass(RecipientPreferencesRepository.Callback.class);
    verify(repository).setMuted(any(long[].class), anyLong(), callback.capture());
    callback.getValue().onFailure(new IllegalStateException("failure"));

    assertThat(viewModel.getState().getValue().isFailed()).isTrue();
    viewModel.acknowledgeFailure();
    assertThat(viewModel.getState().getValue().isFailed()).isFalse();
    viewModel.onCleared();
    verify(handle).cancel();
  }

  @Test
  public void relockCancelsOutstandingMutationsAndClearsState() {
    RecipientPreferencesRepository repository = repositoryWithHandles();
    TaskHandle handle = mock(TaskHandle.class);
    when(repository.setBlocked(any(long[].class), anyBoolean(), any())).thenReturn(handle);
    RecipientPreferencesViewModel viewModel = new RecipientPreferencesViewModel(repository);

    viewModel.setBlocked(new long[] {4L}, true);
    viewModel.clearSensitiveState();

    verify(handle).cancel();
    assertThat(viewModel.getState().getValue().getPendingMutations()).isZero();
    assertThat(viewModel.getState().getValue().isFailed()).isFalse();
  }

  private static RecipientPreferencesRepository repositoryWithHandles() {
    RecipientPreferencesRepository repository = mock(RecipientPreferencesRepository.class);
    when(repository.setRingtone(any(long[].class), any(), any())).thenReturn(new TaskHandle());
    when(repository.setVibrate(any(long[].class), any(), any())).thenReturn(new TaskHandle());
    when(repository.setMuted(any(long[].class), anyLong(), any())).thenReturn(new TaskHandle());
    when(repository.setBlocked(any(long[].class), anyBoolean(), any())).thenReturn(new TaskHandle());
    when(repository.setColor(any(long[].class), any(), any())).thenReturn(new TaskHandle());
    return repository;
  }
}