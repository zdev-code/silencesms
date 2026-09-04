package org.smssecure.smssecure.ui.importexport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.mockito.ArgumentCaptor;

import org.smssecure.smssecure.util.concurrent.AppTaskExecutor;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor.TaskHandle;

import java.util.concurrent.Callable;

public class ImportExportViewModelTest {
  @Test
  public void publishesTypedConsumableCompletion() throws Exception {
    AppTaskExecutor executor = mock(AppTaskExecutor.class);
    when(executor.submitParallel(any(), any(), any())).thenReturn(new TaskHandle());
    ImportExportViewModel viewModel = new ImportExportViewModel(executor);
    Callable<Integer> work = () -> 0;

    viewModel.start(ImportExportUiState.Operation.IMPORT_ENCRYPTED, 10, 20, work);

    ImportExportUiState running = viewModel.getState().getValue();
    assertThat(running.isRunning()).isTrue();
    assertThat(running.getTitleResource()).isEqualTo(10);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<AppTaskExecutor.SuccessCallback<Integer>> success =
        ArgumentCaptor.forClass(AppTaskExecutor.SuccessCallback.class);
    verify(executor).submitParallel(any(), success.capture(), any());
    success.getValue().onSuccess(0);

    ImportExportUiState completed = viewModel.getState().getValue();
    assertThat(completed.getCompletedOperation())
        .isEqualTo(ImportExportUiState.Operation.IMPORT_ENCRYPTED);
    assertThat(completed.getResult()).isEqualTo(0);
    viewModel.acknowledgeResult();
    assertThat(viewModel.getState().getValue().getResult()).isNull();
  }

  @Test
  public void replacementCancelsPriorOperationAndSuppressesLateFailure() {
    AppTaskExecutor executor = mock(AppTaskExecutor.class);
    TaskHandle firstHandle = mock(TaskHandle.class);
    when(executor.submitParallel(any(), any(), any())).thenReturn(firstHandle, new TaskHandle());
    ImportExportViewModel viewModel = new ImportExportViewModel(executor);

    viewModel.start(ImportExportUiState.Operation.EXPORT, 10, 20, () -> 0);
    viewModel.start(ImportExportUiState.Operation.IMPORT_PLAINTEXT, 30, 40, () -> 0);

    ArgumentCaptor<AppTaskExecutor.FailureCallback> failure =
        ArgumentCaptor.forClass(AppTaskExecutor.FailureCallback.class);
    verify(executor, org.mockito.Mockito.times(2)).submitParallel(any(), any(), failure.capture());
    failure.getAllValues().get(0).onFailure(new IllegalStateException("late"));

    verify(firstHandle).cancel();
    ImportExportUiState state = viewModel.getState().getValue();
    assertThat(state.isRunning()).isTrue();
    assertThat(state.getTitleResource()).isEqualTo(30);
    assertThat(state.getResult()).isNull();
  }

  @Test
  public void relockCancelsOperationClearsStateAndSuppressesLateSuccess() {
    AppTaskExecutor executor = mock(AppTaskExecutor.class);
    TaskHandle handle = mock(TaskHandle.class);
    when(executor.submitParallel(any(), any(), any())).thenReturn(handle);
    ImportExportViewModel viewModel = new ImportExportViewModel(executor);

    viewModel.start(ImportExportUiState.Operation.EXPORT, 10, 20, () -> 0);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<AppTaskExecutor.SuccessCallback<Integer>> success =
        ArgumentCaptor.forClass(AppTaskExecutor.SuccessCallback.class);
    verify(executor).submitParallel(any(), success.capture(), any());

    viewModel.clearSensitiveState();
    success.getValue().onSuccess(0);

    verify(handle).cancel();
    ImportExportUiState state = viewModel.getState().getValue();
    assertThat(state.isRunning()).isFalse();
    assertThat(state.getCompletedOperation()).isNull();
    assertThat(state.getResult()).isNull();
  }
}