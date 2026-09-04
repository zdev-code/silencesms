package org.smssecure.smssecure.ui.mediaoverview;

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
public final class MediaOverviewViewModel extends ViewModel {
  public interface SensitiveSnapshotSink {
    void render(MediaOverviewRepository.Snapshot snapshot);
  }

  private final MediaOverviewRepository repository;
  private final MutableStateFlow<MediaOverviewUiState> state = StateFlowKt.MutableStateFlow(
      new MediaOverviewUiState(MediaOverviewUiState.Phase.IDLE, 0, null));
  private TaskHandle activeTask;
  private int generation;

  @Inject
  MediaOverviewViewModel(MediaOverviewRepository repository) {
    this.repository = repository;
  }

  public StateFlow<MediaOverviewUiState> getState() { return state; }

  public void load(long threadId, long recipientId,
                   ConversationUnlockCapability unlockCapability,
                   SensitiveSnapshotSink snapshotSink) {
    replaceTask();
    int requestGeneration = generation;
    publish(MediaOverviewUiState.Phase.LOADING, 0, null);
    activeTask = repository.load(threadId, recipientId, unlockCapability,
      new MediaOverviewRepository.Callback<>() {
      @Override public void onSuccess(MediaOverviewRepository.Snapshot snapshot) {
        if (requestGeneration != generation) return;
        activeTask = null;
        publish(MediaOverviewUiState.Phase.IDLE, snapshot.getRecords().size(), null);
        snapshotSink.render(snapshot);
      }

      @Override public void onFailure(Exception exception) {
        if (requestGeneration != generation) return;
        activeTask = null;
        publish(MediaOverviewUiState.Phase.IDLE, 0, SaveAttachmentTask.FAILURE);
      }
    });
  }

  public void saveAll(long threadId, ConversationUnlockCapability unlockCapability) {
    replaceTask();
    int requestGeneration = generation;
    publish(MediaOverviewUiState.Phase.COLLECTING, 0, null);
    activeTask = repository.collectAttachments(threadId, unlockCapability,
      new MediaOverviewRepository.Callback<>() {
      @Override public void onSuccess(List<SaveAttachmentTask.Attachment> attachments) {
        if (requestGeneration != generation) return;
        if (attachments.isEmpty()) {
          activeTask = null;
          publish(MediaOverviewUiState.Phase.IDLE, 0, SaveAttachmentTask.FAILURE);
          return;
        }
        publish(MediaOverviewUiState.Phase.SAVING, attachments.size(), null);
        activeTask = repository.saveAttachments(unlockCapability, attachments,
            new MediaOverviewRepository.Callback<>() {
              @Override public void onSuccess(Integer result) {
                if (requestGeneration != generation) return;
                activeTask = null;
                publish(MediaOverviewUiState.Phase.IDLE, attachments.size(), result);
              }

              @Override public void onFailure(Exception exception) {
                if (requestGeneration != generation) return;
                activeTask = null;
                publish(MediaOverviewUiState.Phase.IDLE, attachments.size(), SaveAttachmentTask.FAILURE);
              }
            });
      }

      @Override public void onFailure(Exception exception) {
        if (requestGeneration != generation) return;
        activeTask = null;
        publish(MediaOverviewUiState.Phase.IDLE, 1, SaveAttachmentTask.FAILURE);
      }
    });
  }

  public void acknowledgeResult() {
    MediaOverviewUiState current = state.getValue();
    publish(current.getPhase(), current.getAttachmentCount(), null);
  }

  public void clearSensitiveState() {
    replaceTask();
    publish(MediaOverviewUiState.Phase.IDLE, 0, null);
  }

  private void replaceTask() {
    generation++;
    if (activeTask != null) activeTask.cancel();
    activeTask = null;
  }

  private void publish(MediaOverviewUiState.Phase phase, int count, Integer result) {
    state.setValue(new MediaOverviewUiState(phase, count, result));
  }

  @Override protected void onCleared() {
    clearSensitiveState();
  }
}