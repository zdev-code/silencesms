package org.smssecure.smssecure;

import android.Manifest;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import org.smssecure.smssecure.components.ZoomingImageView;
import org.smssecure.smssecure.domain.conversation.ConversationUnlockCapability;
import org.smssecure.smssecure.domain.media.MediaPreviewDraftStore;
import org.smssecure.smssecure.domain.security.UnlockSession;
import org.smssecure.smssecure.mms.VideoSlide;
import org.smssecure.smssecure.permissions.Permissions;
import org.smssecure.smssecure.recipients.Recipient;
import org.smssecure.smssecure.ui.LifecycleStateCollector;
import org.smssecure.smssecure.ui.mediapreview.MediaPreviewUiState;
import org.smssecure.smssecure.ui.mediapreview.MediaPreviewViewModel;
import org.smssecure.smssecure.util.DateUtils;
import org.smssecure.smssecure.util.DynamicLanguage;
import org.smssecure.smssecure.util.SaveAttachmentTask;
import org.smssecure.smssecure.video.VideoPlayer;

import java.io.IOException;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public final class MediaPreviewFragment extends Fragment {
  private static final String TAG = MediaPreviewFragment.class.getSimpleName();
  static final String DRAFT_OWNER = "media-preview-draft";
  static final String MODE_ARGUMENT = "media_preview.mode";
  static final String PART_ROW_ID_ARGUMENT = "media_preview.part_row_id";
  static final String PART_UNIQUE_ID_ARGUMENT = "media_preview.part_unique_id";
  static final String MESSAGE_ID_ARGUMENT = "media_preview.message_id";
  static final String THREAD_ID_ARGUMENT = "media_preview.thread_id";
  static final String RECIPIENT_ID_ARGUMENT = "media_preview.recipient_id";
  static final String DATE_ARGUMENT = "media_preview.date";
  static final String SIZE_ARGUMENT = "media_preview.size";
  static final int MODE_PERSISTED = 1;
  static final int MODE_DRAFT = 2;

  @Inject MediaPreviewDraftStore draftStore;

  private final DynamicLanguage dynamicLanguage = new DynamicLanguage();
  private UnlockSession unlockSession;
  private ZoomingImageView image;
  private VideoPlayer video;
  private Uri mediaUri;
  private String mediaType;
  private Recipient recipient;
  private long threadId;
  private long date;
  private long size;
  private MediaPreviewViewModel viewModel;
  private AlertDialog saveProgressDialog;

  static Bundle persistedArguments(long partRowId, long partUniqueId, long messageId,
                                   long threadId, long recipientId, long date, long size) {
    Bundle arguments = new Bundle();
    arguments.putInt(MODE_ARGUMENT, MODE_PERSISTED);
    arguments.putLong(PART_ROW_ID_ARGUMENT, partRowId);
    arguments.putLong(PART_UNIQUE_ID_ARGUMENT, partUniqueId);
    arguments.putLong(MESSAGE_ID_ARGUMENT, messageId);
    arguments.putLong(THREAD_ID_ARGUMENT, threadId);
    if (recipientId > 0L) arguments.putLong(RECIPIENT_ID_ARGUMENT, recipientId);
    if (date > 0L) arguments.putLong(DATE_ARGUMENT, date);
    if (size > 0L) arguments.putLong(SIZE_ARGUMENT, size);
    requireValidArguments(arguments);
    return arguments;
  }

  static Bundle draftArguments(long size) {
    Bundle arguments = new Bundle();
    arguments.putInt(MODE_ARGUMENT, MODE_DRAFT);
    if (size > 0L) arguments.putLong(SIZE_ARGUMENT, size);
    requireValidArguments(arguments);
    return arguments;
  }

  static void requireValidArguments(@NonNull Bundle arguments) {
    Integer mode = androidx.core.os.BundleCompat.getSerializable(arguments, MODE_ARGUMENT, Integer.class);
    if (mode == null || (mode != MODE_PERSISTED && mode != MODE_DRAFT)) {
      throw new SecurityException("Invalid media preview mode");
    }
    for (String key : new String[] {PART_ROW_ID_ARGUMENT, PART_UNIQUE_ID_ARGUMENT,
        MESSAGE_ID_ARGUMENT, THREAD_ID_ARGUMENT, RECIPIENT_ID_ARGUMENT, DATE_ARGUMENT, SIZE_ARGUMENT}) {
      if (arguments.containsKey(key)) {
        Long value = androidx.core.os.BundleCompat.getSerializable(arguments, key, Long.class);
        if (value == null || value <= 0L) throw new SecurityException("Invalid media preview value");
      }
    }
    if (mode == MODE_PERSISTED &&
        (!arguments.containsKey(PART_ROW_ID_ARGUMENT) || !arguments.containsKey(PART_UNIQUE_ID_ARGUMENT) ||
         !arguments.containsKey(MESSAGE_ID_ARGUMENT) || !arguments.containsKey(THREAD_ID_ARGUMENT))) {
      throw new SecurityException("Persisted media preview requires positive IDs");
    }
    if (mode == MODE_DRAFT &&
      (arguments.containsKey(PART_ROW_ID_ARGUMENT) || arguments.containsKey(PART_UNIQUE_ID_ARGUMENT) ||
       arguments.containsKey(MESSAGE_ID_ARGUMENT) ||
         arguments.containsKey(THREAD_ID_ARGUMENT) || arguments.containsKey(RECIPIENT_ID_ARGUMENT))) {
      throw new SecurityException("Draft media preview cannot carry database IDs");
    }
  }

  @Override public void onCreate(@Nullable Bundle state) {
    super.onCreate(state);
    requireValidArguments(requireArguments());
    unlockSession = UnlockSession.capture();
    threadId = requireArguments().getLong(THREAD_ID_ARGUMENT, -1L);
    date = requireArguments().getLong(DATE_ARGUMENT, -1L);
    size = requireArguments().getLong(SIZE_ARGUMENT, 0L);
    viewModel = new ViewModelProvider(this).get(MediaPreviewViewModel.class);
  }

  @Override public @NonNull View onCreateView(@NonNull LayoutInflater inflater,
                                              @Nullable ViewGroup container,
                                              @Nullable Bundle state) {
    return inflater.inflate(R.layout.media_preview_activity, container, false);
  }

  @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
    super.onViewCreated(view, state);
    image = view.findViewById(R.id.image);
    video = view.findViewById(R.id.video_player);
    LifecycleStateCollector.collect(getViewLifecycleOwner(), viewModel.getState(), this::renderSaveState);
    if (requireArguments().getInt(MODE_ARGUMENT) == MODE_DRAFT) loadDraft();
    else loadPersisted();
    setFullscreen(true);
    androidx.appcompat.app.ActionBar actionBar =
        ((AppCompatActivity) requireActivity()).getSupportActionBar();
    if (actionBar != null) {
      actionBar.setDisplayShowCustomEnabled(false);
      actionBar.setDisplayShowTitleEnabled(true);
      actionBar.setDisplayHomeAsUpEnabled(true);
      actionBar.hide();
    }
  }

  private void loadDraft() {
    try {
      MediaPreviewDraftStore.Payload payload = draftStore.consume(DRAFT_OWNER);
      mediaUri = payload.getUri();
      mediaType = payload.getContentType();
      size = payload.getSize();
      initializeActionBar();
      initializeMedia();
    } catch (MediaPreviewDraftStore.InvalidPayloadException error) {
      closeUnsupported();
    }
  }

  private void loadPersisted() {
    Bundle arguments = requireArguments();
    viewModel.load(arguments.getLong(PART_ROW_ID_ARGUMENT), arguments.getLong(PART_UNIQUE_ID_ARGUMENT),
        arguments.getLong(MESSAGE_ID_ARGUMENT), threadId,
        arguments.getLong(RECIPIENT_ID_ARGUMENT, -1L),
        new ConversationUnlockCapability(unlockSession), snapshot -> {
          if (!isAdded() || image == null) return;
          recipient = snapshot.getRecipient();
          mediaUri = snapshot.getUri();
          mediaType = snapshot.getContentType();
          size = snapshot.getSize();
          initializeActionBar();
          initializeMedia();
        });
  }

  private void initializeMedia() {
    if (!isContentTypeSupported(mediaType)) {
      closeUnsupported();
      return;
    }
    try {
      unlockSession.use(masterSecret -> {
        if (mediaType.startsWith("image/")) {
          image.setVisibility(View.VISIBLE);
          video.setVisibility(View.GONE);
          image.setImageUri(masterSecret, mediaUri, mediaType);
        } else {
          image.setVisibility(View.GONE);
          video.setVisibility(View.VISIBLE);
          video.setVideoSource(masterSecret, new VideoSlide(requireContext(), mediaUri, size));
        }
        return null;
      });
    } catch (Exception error) {
      Log.w(TAG, "Unable to load media preview", error);
      closeUnsupported();
    }
  }

  private void closeUnsupported() {
    if (!isAdded()) return;
    Toast.makeText(requireContext().getApplicationContext(),
        R.string.MediaPreviewActivity_unssuported_media_type, Toast.LENGTH_LONG).show();
    ((MediaNavigationHost) requireActivity()).closeMediaPreview();
  }

  private void initializeActionBar() {
    androidx.appcompat.app.ActionBar actionBar =
        ((AppCompatActivity) requireActivity()).getSupportActionBar();
    if (actionBar == null) return;
    CharSequence relativeTime = date > 0
        ? DateUtils.getExtendedRelativeTimeSpanString(requireContext(),
            dynamicLanguage.getCurrentLocale(), date)
        : getString(R.string.MediaPreviewActivity_draft);
    actionBar.setTitle(recipient == null ? getString(R.string.MediaPreviewActivity_you)
                                         : recipient.toShortString());
    actionBar.setSubtitle(relativeTime);
  }

  void populateOptionsMenu(Menu menu) {
    menu.clear();
    new MenuInflater(requireContext()).inflate(R.menu.media_preview, menu);
    menu.findItem(R.id.media_preview__overview).setVisible(threadId > 0L);
  }

  boolean handleOptionsItem(MenuItem item) {
    if (item.getItemId() == android.R.id.home) {
      ((MediaNavigationHost) requireActivity()).closeMediaPreview();
      return true;
    }
    if (item.getItemId() == R.id.media_preview__overview) {
      long recipientId = requireArguments().getLong(RECIPIENT_ID_ARGUMENT, -1L);
      if (threadId > 0L && recipientId > 0L) {
        ((MediaNavigationHost) requireActivity()).openMediaOverview(threadId, recipientId);
      }
      return true;
    }
    if (item.getItemId() == R.id.media_preview__forward) {
      if (mediaUri != null) {
        Intent intent = new Intent(requireContext(), ShareActivity.class)
            .setAction(Intent.ACTION_SEND)
            .putExtra(Intent.EXTRA_STREAM, mediaUri).setType(mediaType);
        startActivity(intent);
      }
      return true;
    }
    if (item.getItemId() == R.id.save) {
      saveToDisk();
      return true;
    }
    return false;
  }

  private void saveToDisk() {
    if (mediaUri == null || mediaType == null) return;
    SaveAttachmentTask.showWarningDialog(requireContext(), (dialog, which) ->
        Permissions.with(requireActivity())
            .request(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE)
            .ifNecessary()
            .withPermanentDenialDialog(getString(R.string.MediaPreviewActivity_silence_needs_the_storage_permission_in_order_to_write_to_external_storage_but_it_has_been_permanently_denied))
            .onAnyDenied(() -> Toast.makeText(requireContext().getApplicationContext(),
                R.string.MediaPreviewActivity_unable_to_write_to_external_storage_without_permission,
                Toast.LENGTH_LONG).show())
            .onAllGranted(() -> {
              long saveDate = date > 0 ? date : System.currentTimeMillis();
              viewModel.save(new ConversationUnlockCapability(unlockSession),
                  new SaveAttachmentTask.Attachment(mediaUri, mediaType, saveDate));
            }).execute());
  }

  private void renderSaveState(MediaPreviewUiState state) {
    if (state.isSaving()) showSaveProgressDialog();
    else dismissSaveProgressDialog();
    if (state.getResult() != null && isAdded()) {
      SaveAttachmentTask.showResultToast(requireContext().getApplicationContext(), state.getResult(), 1);
      viewModel.acknowledgeResult();
    }
  }

  private void showSaveProgressDialog() {
    dismissSaveProgressDialog();
    saveProgressDialog = new AlertDialog.Builder(requireContext())
        .setTitle(getResources().getQuantityString(
            R.plurals.ConversationFragment_saving_n_attachments, 1, 1))
        .setMessage(getResources().getQuantityString(
            R.plurals.ConversationFragment_saving_n_attachments_to_sd_card, 1, 1))
        .setView(new ProgressBar(requireContext())).setCancelable(false).create();
    saveProgressDialog.show();
  }

  private void dismissSaveProgressDialog() {
    if (saveProgressDialog != null) saveProgressDialog.dismiss();
    saveProgressDialog = null;
  }

  private void setFullscreen(boolean fullscreen) {
    WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(
        requireActivity().getWindow(), requireActivity().getWindow().getDecorView());
    controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    if (fullscreen) controller.hide(WindowInsetsCompat.Type.systemBars());
    else controller.show(WindowInsetsCompat.Type.systemBars());
  }

  public void clearSensitiveState() {
    if (viewModel != null) viewModel.clearSensitiveState();
    dismissSaveProgressDialog();
    if (image != null) image.cleanup();
    if (video != null) video.cleanup();
    mediaUri = null;
    mediaType = null;
    recipient = null;
    draftStore.discard(DRAFT_OWNER);
  }

  @Override public void onDestroyView() {
    clearSensitiveState();
    setFullscreen(false);
    image = null;
    video = null;
    super.onDestroyView();
  }

  public static boolean isContentTypeSupported(@Nullable String contentType) {
    return contentType != null &&
        (contentType.startsWith("image/") || contentType.startsWith("video/"));
  }
}