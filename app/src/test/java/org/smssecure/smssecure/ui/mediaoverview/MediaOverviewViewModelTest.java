package org.smssecure.smssecure.ui.mediaoverview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.mockito.ArgumentCaptor;

import org.smssecure.smssecure.data.media.MediaOverviewRepository;
import org.smssecure.smssecure.database.ImageDatabase.ImageRecord;
import org.smssecure.smssecure.domain.conversation.ConversationUnlockCapability;
import org.smssecure.smssecure.util.SaveAttachmentTask;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor.TaskHandle;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class MediaOverviewViewModelTest {
  @Test
  public void loadDeliversSnapshotOnlyToCurrentRenderer() {
    MediaOverviewRepository repository = mock(MediaOverviewRepository.class);
    when(repository.load(anyLong(), anyLong(), any(), any())).thenReturn(new TaskHandle());
    MediaOverviewViewModel viewModel = new MediaOverviewViewModel(repository);
    AtomicReference<MediaOverviewRepository.Snapshot> delivered = new AtomicReference<>();

    viewModel.load(5L, 7L, mock(ConversationUnlockCapability.class), delivered::set);
    assertThat(viewModel.getState().getValue().getPhase())
        .isEqualTo(MediaOverviewUiState.Phase.LOADING);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<MediaOverviewRepository.Callback<MediaOverviewRepository.Snapshot>> callback =
        ArgumentCaptor.forClass(MediaOverviewRepository.Callback.class);
    verify(repository).load(anyLong(), anyLong(), any(), callback.capture());
    MediaOverviewRepository.Snapshot snapshot =
        new MediaOverviewRepository.Snapshot(null, List.of(mock(ImageRecord.class)));
    callback.getValue().onSuccess(snapshot);

    assertThat(delivered.get()).isSameAs(snapshot);
    assertThat(viewModel.getState().getValue().getPhase())
        .isEqualTo(MediaOverviewUiState.Phase.IDLE);
    assertThat(viewModel.getState().getValue().getAttachmentCount()).isEqualTo(1);
  }

  @Test
  public void saveSequencesCollectionAndWriteThenAcknowledgesResult() {
    MediaOverviewRepository repository = mock(MediaOverviewRepository.class);
    when(repository.collectAttachments(anyLong(), any(), any())).thenReturn(new TaskHandle());
    when(repository.saveAttachments(any(), any(), any())).thenReturn(new TaskHandle());
    MediaOverviewViewModel viewModel = new MediaOverviewViewModel(repository);
    viewModel.saveAll(5L, mock(ConversationUnlockCapability.class));
    assertThat(viewModel.getState().getValue().getPhase())
        .isEqualTo(MediaOverviewUiState.Phase.COLLECTING);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<MediaOverviewRepository.Callback<List<SaveAttachmentTask.Attachment>>> collect =
        ArgumentCaptor.forClass(MediaOverviewRepository.Callback.class);
    verify(repository).collectAttachments(anyLong(), any(), collect.capture());
    List<SaveAttachmentTask.Attachment> attachments = List.of(mock(SaveAttachmentTask.Attachment.class));
    collect.getValue().onSuccess(attachments);

    assertThat(viewModel.getState().getValue().getPhase())
        .isEqualTo(MediaOverviewUiState.Phase.SAVING);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<MediaOverviewRepository.Callback<Integer>> save =
        ArgumentCaptor.forClass(MediaOverviewRepository.Callback.class);
    verify(repository).saveAttachments(any(), any(), save.capture());
    save.getValue().onSuccess(SaveAttachmentTask.SUCCESS);

    assertThat(viewModel.getState().getValue().getSaveResult()).isEqualTo(SaveAttachmentTask.SUCCESS);
    viewModel.acknowledgeResult();
    assertThat(viewModel.getState().getValue().getSaveResult()).isNull();
  }

  @Test
  public void replacementCancelsPriorLoadAndSuppressesItsSnapshot() {
    MediaOverviewRepository repository = mock(MediaOverviewRepository.class);
    TaskHandle firstHandle = mock(TaskHandle.class);
    when(repository.load(anyLong(), anyLong(), any(), any())).thenReturn(firstHandle, new TaskHandle());
    MediaOverviewViewModel viewModel = new MediaOverviewViewModel(repository);
    AtomicReference<MediaOverviewRepository.Snapshot> delivered = new AtomicReference<>();

    viewModel.load(5L, 7L, mock(ConversationUnlockCapability.class), delivered::set);
    viewModel.load(6L, 8L, mock(ConversationUnlockCapability.class), delivered::set);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<MediaOverviewRepository.Callback<MediaOverviewRepository.Snapshot>> callback =
        ArgumentCaptor.forClass(MediaOverviewRepository.Callback.class);
    verify(repository, org.mockito.Mockito.times(2))
        .load(anyLong(), anyLong(), any(), callback.capture());
    callback.getAllValues().get(0).onSuccess(new MediaOverviewRepository.Snapshot(null, List.of()));

    verify(firstHandle).cancel();
    assertThat(delivered.get()).isNull();
    assertThat(viewModel.getState().getValue().getPhase())
        .isEqualTo(MediaOverviewUiState.Phase.LOADING);
  }

    @Test
    public void relockCancelsLoadClearsStateAndSuppressesLateSnapshot() {
        MediaOverviewRepository repository = mock(MediaOverviewRepository.class);
        TaskHandle handle = mock(TaskHandle.class);
        when(repository.load(anyLong(), anyLong(), any(), any())).thenReturn(handle);
        MediaOverviewViewModel viewModel = new MediaOverviewViewModel(repository);
        AtomicReference<MediaOverviewRepository.Snapshot> delivered = new AtomicReference<>();

        viewModel.load(5L, 7L, mock(ConversationUnlockCapability.class), delivered::set);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<MediaOverviewRepository.Callback<MediaOverviewRepository.Snapshot>> callback =
                ArgumentCaptor.forClass(MediaOverviewRepository.Callback.class);
        verify(repository).load(anyLong(), anyLong(), any(), callback.capture());
        viewModel.clearSensitiveState();
        callback.getValue().onSuccess(new MediaOverviewRepository.Snapshot(null, List.of()));

        verify(handle).cancel();
        assertThat(delivered.get()).isNull();
        assertThat(viewModel.getState().getValue().getPhase())
                .isEqualTo(MediaOverviewUiState.Phase.IDLE);
    }
}