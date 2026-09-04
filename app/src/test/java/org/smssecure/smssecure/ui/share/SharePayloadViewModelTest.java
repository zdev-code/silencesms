package org.smssecure.smssecure.ui.share;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.net.Uri;

import org.junit.Test;
import org.mockito.ArgumentCaptor;

import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.data.share.SharePayloadRepository;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor.TaskHandle;

import java.util.concurrent.atomic.AtomicReference;

public class SharePayloadViewModelTest {
  @Test
  public void deliversResolvedUriOnlyToCurrentSink() {
    SharePayloadRepository repository = mock(SharePayloadRepository.class);
    when(repository.resolve(any(), any(), any(), any())).thenReturn(new TaskHandle());
    SharePayloadViewModel viewModel = new SharePayloadViewModel(repository);
    AtomicReference<Uri> delivered = new AtomicReference<>();

    viewModel.resolve(mock(MasterSecret.class), mock(Uri.class), "image/png", delivered::set);
    assertThat(viewModel.getState().getValue().isLoading()).isTrue();

    ArgumentCaptor<SharePayloadRepository.Callback> callback =
        ArgumentCaptor.forClass(SharePayloadRepository.Callback.class);
    verify(repository).resolve(any(), any(), any(), callback.capture());
    Uri resolved = mock(Uri.class);
    callback.getValue().onSuccess(resolved);

    assertThat(delivered.get()).isSameAs(resolved);
    assertThat(viewModel.getState().getValue().isLoading()).isFalse();
  }

  @Test
  public void replacementCancelsPriorResolutionAndDeletesItsLateBlob() {
    SharePayloadRepository repository = mock(SharePayloadRepository.class);
    TaskHandle firstHandle = mock(TaskHandle.class);
    when(repository.resolve(any(), any(), any(), any())).thenReturn(firstHandle, new TaskHandle());
    SharePayloadViewModel viewModel = new SharePayloadViewModel(repository);

    viewModel.resolve(mock(MasterSecret.class), mock(Uri.class), "image/png", ignored -> {});
    viewModel.resolve(mock(MasterSecret.class), mock(Uri.class), "image/png", ignored -> {});

    ArgumentCaptor<SharePayloadRepository.Callback> callback =
        ArgumentCaptor.forClass(SharePayloadRepository.Callback.class);
    verify(repository, org.mockito.Mockito.times(2)).resolve(any(), any(), any(), callback.capture());
    Uri stale = mock(Uri.class);
    callback.getAllValues().get(0).onSuccess(stale);

    verify(firstHandle).cancel();
    verify(repository).delete(stale);
    assertThat(viewModel.getState().getValue().isLoading()).isTrue();
  }

  @Test
  public void discardDelegatesTemporaryBlobDeletion() {
    SharePayloadRepository repository = mock(SharePayloadRepository.class);
    SharePayloadViewModel viewModel = new SharePayloadViewModel(repository);
    Uri resolved = mock(Uri.class);

    viewModel.discard(resolved);

    verify(repository).delete(resolved);
  }
}