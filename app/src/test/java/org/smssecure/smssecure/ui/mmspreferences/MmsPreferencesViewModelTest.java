package org.smssecure.smssecure.ui.mmspreferences;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.mockito.ArgumentCaptor;

import org.smssecure.smssecure.data.settings.ApnDefaultsRepository;
import org.smssecure.smssecure.mms.LegacyMmsConnection;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor.TaskHandle;

public class MmsPreferencesViewModelTest {
  @Test
  public void loadPublishesApnDefaults() {
    ApnDefaultsRepository repository = mock(ApnDefaultsRepository.class);
    when(repository.load(any())).thenReturn(new TaskHandle());
    MmsPreferencesViewModel viewModel = new MmsPreferencesViewModel(repository);

    viewModel.load();
    assertThat(viewModel.getState().getValue().isLoading()).isTrue();

    ArgumentCaptor<ApnDefaultsRepository.Callback> callback =
        ArgumentCaptor.forClass(ApnDefaultsRepository.Callback.class);
    verify(repository).load(callback.capture());
    LegacyMmsConnection.Apn defaults = mock(LegacyMmsConnection.Apn.class);
    callback.getValue().onSuccess(defaults);

    assertThat(viewModel.getState().getValue().getDefaults()).isSameAs(defaults);
    assertThat(viewModel.getState().getValue().isLoading()).isFalse();
  }

  @Test
  public void replacementCancelsPriorLoadAndSuppressesLateResult() {
    ApnDefaultsRepository repository = mock(ApnDefaultsRepository.class);
    TaskHandle firstHandle = mock(TaskHandle.class);
    when(repository.load(any())).thenReturn(firstHandle, new TaskHandle());
    MmsPreferencesViewModel viewModel = new MmsPreferencesViewModel(repository);

    viewModel.load();
    viewModel.load();

    ArgumentCaptor<ApnDefaultsRepository.Callback> callback =
        ArgumentCaptor.forClass(ApnDefaultsRepository.Callback.class);
    verify(repository, org.mockito.Mockito.times(2)).load(callback.capture());
    callback.getAllValues().get(0).onSuccess(mock(LegacyMmsConnection.Apn.class));

    verify(firstHandle).cancel();
    assertThat(viewModel.getState().getValue().isLoading()).isTrue();
    assertThat(viewModel.getState().getValue().getDefaults()).isNull();
  }
}