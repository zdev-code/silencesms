package org.smssecure.smssecure.ui.mediapreview;

import androidx.lifecycle.ViewModel;

import org.smssecure.smssecure.data.media.MediaOverviewRepository;
import org.smssecure.smssecure.domain.conversation.ConversationUnlockCapability;
import org.smssecure.smssecure.util.SaveAttachmentTask;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor.TaskHandle;

import java.util.List;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import kotlinx.coroutines.flow.MutableStateFlow;
import kotlinx.coroutines.flow.StateFlow;
import kotlinx.coroutines.flow.StateFlowKt;

@HiltViewModel
public final class MediaPreviewViewModel extends ViewModel {
  public interface SensitiveSnapshotSink {
    void render(MediaOverviewRepository.PreviewSnapshot snapshot);
  }

  private final MediaOverviewRepository repository;
  private final MutableStateFlow<MediaPreviewUiState> state =
      StateFlowKt.MutableStateFlow(new MediaPreviewUiState(false, null));
  private TaskHandle loadTask;
  private TaskHandle saveTask;
  private int generation;

  @Inject
  MediaPreviewViewModel(MediaOverviewRepository repository) {
    this.repository = repository;
  }

  public StateFlow<MediaPreviewUiState> getState() { return state; }

  public void load(long partRowId, long partUniqueId, long messageId, long threadId,
                   long recipientId, ConversationUnlockCapability unlockCapability,
                   SensitiveSnapshotSink snapshotSink) {
    cancelTasks();
    int requestGeneration = ++generation;
    loadTask = repository.loadPreview(partRowId, partUniqueId, messageId, threadId, recipientId,
        unlockCapability, new MediaOverviewRepository.Callback<>() {
          @Override public void onSuccess(MediaOverviewRepository.PreviewSnapshot snapshot) {
            if (requestGeneration != generation) return;
            loadTask = null;
            snapshotSink.render(snapshot);
          }

          @Override public void onFailure(Exception exception) {
            if (requestGeneration != generation) return;
            loadTask = null;
            state.setValue(new MediaPreviewUiState(false, SaveAttachmentTask.FAILURE));
          }
        });
  }

  public void save(ConversationUnlockCapability unlockCapability,
                   SaveAttachmentTask.Attachment attachment) {
    if (saveTask != null) saveTask.cancel();
    int requestGeneration = ++generation;
    state.setValue(new MediaPreviewUiState(true, null));
    saveTask = repository.saveAttachments(unlockCapability, List.of(attachment),
        new MediaOverviewRepository.Callback<>() {
          @Override public void onSuccess(Integer result) { complete(requestGeneration, result); }
          @Override public void onFailure(Exception exception) {
            complete(requestGeneration, SaveAttachmentTask.FAILURE);
          }
        });
  }

  public void acknowledgeResult() {
    state.setValue(new MediaPreviewUiState(false, null));
  }

  public void clearSensitiveState() {
    generation++;
    cancelTasks();
    state.setValue(new MediaPreviewUiState(false, null));
  }

  private void cancelTasks() {
    if (loadTask != null) loadTask.cancel();
    if (saveTask != null) saveTask.cancel();
    loadTask = null;
    saveTask = null;
  }

  private void complete(int requestGeneration, int result) {
    if (requestGeneration != generation) return;
    saveTask = null;
    state.setValue(new MediaPreviewUiState(false, result));
  }

  @Override protected void onCleared() {
    clearSensitiveState();
  }
}