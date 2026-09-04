/**
 * Copyright (C) 2015 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.smssecure.smssecure;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Build;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;
import androidx.fragment.app.Fragment;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ActionMode;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.ItemTouchHelper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.ProgressBar;

import org.smssecure.smssecure.ConversationListModelAdapter.ItemClickListener;
import org.smssecure.smssecure.components.reminder.DefaultSmsReminder;
import org.smssecure.smssecure.components.reminder.DeliveryReportsReminder;
import org.smssecure.smssecure.components.reminder.ReminderView;
import org.smssecure.smssecure.components.reminder.StoreRatingReminder;
import org.smssecure.smssecure.components.reminder.SystemSmsImportReminder;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.domain.security.UnlockSession;
import org.smssecure.smssecure.domain.conversation.ConversationUnlockCapability;
import org.smssecure.smssecure.domain.conversation.ConversationListReminderPolicy;
import org.smssecure.smssecure.ui.conversationlist.ConversationListUiState;
import org.smssecure.smssecure.ui.conversationlist.ConversationListViewModel;
import org.smssecure.smssecure.ui.LifecycleStateCollector;
import org.smssecure.smssecure.recipients.Recipients;
import org.smssecure.smssecure.util.ViewUtil;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class ConversationListFragment extends Fragment
  implements ActionMode.Callback, ItemClickListener
{

  private static final String TAG = ConversationListFragment.class.getSimpleName();

  private final ActivityResultLauncher<Intent> defaultSmsRoleRequest = registerForActivityResult(
      new ActivityResultContracts.StartActivityForResult(), result -> initializeReminders());

  public static final String ARCHIVE = "archive";

  private UnlockSession        unlockSession;
  private ActionMode           actionMode;
  private RecyclerView         list;
  private ReminderView         reminderView;
  private FloatingActionButton fab;
  private Locale               locale;
  private boolean              archive;
  private ConversationListViewModel viewModel;
  private ConversationListUiState.Mutation renderedMutation = ConversationListUiState.Mutation.NONE;
  private ConversationListReminderPolicy.Kind renderedReminder = ConversationListReminderPolicy.Kind.NONE;
  private ConversationListUiState.Error renderedError = ConversationListUiState.Error.NONE;
  private @Nullable AlertDialog progressDialog;
  private boolean              viewDestroyed = true;

  @Override
  public void onCreate(Bundle icicle) {
    super.onCreate(icicle);
    unlockSession = UnlockSession.capture();
    locale       = androidx.core.os.BundleCompat.getSerializable(getArguments(), PassphraseRequiredActionBarActivity.LOCALE_EXTRA, Locale.class);
    archive      = getArguments().getBoolean(ARCHIVE, false);
  }

  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle bundle) {
    final View view = inflater.inflate(R.layout.conversation_list_fragment, container, false);
    viewDestroyed = false;
    renderedMutation = ConversationListUiState.Mutation.NONE;
    renderedReminder = ConversationListReminderPolicy.Kind.NONE;
    renderedError = ConversationListUiState.Error.NONE;
    reminderView = ViewUtil.findById(view, R.id.reminder);
    list         = ViewUtil.findById(view, R.id.list);
    fab          = ViewUtil.findById(view, R.id.fab);

    if (archive) fab.setVisibility(View.GONE);
    else         fab.setVisibility(View.VISIBLE);

    list.setHasFixedSize(true);
    list.setLayoutManager(new LinearLayoutManager(getActivity()));

    new ItemTouchHelper(new ArchiveListenerCallback()).attachToRecyclerView(list);

    return view;
  }

  @Override
  public void onDestroyView() {
    viewDestroyed = true;
    list.setAdapter(null);
    dismissProgressDialog();
    super.onDestroyView();
  }

  private boolean isViewActive() {
    return isAdded() && !viewDestroyed && getView() != null &&
           getViewLifecycleOwner().getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.INITIALIZED);
  }

  private void showProgressDialog(int title, int message) {
    if (!isViewActive()) return;

    dismissProgressDialog();
    Context context = requireContext();
    progressDialog = new AlertDialog.Builder(context)
        .setTitle(title)
        .setMessage(message)
        .setView(new ProgressBar(context))
        .setCancelable(false)
        .create();
    progressDialog.show();
  }

  private void dismissProgressDialog() {
    if (progressDialog != null) progressDialog.dismiss();
    progressDialog = null;
  }

  @Override
  public void onViewCreated(@NonNull View view, Bundle bundle) {
    super.onViewCreated(view, bundle);

    fab.setOnClickListener(new OnClickListener() {
      @Override
      public void onClick(View v) {
        ((ConversationSelectedListener) requireActivity()).onCreateNewConversation();
      }
    });
    initializeConversationList();
  }

  @Override
  public void onResume() {
    super.onResume();

    initializeReminders();
    list.getAdapter().notifyDataSetChanged();
  }

  public ConversationListModelAdapter getListAdapter() {
    return (ConversationListModelAdapter) list.getAdapter();
  }

  public ConversationListViewModel getViewModel() {
    return viewModel;
  }

  void clearSensitiveState() {
    if (list != null) list.setAdapter(null);
    if (reminderView != null) reminderView.hide();
    dismissProgressDialog();
  }

  public void setQueryFilter(String query) {
    viewModel.setFilter(query);
  }

  public void resetQueryFilter() {
    viewModel.setFilter("");
  }

  private void initializeReminders() {
    if (viewModel != null) viewModel.refreshReminder();
  }

  private void initializeConversationList() {
    list.setAdapter(new ConversationListModelAdapter(requireContext(), unlockSession, locale, this));
    viewModel = new ViewModelProvider(this).get(ConversationListViewModel.class);
    LifecycleStateCollector.collect(getViewLifecycleOwner(), viewModel.getState(), this::render);
  }

  private void render(ConversationListUiState state) {
    getListAdapter().submitList(state.getEntries(), archive ? 0 : state.getArchivedCount(),
                                state.getSelectedThreadIds());
    if (actionMode != null) {
      int count = state.getSelectedThreadIds().size();
      if (count == 0) {
        actionMode.finish();
      } else {
        actionMode.setSubtitle(getString(R.string.conversation_fragment_cab__batch_selection_amount,
                                         String.valueOf(count)));
      }
    }
    ConversationListUiState.Mutation mutation = state.getActiveMutation();
    if (mutation != renderedMutation && mutation == ConversationListUiState.Mutation.SEND_DRAFTS) {
      showProgressDialog(R.string.ConversationListFragment_sending,
                         R.string.ConversationListFragment_sending_selected_drafts);
    } else if (mutation != renderedMutation && mutation == ConversationListUiState.Mutation.DELETE) {
      showProgressDialog(R.string.ConversationListFragment_deleting,
                         R.string.ConversationListFragment_deleting_selected_conversations);
    } else if (mutation != renderedMutation &&
               (renderedMutation == ConversationListUiState.Mutation.SEND_DRAFTS ||
                renderedMutation == ConversationListUiState.Mutation.DELETE)) {
      dismissProgressDialog();
    }
    renderedMutation = mutation;
    renderReminder(state.getReminderKind());
    renderError(state.getError());
  }

  private void renderError(ConversationListUiState.Error error) {
    if (error == ConversationListUiState.Error.NONE) {
      renderedError = error;
      return;
    }
    if (renderedError == error) return;
    int message;
    switch (error) {
      case LOAD_FAILED: message = R.string.ConversationListFragment_load_failed; break;
      case DELETE_FAILED: message = R.string.ConversationListFragment_delete_failed; break;
      case SEND_DRAFTS_FAILED: message = R.string.ConversationListFragment_send_drafts_failed; break;
      case LOCKED: message = R.string.ConversationListFragment_operation_locked; break;
      case MUTATION_FAILED: message = R.string.ConversationListFragment_mutation_failed; break;
      default: return;
    }
    renderedError = error;
    Snackbar.make(requireView(), message, Snackbar.LENGTH_LONG).show();
    viewModel.acknowledgeError();
  }

  private void renderReminder(ConversationListReminderPolicy.Kind reminderKind) {
    if (renderedReminder == reminderKind) return;
    reminderView.hide();
    Context context = requireContext();
    switch (reminderKind) {
      case DEFAULT_SMS:
        reminderView.showReminder(new DefaultSmsReminder(context, defaultSmsRoleRequest));
        break;
      case SYSTEM_SMS_IMPORT:
        try {
          unlockSession.use(masterSecret -> {
            reminderView.showReminder(new SystemSmsImportReminder(context));
            return null;
          });
        } catch (Exception exception) {
          reminderView.hide();
        }
        break;
      case DELIVERY_REPORTS:
        reminderView.showReminder(new DeliveryReportsReminder(context));
        break;
      case STORE_RATING:
        reminderView.showReminder(new StoreRatingReminder(context));
        break;
      case NONE:
        break;
    }
    renderedReminder = reminderKind;
  }

  private void handleArchiveAllSelected() {
    final Set<Long> selectedConversations = new HashSet<>(getListAdapter().getBatchSelections());
    final boolean   archive               = this.archive;
    int snackBarTitleId;

    if (archive) snackBarTitleId = R.plurals.ConversationListFragment_moved_conversations_to_inbox;
    else         snackBarTitleId = R.plurals.ConversationListFragment_conversations_archived;

    int count            = selectedConversations.size();
    String snackBarTitle = getResources().getQuantityString(snackBarTitleId, count, count);

    Snackbar.make(requireView(), snackBarTitle, Snackbar.LENGTH_LONG)
        .setAction(R.string.ConversationListFragment_undo, view ->
          viewModel.undoLastArchiveMutation(new ConversationUnlockCapability(unlockSession)))
        .setActionTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.amber_500))
        .show();
    if (archive) viewModel.unarchiveSelected();
    else         viewModel.archiveSelected();
  }

  private void handleDeleteAllSelected() {
    int                 conversationsCount = getListAdapter().getBatchSelections().size();
    AlertDialog.Builder alert              = new AlertDialog.Builder(getActivity());
    alert.setIconAttribute(R.attr.dialog_alert_icon);
    alert.setTitle(getActivity().getResources().getQuantityString(R.plurals.ConversationListFragment_delete_selected_conversations,
                                                                  conversationsCount, conversationsCount));
    alert.setMessage(getActivity().getResources().getQuantityString(R.plurals.ConversationListFragment_this_will_permanently_delete_all_n_selected_conversations,
                                                                    conversationsCount, conversationsCount));
    alert.setCancelable(true);

    alert.setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
      @Override
      public void onClick(DialogInterface dialog, int which) {
        viewModel.deleteSelected(new ConversationUnlockCapability(unlockSession));
      }
    });

    alert.setNegativeButton(android.R.string.cancel, null);
    alert.show();
  }

  private void handleSelectAllThreads() {
    viewModel.selectAll();
  }

  private void handleCreateConversation(long threadId, Recipients recipients, int distributionType, long lastSeen) {
    ((ConversationSelectedListener)getActivity()).onCreateConversation(threadId, recipients, distributionType, lastSeen);
  }

  private void handleSendDrafts() {
    AlertDialog.Builder alert = new AlertDialog.Builder(getActivity());
    alert.setIconAttribute(R.attr.dialog_alert_icon);
    alert.setTitle(getString(R.string.ConversationListFragment_send_drafts));
    alert.setMessage(getString(R.string.ConversationListFragment_this_will_send_drafts_of_selected_conversations));
    alert.setCancelable(true);

    alert.setPositiveButton(R.string.ConversationListFragment_send, new DialogInterface.OnClickListener() {
      @Override
      public void onClick(DialogInterface dialog, int which) {
        viewModel.sendSelectedDrafts(new ConversationUnlockCapability(unlockSession));
      }
    });

    alert.setNegativeButton(android.R.string.cancel, null);
    alert.show();
  }

  @Override
  public void onItemClick(ConversationListItem item) {
    if (actionMode == null) {
      handleCreateConversation(item.getThreadId(), item.getRecipients(),
                               item.getDistributionType(), item.getLastSeen());
    } else {
      viewModel.toggleSelection(item.getThreadId());
    }
  }

  @Override
  public void onItemLongClick(ConversationListItem item) {
    actionMode = ((AppCompatActivity)getActivity()).startSupportActionMode(ConversationListFragment.this);

    getListAdapter().initializeBatchMode(true);
    viewModel.toggleSelection(item.getThreadId());
  }

  @Override
  public void onSwitchToArchive() {
    ((ConversationSelectedListener)getActivity()).onSwitchToArchive();
  }

  public interface ConversationSelectedListener {
    void onCreateConversation(long threadId, Recipients recipients, int distributionType, long lastSeen);
    void onCreateNewConversation();
    void onSwitchToArchive();
  }

  @Override
  public boolean onCreateActionMode(ActionMode mode, Menu menu) {
    MenuInflater inflater = getActivity().getMenuInflater();

    if (archive) inflater.inflate(R.menu.conversation_list_batch_unarchive, menu);
    else         inflater.inflate(R.menu.conversation_list_batch_archive, menu);

    inflater.inflate(R.menu.conversation_list_batch, menu);
    inflater.inflate(R.menu.conversation_send_drafts, menu);

    mode.setTitle(R.string.conversation_fragment_cab__batch_selection_mode);
    mode.setSubtitle(null);

    ((BaseActionBarActivity) requireActivity()).setSystemBarColors(
        androidx.core.content.ContextCompat.getColor(requireContext(), R.color.action_mode_status_bar),
        androidx.core.content.ContextCompat.getColor(requireContext(), android.R.color.black));

    return true;
  }

  @Override
  public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
    return false;
  }

  @Override
  @SuppressLint("NonConstantResourceId")
  public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
    int itemId = item.getItemId();
    if      (itemId == R.id.menu_select_all)       { handleSelectAllThreads();   return true; }
    else if (itemId == R.id.menu_delete_selected)  { handleDeleteAllSelected();  return true; }
    else if (itemId == R.id.menu_archive_selected) { handleArchiveAllSelected(); return true; }
    else if (itemId == R.id.menu_send_drafts)      { handleSendDrafts();         return true; }

    return false;
  }

  @Override
  public void onDestroyActionMode(ActionMode mode) {
    getListAdapter().initializeBatchMode(false);
    viewModel.clearSelection();

    ((BaseActionBarActivity) requireActivity()).resetSystemBarColors();

    actionMode = null;
  }

  private class ArchiveListenerCallback extends ItemTouchHelper.SimpleCallback {

    public ArchiveListenerCallback() {
      super(0, ItemTouchHelper.RIGHT);
    }

    @Override
    public boolean onMove(RecyclerView recyclerView,
                          RecyclerView.ViewHolder viewHolder,
                          RecyclerView.ViewHolder target)
    {
      return false;
    }

    @Override
    public int getSwipeDirs(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder) {
      if (viewHolder.itemView instanceof ConversationListItemAction) {
        return 0;
      }

      if (actionMode != null) {
        return 0;
      }

      return super.getSwipeDirs(recyclerView, viewHolder);
    }

    @Override
    public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {
      final long    threadId = ((ConversationListItem)viewHolder.itemView).getThreadId();
      final boolean read     = ((ConversationListItem)viewHolder.itemView).getRead();
      int title = archive ? R.plurals.ConversationListFragment_moved_conversations_to_inbox
                          : R.plurals.ConversationListFragment_conversations_archived;
      viewModel.archiveFromSwipe(threadId, read, new ConversationUnlockCapability(unlockSession));
      Snackbar.make(requireView(), getResources().getQuantityString(title, 1, 1), Snackbar.LENGTH_LONG)
          .setAction(R.string.ConversationListFragment_undo, view ->
              viewModel.undoLastArchiveMutation(new ConversationUnlockCapability(unlockSession)))
          .setActionTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.amber_500))
          .show();
    }

    @Override
    public void onChildDraw(Canvas c, RecyclerView recyclerView,
                            RecyclerView.ViewHolder viewHolder,
                            float dX, float dY, int actionState,
                            boolean isCurrentlyActive)
    {

      if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
        View  itemView = viewHolder.itemView;
        Paint p        = new Paint();

        if (dX > 0) {
          Bitmap icon;

          if (archive) icon = BitmapFactory.decodeResource(getResources(), R.drawable.ic_unarchive_white_36dp);
          else         icon = BitmapFactory.decodeResource(getResources(), R.drawable.ic_archive_white_36dp);

          p.setColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.green_500));

          c.drawRect((float) itemView.getLeft(), (float) itemView.getTop(), dX,
                     (float) itemView.getBottom(), p);

          c.drawBitmap(icon,
                       (float) itemView.getLeft() + getResources().getDimension(R.dimen.conversation_list_fragment_archive_padding),
                       (float) itemView.getTop() + ((float) itemView.getBottom() - (float) itemView.getTop() - icon.getHeight())/2,
                       p);
        }

        if (Build.VERSION.SDK_INT >= 11) {
          float alpha = 1.0f - Math.abs(dX) / (float) viewHolder.itemView.getWidth();
          viewHolder.itemView.setAlpha(alpha);
          viewHolder.itemView.setTranslationX(dX);
        }

      } else {
        super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);
      }
    }
  }

}
