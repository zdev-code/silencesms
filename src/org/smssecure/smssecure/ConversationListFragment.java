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
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.lifecycle.Lifecycle;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;
import androidx.fragment.app.Fragment;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.Loader;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ActionMode;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.ItemTouchHelper;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ProgressBar;

import org.smssecure.smssecure.attachments.Attachment;
import org.smssecure.smssecure.attachments.UriAttachment;
import org.smssecure.smssecure.ConversationListAdapter.ItemClickListener;
import org.smssecure.smssecure.components.reminder.DefaultSmsReminder;
import org.smssecure.smssecure.components.reminder.DeliveryReportsReminder;
import org.smssecure.smssecure.components.reminder.Reminder;
import org.smssecure.smssecure.components.reminder.ReminderView;
import org.smssecure.smssecure.components.reminder.StoreRatingReminder;
import org.smssecure.smssecure.components.reminder.SystemSmsImportReminder;
import org.smssecure.smssecure.crypto.MasterCipher;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.crypto.SessionUtil;
import org.smssecure.smssecure.database.AttachmentDatabase;
import org.smssecure.smssecure.database.DatabaseFactory;
import org.smssecure.smssecure.database.DraftDatabase;
import org.smssecure.smssecure.database.ThreadDatabase;
import org.smssecure.smssecure.database.loaders.ConversationListLoader;
import org.smssecure.smssecure.notifications.MessageNotifier;
import org.smssecure.smssecure.mms.OutgoingMediaMessage;
import org.smssecure.smssecure.mms.OutgoingSecureMediaMessage;
import org.smssecure.smssecure.recipients.Recipients;
import org.smssecure.smssecure.sms.MessageSender;
import org.smssecure.smssecure.sms.OutgoingEncryptedMessage;
import org.smssecure.smssecure.sms.OutgoingTextMessage;
import org.smssecure.smssecure.util.dualsim.SubscriptionManagerCompat;
import org.smssecure.smssecure.util.Util;
import org.smssecure.smssecure.util.ViewUtil;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor.TaskHandle;
import org.smssecure.smssecure.util.task.SnackbarAsyncTask;
import java.util.Optional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class ConversationListFragment extends Fragment
  implements LoaderManager.LoaderCallbacks<Cursor>, ActionMode.Callback, ItemClickListener
{

  private static final String TAG = ConversationListFragment.class.getSimpleName();

  private final ActivityResultLauncher<Intent> defaultSmsRoleRequest = registerForActivityResult(
      new ActivityResultContracts.StartActivityForResult(), result -> initializeReminders());

  public static final String ARCHIVE = "archive";

  private MasterSecret         masterSecret;
  private ActionMode           actionMode;
  private RecyclerView         list;
  private ReminderView         reminderView;
  private FloatingActionButton fab;
  private Locale               locale;
  private String               queryFilter  = "";
  private boolean              archive;
  private final List<TaskHandle> callbackTasks = new ArrayList<>();
  private @Nullable AlertDialog progressDialog;
  private boolean              viewDestroyed = true;

  @Override
  public void onCreate(Bundle icicle) {
    super.onCreate(icicle);
    masterSecret = androidx.core.os.BundleCompat.getParcelable(getArguments(), "master_secret", MasterSecret.class);
    locale       = androidx.core.os.BundleCompat.getSerializable(getArguments(), PassphraseRequiredActionBarActivity.LOCALE_EXTRA, Locale.class);
    archive      = getArguments().getBoolean(ARCHIVE, false);
  }

  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle bundle) {
    final View view = inflater.inflate(R.layout.conversation_list_fragment, container, false);
    viewDestroyed = false;
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
    for (TaskHandle task : callbackTasks) task.cancel();
    callbackTasks.clear();
    dismissProgressDialog();
    super.onDestroyView();
  }

  private void trackCallbackTask(TaskHandle task) {
    if (viewDestroyed) task.cancel();
    else               callbackTasks.add(task);
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
        startActivity(new Intent(getActivity(), NewConversationActivity.class));
      }
    });
    initializeListAdapter();
  }

  @Override
  public void onResume() {
    super.onResume();

    initializeReminders();
    list.getAdapter().notifyDataSetChanged();
  }

  public ConversationListAdapter getListAdapter() {
    return (ConversationListAdapter) list.getAdapter();
  }

  public void setQueryFilter(String query) {
    this.queryFilter = query;
    LoaderManager.getInstance(this).restartLoader(0, null, this);
  }

  public void resetQueryFilter() {
    if (!TextUtils.isEmpty(this.queryFilter)) {
      setQueryFilter("");
    }
  }

  private void initializeReminders() {
    reminderView.hide();
    final Context      context              = requireContext().getApplicationContext();
    final MasterSecret reminderMasterSecret = masterSecret;

    trackCallbackTask(AppTaskExecutor.getInstance().<Optional<? extends Reminder>>submitSerial(
      () -> {
        if (DefaultSmsReminder.isEligible(context)) {
          return Optional.of(new DefaultSmsReminder(context, defaultSmsRoleRequest));
        } else if (Util.isDefaultSmsProvider(context) && SystemSmsImportReminder.isEligible(context)) {
          return Optional.of((new SystemSmsImportReminder(context, reminderMasterSecret)));
        } else if (DeliveryReportsReminder.isEligible(context)) {
          return Optional.of((new DeliveryReportsReminder(context)));
        } else if (StoreRatingReminder.isEligible(context)) {
          return Optional.of((new StoreRatingReminder(context)));
        } else {
          return Optional.empty();
        }
      },
      reminder -> {
        if (reminder.isPresent() && isViewActive() && !isRemoving()) {
          reminderView.showReminder(reminder.get());
        }
      },
      exception -> Log.w(TAG, "Unable to initialize reminders", exception)));
  }

  private void initializeListAdapter() {
    list.setAdapter(new ConversationListAdapter(getActivity(), masterSecret, locale, null, this));
    LoaderManager.getInstance(this).restartLoader(0, null, this);
  }

  private void handleArchiveAllSelected() {
    final Set<Long> selectedConversations = new HashSet<>(getListAdapter().getBatchSelections());
    final boolean   archive               = this.archive;
    final Context   context               = requireContext().getApplicationContext();

    int snackBarTitleId;

    if (archive) snackBarTitleId = R.plurals.ConversationListFragment_moved_conversations_to_inbox;
    else         snackBarTitleId = R.plurals.ConversationListFragment_conversations_archived;

    int count            = selectedConversations.size();
    String snackBarTitle = getResources().getQuantityString(snackBarTitleId, count, count);

    new SnackbarAsyncTask<Void>(getView(), snackBarTitle,
                                getString(R.string.ConversationListFragment_undo),
                                androidx.core.content.ContextCompat.getColor(requireContext(), R.color.amber_500),
                                Snackbar.LENGTH_LONG, true)
    {

      @Override
      protected void onPostExecute(Void result) {
        super.onPostExecute(result);

        if (isViewActive() && actionMode != null) {
          actionMode.finish();
          actionMode = null;
        }
      }

      @Override
      protected void executeAction(@Nullable Void parameter) {
        for (long threadId : selectedConversations) {
          if (!archive) DatabaseFactory.getThreadDatabase(context).archiveConversation(threadId);
          else          DatabaseFactory.getThreadDatabase(context).unarchiveConversation(threadId);
        }
      }

      @Override
      protected void reverseAction(@Nullable Void parameter) {
        for (long threadId : selectedConversations) {
          if (!archive) DatabaseFactory.getThreadDatabase(context).unarchiveConversation(threadId);
          else          DatabaseFactory.getThreadDatabase(context).archiveConversation(threadId);
        }
      }
    }.execute();
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
        final Set<Long>    selectedConversations = new HashSet<>(getListAdapter().getBatchSelections());
        final Context      context               = requireContext().getApplicationContext();
        final MasterSecret selectedMasterSecret  = masterSecret;

        if (!selectedConversations.isEmpty()) {
          showProgressDialog(R.string.ConversationListFragment_deleting,
                             R.string.ConversationListFragment_deleting_selected_conversations);
          trackCallbackTask(AppTaskExecutor.getInstance().submitSerial(
            () -> {
              DatabaseFactory.getThreadDatabase(context).deleteConversations(selectedConversations);
              MessageNotifier.updateNotification(context, selectedMasterSecret);
              return null;
            },
            ignored -> {
              dismissProgressDialog();
              if (isViewActive() && actionMode != null) {
                actionMode.finish();
                actionMode = null;
              }
            },
            exception -> {
              dismissProgressDialog();
              Log.w(TAG, "Unable to delete selected conversations", exception);
            }
          ));
        }
      }
    });

    alert.setNegativeButton(android.R.string.cancel, null);
    alert.show();
  }

  private void handleSelectAllThreads() {
    getListAdapter().selectAllThreads();
    int selectedCount = getListAdapter().getBatchSelections().size();
    actionMode.setSubtitle(getString(R.string.conversation_fragment_cab__batch_selection_amount,
                                     String.valueOf(selectedCount)));
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
        final List<Long> selectedThreadIds = new ArrayList<>(getListAdapter().getBatchSelections());
        final Context context = requireContext().getApplicationContext();
        final MasterSecret selectedMasterSecret = masterSecret;
        final Map<Long, Recipients> selectedRecipients = new LinkedHashMap<>();
        for (long threadId : selectedThreadIds) {
          selectedRecipients.put(threadId, getListAdapter().getRecipientsFromThreadId(threadId));
        }

        if (!selectedThreadIds.isEmpty() && selectedMasterSecret != null) {
          final MasterCipher masterCipher = new MasterCipher(selectedMasterSecret);

          showProgressDialog(R.string.ConversationListFragment_sending,
                             R.string.ConversationListFragment_sending_selected_drafts);
          trackCallbackTask(AppTaskExecutor.getInstance().submitSerial(
            () -> {
              DraftDatabase draftDatabase = DatabaseFactory.getDraftDatabase(context);
              Map<Long, List<DraftDatabase.Draft>> selectedDrafts = new LinkedHashMap<>();

              for (long threadId : selectedThreadIds) {
                selectedDrafts.put(threadId, new ArrayList<>(draftDatabase.getDrafts(masterCipher, threadId)));
              }

              for (long threadId : selectedThreadIds) {
                List<DraftDatabase.Draft> drafts = selectedDrafts.get(threadId);
                Recipients recipients = selectedRecipients.get(threadId);

                if (recipients != null) {
                  int subscriptionId = SubscriptionManagerCompat.getDefaultMessagingSubscriptionId().orElse(-1);
                  boolean isSingleConversation = recipients.isSingleRecipient() && !recipients.isGroupRecipient();
                  boolean isSecureDestination  = isSingleConversation && SessionUtil.hasSession(context, selectedMasterSecret, recipients.getPrimaryRecipient().getNumber(), subscriptionId);

                  Log.w(TAG, "Number of drafts: " + drafts.size());
                  if (drafts.size() > 1 && !drafts.get(1).getType().equals(DraftDatabase.Draft.TEXT)) {
                    sendMediaDraft(context, selectedMasterSecret, recipients, isSecureDestination,
                                   drafts.get(1), threadId, drafts.get(0).getValue());
                  } else {
                    for (DraftDatabase.Draft draft : drafts) {
                      Log.w(TAG, "getType(): " + draft.getType());
                      if (draft.getType().equals(DraftDatabase.Draft.TEXT)) {
                        sendTextDraft(context, selectedMasterSecret, recipients, isSecureDestination,
                                      draft, threadId);
                      } else {
                        sendMediaDraft(context, selectedMasterSecret, recipients, isSecureDestination,
                                       draft, threadId, null);
                      }
                    }
                  }
                } else {
                  Log.w(TAG, "null recipients when sending drafts ?!");
                }
                draftDatabase.clearDrafts(threadId);
              }
              return null;
            },
            ignored -> {
              dismissProgressDialog();
              if (isViewActive() && actionMode != null) {
                actionMode.finish();
                actionMode = null;
              }
            },
            exception -> {
              dismissProgressDialog();
              Log.w(TAG, "Unable to send selected drafts", exception);
            }
          ));
        }
      }
    });

    alert.setNegativeButton(android.R.string.cancel, null);
    alert.show();
  }

  private void sendTextDraft(Context context, MasterSecret selectedMasterSecret,
                             Recipients recipients, boolean isSecureDestination,
                             DraftDatabase.Draft draft, long threadId)
  {
    OutgoingTextMessage message;
    if (isSecureDestination) {
      message = new OutgoingEncryptedMessage(recipients, draft.getValue(), -1);
    } else {
      message = new OutgoingTextMessage(recipients, draft.getValue(), -1);
    }
    MessageSender.send(context, selectedMasterSecret, message, threadId, false);
  }

  private void sendMediaDraft(Context context, MasterSecret selectedMasterSecret,
                              Recipients recipients, boolean isSecureDestination,
                              DraftDatabase.Draft draft, long threadId, @Nullable String forcedValue)
  {
    List<Attachment> attachment = new LinkedList<Attachment>();
    attachment.add(new UriAttachment(Uri.parse(draft.getValue()), draft.getType() + "/*", AttachmentDatabase.TRANSFER_PROGRESS_DONE));

    OutgoingMediaMessage message = new OutgoingMediaMessage(recipients,
                                                            forcedValue != null ? forcedValue : "",
                                                            attachment,
                                                            System.currentTimeMillis(),
                                                            -1,
                                                            ThreadDatabase.DistributionTypes.BROADCAST);

    if (isSecureDestination) {
      message = new OutgoingSecureMediaMessage(message);
    }
    MessageSender.send(context, selectedMasterSecret, message, threadId, false);
  }

  @Override
  public Loader<Cursor> onCreateLoader(int arg0, Bundle arg1) {
    return new ConversationListLoader(getActivity(), queryFilter, archive);
  }

  @Override
  public void onLoadFinished(Loader<Cursor> arg0, Cursor cursor) {
    getListAdapter().changeCursor(cursor);
  }

  @Override
  public void onLoaderReset(Loader<Cursor> arg0) {
    getListAdapter().changeCursor(null);
  }

  @Override
  public void onItemClick(ConversationListItem item) {
    if (actionMode == null) {
      handleCreateConversation(item.getThreadId(), item.getRecipients(),
                               item.getDistributionType(), item.getLastSeen());
    } else {
      ConversationListAdapter adapter = (ConversationListAdapter)list.getAdapter();
      adapter.toggleThreadInBatchSet(item.getThreadId());
      adapter.populateRecipients(item.getThreadId(), item.getRecipients());

      if (adapter.getBatchSelections().size() == 0) {
        actionMode.finish();
      } else {
  actionMode.setSubtitle(getString(R.string.conversation_fragment_cab__batch_selection_amount,
           String.valueOf(adapter.getBatchSelections().size())));
      }

      adapter.notifyDataSetChanged();
    }
  }

  @Override
  public void onItemLongClick(ConversationListItem item) {
    actionMode = ((AppCompatActivity)getActivity()).startSupportActionMode(ConversationListFragment.this);

    getListAdapter().initializeBatchMode(true);
    getListAdapter().toggleThreadInBatchSet(item.getThreadId());
    getListAdapter().populateRecipients(item.getThreadId(), item.getRecipients());
    getListAdapter().notifyDataSetChanged();
  }

  @Override
  public void onSwitchToArchive() {
    ((ConversationSelectedListener)getActivity()).onSwitchToArchive();
  }

  public interface ConversationSelectedListener {
    void onCreateConversation(long threadId, Recipients recipients, int distributionType, long lastSeen);
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
      final Context context  = requireContext().getApplicationContext();
      final MasterSecret selectedMasterSecret = masterSecret;

      if (archive) {
        new SnackbarAsyncTask<Long>(getView(),
                                    getResources().getQuantityString(R.plurals.ConversationListFragment_moved_conversations_to_inbox, 1, 1),
                                    getString(R.string.ConversationListFragment_undo),
                                    androidx.core.content.ContextCompat.getColor(requireContext(), R.color.amber_500),
                                    Snackbar.LENGTH_LONG, false)
        {
          @Override
          protected void executeAction(@Nullable Long parameter) {
            DatabaseFactory.getThreadDatabase(context).unarchiveConversation(threadId);
          }

          @Override
          protected void reverseAction(@Nullable Long parameter) {
            DatabaseFactory.getThreadDatabase(context).archiveConversation(threadId);
          }
        }.execute(threadId);
      } else {
        new SnackbarAsyncTask<Long>(getView(),
                                    getResources().getQuantityString(R.plurals.ConversationListFragment_conversations_archived, 1, 1),
                                    getString(R.string.ConversationListFragment_undo),
                                    androidx.core.content.ContextCompat.getColor(requireContext(), R.color.amber_500),
                                    Snackbar.LENGTH_LONG, false)
        {
          @Override
          protected void executeAction(@Nullable Long parameter) {
            DatabaseFactory.getThreadDatabase(context).archiveConversation(threadId);

            if (!read) {
              DatabaseFactory.getThreadDatabase(context).setRead(threadId);
              MessageNotifier.updateNotification(context, selectedMasterSecret);
            }
          }

          @Override
          protected void reverseAction(@Nullable Long parameter) {
            DatabaseFactory.getThreadDatabase(context).unarchiveConversation(threadId);

            if (!read) {
              DatabaseFactory.getThreadDatabase(context).setUnread(threadId);
              MessageNotifier.updateNotification(context, selectedMasterSecret);
            }
          }
        }.execute(threadId);
      }
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
