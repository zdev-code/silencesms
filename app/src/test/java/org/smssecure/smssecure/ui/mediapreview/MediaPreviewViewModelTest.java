package org.smssecure.smssecure.ui.mediapreview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.mockito.ArgumentCaptor;

import org.smssecure.smssecure.data.media.MediaOverviewRepository;
import org.smssecure.smssecure.domain.conversation.ConversationUnlockCapability;
import org.smssecure.smssecure.util.SaveAttachmentTask;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor.TaskHandle;

public class MediaPreviewViewModelTest {
  @Test
  public void savePublishesConsumableResult() {
    MediaOverviewRepository repository = mock(MediaOverviewRepository.class);
    when(repository.saveAttachments(any(), any(), any())).thenReturn(new TaskHandle());
    MediaPreviewViewModel viewModel = new MediaPreviewViewModel(repository);

    viewModel.save(mock(ConversationUnlockCapability.class), mock(SaveAttachmentTask.Attachment.class));
    assertThat(viewModel.getState().getValue().isSaving()).isTrue();

    @SuppressWarnings("unchecked")
    ArgumentCaptor<MediaOverviewRepository.Callback<Integer>> callback =
        ArgumentCaptor.forClass(MediaOverviewRepository.Callback.class);
    verify(repository).saveAttachments(any(), any(), callback.capture());
    callback.getValue().onSuccess(SaveAttachmentTask.SUCCESS);

    assertThat(viewModel.getState().getValue().getResult()).isEqualTo(SaveAttachmentTask.SUCCESS);
    viewModel.acknowledgeResult();
    assertThat(viewModel.getState().getValue().getResult()).isNull();
  }

  @Test
  public void replacementCancelsPriorSaveAndSuppressesLateResult() {
    MediaOverviewRepository repository = mock(MediaOverviewRepository.class);
    TaskHandle firstHandle = mock(TaskHandle.class);
    when(repository.saveAttachments(any(), any(), any())).thenReturn(firstHandle, new TaskHandle());
    MediaPreviewViewModel viewModel = new MediaPreviewViewModel(repository);

    viewModel.save(mock(ConversationUnlockCapability.class), mock(SaveAttachmentTask.Attachment.class));
    viewModel.save(mock(ConversationUnlockCapability.class), mock(SaveAttachmentTask.Attachment.class));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<MediaOverviewRepository.Callback<Integer>> callback =
        ArgumentCaptor.forClass(MediaOverviewRepository.Callback.class);
    verify(repository, org.mockito.Mockito.times(2)).saveAttachments(any(), any(), callback.capture());
    callback.getAllValues().get(0).onFailure(new IllegalStateException("late"));

    verify(firstHandle).cancel();
    assertThat(viewModel.getState().getValue().isSaving()).isTrue();
    assertThat(viewModel.getState().getValue().getResult()).isNull();
  }

  @Test
  public void relockCancelsSaveClearsStateAndSuppressesLateResult() {
    MediaOverviewRepository repository = mock(MediaOverviewRepository.class);
    TaskHandle handle = mock(TaskHandle.class);
    when(repository.saveAttachments(any(), any(), any())).thenReturn(handle);
    MediaPreviewViewModel viewModel = new MediaPreviewViewModel(repository);

    viewModel.save(mock(ConversationUnlockCapability.class), mock(SaveAttachmentTask.Attachment.class));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<MediaOverviewRepository.Callback<Integer>> callback =
        ArgumentCaptor.forClass(MediaOverviewRepository.Callback.class);
    verify(repository).saveAttachments(any(), any(), callback.capture());
    viewModel.clearSensitiveState();
    callback.getValue().onSuccess(SaveAttachmentTask.SUCCESS);

    verify(handle).cancel();
    assertThat(viewModel.getState().getValue().isSaving()).isFalse();
    assertThat(viewModel.getState().getValue().getResult()).isNull();
  }
}