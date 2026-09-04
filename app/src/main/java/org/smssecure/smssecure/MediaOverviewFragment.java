package org.smssecure.smssecure;

import android.content.DialogInterface;
import android.content.res.Configuration;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.smssecure.smssecure.domain.conversation.ConversationUnlockCapability;
import org.smssecure.smssecure.domain.security.UnlockSession;
import org.smssecure.smssecure.recipients.Recipient;
import org.smssecure.smssecure.ui.LifecycleStateCollector;
import org.smssecure.smssecure.ui.mediaoverview.MediaOverviewUiState;
import org.smssecure.smssecure.ui.mediaoverview.MediaOverviewViewModel;
import org.smssecure.smssecure.util.SaveAttachmentTask;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public final class MediaOverviewFragment extends Fragment {
  private static final String TAG = MediaOverviewFragment.class.getSimpleName();

  static final String THREAD_ID_ARGUMENT = "media_overview.thread_id";
  static final String RECIPIENT_ID_ARGUMENT = "media_overview.recipient_id";

  private UnlockSession unlockSession;
  private long threadId;
  private long recipientId;
  private RecyclerView gridView;
  private GridLayoutManager gridManager;
  private TextView noImages;
  private Recipient recipient;
  private MediaOverviewViewModel viewModel;
  private AlertDialog progressDialog;

  static Bundle arguments(long threadId, long recipientId) {
    Bundle arguments = new Bundle();
    arguments.putLong(THREAD_ID_ARGUMENT, threadId);
    arguments.putLong(RECIPIENT_ID_ARGUMENT, recipientId);
    requireValidArguments(arguments);
    return arguments;
  }

  static void requireValidArguments(@NonNull Bundle arguments) {
    Long threadId = androidx.core.os.BundleCompat.getSerializable(
        arguments, THREAD_ID_ARGUMENT, Long.class);
    Long recipientId = androidx.core.os.BundleCompat.getSerializable(
        arguments, RECIPIENT_ID_ARGUMENT, Long.class);
    if (threadId == null || threadId <= 0L || recipientId == null || recipientId <= 0L) {
      throw new SecurityException("Media overview requires positive thread and recipient IDs");
    }
  }

  @Override public void onCreate(@Nullable Bundle state) {
    super.onCreate(state);
    requireValidArguments(requireArguments());
    unlockSession = UnlockSession.capture();
    threadId = requireArguments().getLong(THREAD_ID_ARGUMENT);
    recipientId = requireArguments().getLong(RECIPIENT_ID_ARGUMENT);
    viewModel = new ViewModelProvider(this).get(MediaOverviewViewModel.class);
  }

  @Override public @NonNull View onCreateView(@NonNull LayoutInflater inflater,
                                              @Nullable ViewGroup container,
                                              @Nullable Bundle state) {
    return inflater.inflate(R.layout.media_overview_activity, container, false);
  }

  @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
    super.onViewCreated(view, state);
    noImages = view.findViewById(R.id.no_images);
    gridView = view.findViewById(R.id.media_grid);
    gridManager = new GridLayoutManager(requireContext(),
        getResources().getInteger(R.integer.media_overview_cols));
    gridView.setLayoutManager(gridManager);
    gridView.setHasFixedSize(true);
    LifecycleStateCollector.collect(getViewLifecycleOwner(), viewModel.getState(), this::renderState);
    viewModel.load(threadId, recipientId, new ConversationUnlockCapability(unlockSession), snapshot -> {
      if (!isAdded() || gridView == null) return;
      recipient = snapshot.getRecipient();
      gridView.setAdapter(new ImageMediaAdapter(requireContext(), unlockSession,
          snapshot.getRecords(), threadId, this::openPreview));
      noImages.setVisibility(snapshot.getRecords().isEmpty() ? View.VISIBLE : View.GONE);
      initializeActionBar();
      requireActivity().invalidateOptionsMenu();
    });
  }

  private void openPreview(long partRowId, long partUniqueId, long messageId, long threadId,
                           long recipientId, long date, long size) {
    ((MediaNavigationHost) requireActivity()).openMediaPreview(
        partRowId, partUniqueId, messageId, threadId, recipientId, date, size);
  }

  private void initializeActionBar() {
    androidx.appcompat.app.ActionBar actionBar =
        ((AppCompatActivity) requireActivity()).getSupportActionBar();
    if (actionBar == null) return;
    actionBar.setTitle(recipient == null
        ? getString(R.string.AndroidManifest__media_overview)
        : getString(R.string.AndroidManifest__media_overview_named, recipient.toShortString()));
  }

  @Override public void onConfigurationChanged(@NonNull Configuration configuration) {
    super.onConfigurationChanged(configuration);
    if (gridManager != null) {
      gridManager.setSpanCount(getResources().getInteger(R.integer.media_overview_cols));
    }
  }

  void populateOptionsMenu(Menu menu) {
    menu.clear();
    if (gridView != null && gridView.getAdapter() != null && gridView.getAdapter().getItemCount() > 0) {
      new MenuInflater(requireContext()).inflate(R.menu.media_overview, menu);
    }
  }

  boolean handleOptionsItem(MenuItem item) {
    if (item.getItemId() != R.id.save) return false;
    SaveAttachmentTask.showWarningDialog(requireContext(), new DialogInterface.OnClickListener() {
      @Override public void onClick(DialogInterface dialog, int which) {
        viewModel.saveAll(threadId, new ConversationUnlockCapability(unlockSession));
      }
    }, gridView == null || gridView.getAdapter() == null ? 0 : gridView.getAdapter().getItemCount());
    return true;
  }

  private void renderState(MediaOverviewUiState state) {
    if (state.getPhase() == MediaOverviewUiState.Phase.COLLECTING) {
      showProgressDialog(getString(R.string.ConversationFragment_collecting_attahments),
                         getString(R.string.please_wait));
    } else if (state.getPhase() == MediaOverviewUiState.Phase.SAVING) {
      int count = state.getAttachmentCount();
      showProgressDialog(
          getResources().getQuantityString(R.plurals.ConversationFragment_saving_n_attachments, count, count),
          getResources().getQuantityString(R.plurals.ConversationFragment_saving_n_attachments_to_sd_card, count, count));
    } else {
      dismissProgressDialog();
    }
    if (state.getSaveResult() != null && isAdded()) {
      if (state.getSaveResult() != SaveAttachmentTask.SUCCESS) {
        Log.w(TAG, "Unable to complete media operation, result: " + state.getSaveResult());
      }
      SaveAttachmentTask.showResultToast(requireContext().getApplicationContext(),
          state.getSaveResult(), state.getAttachmentCount());
      viewModel.acknowledgeResult();
    }
  }

  private void showProgressDialog(CharSequence title, CharSequence message) {
    dismissProgressDialog();
    progressDialog = new AlertDialog.Builder(requireContext())
        .setTitle(title).setMessage(message).setView(new ProgressBar(requireContext()))
        .setCancelable(false).create();
    progressDialog.show();
  }

  private void dismissProgressDialog() {
    if (progressDialog != null) progressDialog.dismiss();
    progressDialog = null;
  }

  public void clearSensitiveState() {
    viewModel.clearSensitiveState();
    dismissProgressDialog();
    recipient = null;
    if (gridView != null) {
      gridView.setAdapter(null);
      gridView.removeAllViews();
    }
    if (noImages != null) noImages.setText("");
  }

  @Override public void onDestroyView() {
    clearSensitiveState();
    gridView = null;
    gridManager = null;
    noImages = null;
    super.onDestroyView();
  }
}