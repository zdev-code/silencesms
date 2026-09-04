package org.smssecure.smssecure.ui.importexport;

import androidx.lifecycle.ViewModel;

import org.smssecure.smssecure.util.concurrent.AppTaskExecutor;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor.TaskHandle;

import java.util.concurrent.Callable;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import kotlinx.coroutines.flow.MutableStateFlow;
import kotlinx.coroutines.flow.StateFlow;
import kotlinx.coroutines.flow.StateFlowKt;

@HiltViewModel
public final class ImportExportViewModel extends ViewModel {
  private static final int ERROR_IO = 2;

  private final AppTaskExecutor executor;
  private final MutableStateFlow<ImportExportUiState> state = StateFlowKt.MutableStateFlow(
      new ImportExportUiState(false, 0, 0, null, null));
  private TaskHandle currentTask;
  private int generation;

  @Inject
  ImportExportViewModel(AppTaskExecutor executor) {
    this.executor = executor;
  }

  public StateFlow<ImportExportUiState> getState() { return state; }

  public void start(ImportExportUiState.Operation operation, int titleResource, int messageResource,
                    Callable<Integer> work) {
    cancelCurrent();
    int requestGeneration = generation;
    state.setValue(new ImportExportUiState(true, titleResource, messageResource, null, null));
    currentTask = executor.submitParallel(work,
        result -> complete(requestGeneration, operation, result),
        exception -> complete(requestGeneration, operation, ERROR_IO));
  }

  public void acknowledgeResult() {
    state.setValue(new ImportExportUiState(false, 0, 0, null, null));
  }

  public void clearSensitiveState() {
    cancelCurrent();
    acknowledgeResult();
  }

  private void complete(int requestGeneration, ImportExportUiState.Operation operation, int result) {
    if (requestGeneration != generation) return;
    currentTask = null;
    state.setValue(new ImportExportUiState(false, 0, 0, operation, result));
  }

  private void cancelCurrent() {
    generation++;
    if (currentTask != null) currentTask.cancel();
    currentTask = null;
  }

  @Override protected void onCleared() {
    clearSensitiveState();
  }
}