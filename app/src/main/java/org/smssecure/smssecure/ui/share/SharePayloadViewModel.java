package org.smssecure.smssecure.ui.share;

import android.net.Uri;

import androidx.lifecycle.ViewModel;

import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.data.share.SharePayloadRepository;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor.TaskHandle;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import kotlinx.coroutines.flow.MutableStateFlow;
import kotlinx.coroutines.flow.StateFlow;
import kotlinx.coroutines.flow.StateFlowKt;

@HiltViewModel
public final class SharePayloadViewModel extends ViewModel {
  public interface SensitiveResultSink { void resolved(Uri uri); }

  private final SharePayloadRepository repository;
  private final MutableStateFlow<SharePayloadUiState> state =
      StateFlowKt.MutableStateFlow(new SharePayloadUiState(false, false));
  private TaskHandle resolveTask;
  private int generation;

  @Inject
  SharePayloadViewModel(SharePayloadRepository repository) {
    this.repository = repository;
  }

  public StateFlow<SharePayloadUiState> getState() { return state; }

  public void resolve(MasterSecret masterSecret, Uri source, String mimeType, SensitiveResultSink sink) {
    if (resolveTask != null) resolveTask.cancel();
    int requestGeneration = ++generation;
    state.setValue(new SharePayloadUiState(true, false));
    resolveTask = repository.resolve(masterSecret, source, mimeType, new SharePayloadRepository.Callback() {
      @Override public void onSuccess(Uri uri) {
        if (requestGeneration != generation) {
          repository.delete(uri);
          return;
        }
        resolveTask = null;
        state.setValue(new SharePayloadUiState(false, false));
        sink.resolved(uri);
      }

      @Override public void onFailure(Exception exception) {
        if (requestGeneration != generation) return;
        resolveTask = null;
        state.setValue(new SharePayloadUiState(false, true));
      }
    });
  }

  public void discard(Uri resolved) { repository.delete(resolved); }

  @Override protected void onCleared() {
    generation++;
    if (resolveTask != null) resolveTask.cancel();
    resolveTask = null;
  }
}