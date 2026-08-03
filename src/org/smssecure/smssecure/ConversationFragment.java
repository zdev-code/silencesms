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
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.os.Build;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.Loader;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.view.ActionMode;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerView.ItemAnimator.ItemAnimatorFinishedListener;
import androidx.recyclerview.widget.RecyclerView.OnScrollListener;
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
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.smssecure.smssecure.ConversationAdapter.HeaderViewHolder;
import org.smssecure.smssecure.ConversationAdapter.ItemClickListener;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.database.DatabaseFactory;
import org.smssecure.smssecure.database.MmsSmsDatabase;
import org.smssecure.smssecure.database.loaders.ConversationLoader;
import org.smssecure.smssecure.database.model.MediaMmsMessageRecord;
import org.smssecure.smssecure.database.model.MessageRecord;
import org.smssecure.smssecure.mms.Slide;
import org.smssecure.smssecure.recipients.RecipientFactory;
import org.smssecure.smssecure.recipients.Recipients;
import org.smssecure.smssecure.sms.MessageSender;
import org.smssecure.smssecure.util.SilencePreferences;
import org.smssecure.smssecure.util.SaveAttachmentTask;
import org.smssecure.smssecure.util.SaveAttachmentTask.Attachment;
import org.smssecure.smssecure.util.StickyHeaderDecoration;
import org.smssecure.smssecure.util.ViewUtil;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor.TaskHandle;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class ConversationFragment extends Fragment
  implements LoaderManager.LoaderCallbacks<Cursor>
{
  private static final String TAG = ConversationFragment.class.getSimpleName();

  private static final long   PARTIAL_CONVERSATION_LIMIT = 500L;

  private final ActionModeCallback actionModeCallback     = new ActionModeCallback();
  private final ItemClickListener  selectionClickListener = new ConversationFragmentItemClickListener();

  private ConversationFragmentListener listener;

  private MasterSecret                masterSecret;
  private Recipients                  recipients;
  private long                        threadId;
  private long                        lastSeen;
  private boolean                     firstLoad;
  private ActionMode                  actionMode;
  private Locale                      locale;
  private RecyclerView                list;
  private RecyclerView.ItemDecoration lastSeenDecoration;
  private View                        loadMoreView;
  private View                        composeDivider;
  private View                        scrollToBottomButton;
  private TextView                    scrollDateHeader;
  private TaskHandle                  deleteTask;
  private TaskHandle                  saveAttachmentTask;
  private AlertDialog                 deleteProgressDialog;
  private AlertDialog                 saveProgressDialog;
  private boolean                     viewDestroyed;

  @Override
  public void onCreate(Bundle icicle) {
    super.onCreate(icicle);
    this.masterSecret = androidx.core.os.BundleCompat.getParcelable(getArguments(), "master_secret", MasterSecret.class);
    this.locale       = androidx.core.os.BundleCompat.getSerializable(getArguments(), PassphraseRequiredActionBarActivity.LOCALE_EXTRA, Locale.class);
  }

  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle bundle) {
    final View view = inflater.inflate(R.layout.conversation_fragment, container, false);
    viewDestroyed        = false;
    list                 = ViewUtil.findById(view, android.R.id.list);
    composeDivider       = ViewUtil.findById(view, R.id.compose_divider);
    scrollToBottomButton = ViewUtil.findById(view, R.id.scroll_to_bottom_button);
    scrollDateHeader     = ViewUtil.findById(view, R.id.scroll_date_header);

    scrollToBottomButton.setOnClickListener(new OnClickListener() {
      @Override
      public void onClick(final View view) {
        scrollToBottom();
      }
    });

    final LinearLayoutManager layoutManager = new LinearLayoutManager(getActivity(), LinearLayoutManager.VERTICAL, true);
    list.setHasFixedSize(false);
    list.setLayoutManager(layoutManager);

    loadMoreView = inflater.inflate(R.layout.load_more_header, container, false);
    loadMoreView.setOnClickListener(new OnClickListener() {
      @Override
      public void onClick(View v) {
        Bundle args = new Bundle();
        args.putLong("limit", 0);
        LoaderManager.getInstance(ConversationFragment.this).restartLoader(0, args, ConversationFragment.this);
      }
    });
    return view;
  }

  @Override
  public void onDestroyView() {
    viewDestroyed = true;
    if (deleteTask != null) deleteTask.cancel();
    if (saveAttachmentTask != null) saveAttachmentTask.cancel();
    deleteTask = null;
    saveAttachmentTask = null;
    dismissDialog(deleteProgressDialog);
    dismissDialog(saveProgressDialog);
    deleteProgressDialog = null;
    saveProgressDialog = null;
    super.onDestroyView();
  }

  private boolean isViewActive() {
    return isAdded() && !viewDestroyed && getView() != null &&
           getViewLifecycleOwner().getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.INITIALIZED);
  }

  private AlertDialog showProgressDialog(CharSequence title, CharSequence message) {
    if (!isViewActive()) return null;

    Context context = requireContext();
    AlertDialog dialog = new AlertDialog.Builder(context)
        .setTitle(title)
        .setMessage(message)
        .setView(new ProgressBar(context))
        .setCancelable(false)
        .create();
    dialog.show();
    return dialog;
  }

  private AlertDialog showProgressDialog(int title, int message) {
    return showProgressDialog(getString(title), getString(message));
  }

  private void dismissDialog(AlertDialog dialog) {
    if (dialog != null) dialog.dismiss();
  }

  @Override
  public void onViewCreated(@NonNull View view, Bundle bundle) {
    super.onViewCreated(view, bundle);

    initializeResources();
    initializeListAdapter();
  }

  @Override
  public void onAttach(@NonNull Context context) {
    super.onAttach(context);
    this.listener = (ConversationFragmentListener) context;
  }

  @Override
  public void onResume() {
    super.onResume();

    if (list.getAdapter() != null) {
      list.getAdapter().notifyDataSetChanged();
    }
  }

  public void onNewIntent() {
    if (actionMode != null) {
      actionMode.finish();
    }

    initializeResources();
    initializeListAdapter();

    if (threadId == -1) {
      LoaderManager.getInstance(this).restartLoader(0, Bundle.EMPTY, this);
    }
  }

  public void reloadList() {
    LoaderManager.getInstance(this).restartLoader(0, Bundle.EMPTY, this);
  }

  private void initializeResources() {
    this.recipients     = RecipientFactory.getRecipientsForIds(getActivity(), getActivity().getIntent().getLongArrayExtra("recipients"), true);
    this.threadId       = this.getActivity().getIntent().getLongExtra("thread_id", -1);
    this.lastSeen       = this.getActivity().getIntent().getLongExtra(ConversationActivity.LAST_SEEN_EXTRA, -1);
    this.firstLoad      = true;

    OnScrollListener scrollListener = new ConversationScrollListener(getActivity());
    list.addOnScrollListener(scrollListener);
  }

  private void initializeListAdapter() {
    if (this.recipients != null && this.threadId != -1) {
      ConversationAdapter adapter = new ConversationAdapter(getActivity(), masterSecret, locale, selectionClickListener, null, this.recipients);
      list.setAdapter(adapter);
      list.addItemDecoration(new StickyHeaderDecoration(adapter, false, false));

      setLastSeen(lastSeen);
      LoaderManager.getInstance(this).restartLoader(0, Bundle.EMPTY, this);
      list.getItemAnimator().setMoveDuration(120);
    }
  }

  private void setCorrectMenuVisibility(Menu menu) {
    Set<MessageRecord> messageRecords = getListAdapter().getSelectedItems();

    if (actionMode != null && messageRecords.size() == 0) {
      actionMode.finish();
      return;
    }

    if (messageRecords.size() > 1) {
      menu.findItem(R.id.menu_context_forward).setVisible(false);
      menu.findItem(R.id.menu_context_details).setVisible(false);
      menu.findItem(R.id.menu_context_save_attachment).setVisible(false);
      menu.findItem(R.id.menu_context_resend).setVisible(false);
    } else {
      MessageRecord messageRecord = messageRecords.iterator().next();

      menu.findItem(R.id.menu_context_resend).setVisible(messageRecord.isFailed());
      menu.findItem(R.id.menu_context_save_attachment).setVisible(messageRecord.isMms()              &&
                                                                  !messageRecord.isMmsNotification() &&
                                                                  ((MediaMmsMessageRecord)messageRecord).containsMediaSlide());

      menu.findItem(R.id.menu_context_forward).setVisible(true);
      menu.findItem(R.id.menu_context_details).setVisible(true);
      menu.findItem(R.id.menu_context_copy).setVisible(true);
    }
  }

  private ConversationAdapter getListAdapter() {
    return (ConversationAdapter) list.getAdapter();
  }

  private MessageRecord getSelectedMessageRecord() {
    Set<MessageRecord> messageRecords = getListAdapter().getSelectedItems();

    if (messageRecords.size() == 1) return messageRecords.iterator().next();
    else                            throw new AssertionError();
  }

  public void reload(Recipients recipients, long threadId) {
    this.recipients = recipients;

    if (this.threadId != threadId) {
      this.threadId = threadId;
      initializeListAdapter();
    }
  }

  public void scrollToBottom() {
    list.scrollToPosition(0);
  }

  public void setLastSeen(long lastSeen) {
    this.lastSeen = lastSeen;
    if (lastSeenDecoration != null) {
      list.removeItemDecoration(lastSeenDecoration);
    }

    final Context context = getActivity().getApplicationContext();
    if (!SilencePreferences.hideUnreadMessageDivider(context)) {
      lastSeenDecoration = new ConversationAdapter.LastSeenHeader(getListAdapter(), lastSeen);
      list.addItemDecoration(lastSeenDecoration);
    }
  }

  private void handleCopyMessage(final Set<MessageRecord> messageRecords) {
    List<MessageRecord> messageList = new LinkedList<>(messageRecords);
    Collections.sort(messageList, new Comparator<MessageRecord>() {
      @Override
      public int compare(MessageRecord lhs, MessageRecord rhs) {
        if      (lhs.getDateReceived() < rhs.getDateReceived())  return -1;
        else if (lhs.getDateReceived() == rhs.getDateReceived()) return 0;
        else                                                     return 1;
      }
    });

    StringBuilder    bodyBuilder = new StringBuilder();
    ClipboardManager clipboard   = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
    boolean          first       = true;

    for (MessageRecord messageRecord : messageList) {
      String body = messageRecord.getDisplayBody().toString();

      if (body != null) {
        if (!first) bodyBuilder.append('\n');
        bodyBuilder.append(body);
        first = false;
      }
    }

    String result = bodyBuilder.toString();

    if (!TextUtils.isEmpty(result))
      clipboard.setPrimaryClip(ClipData.newPlainText(null, result));
  }

  private void handleDeleteMessages(final Set<MessageRecord> messageRecords) {
    int                 messagesCount = messageRecords.size();
    AlertDialog.Builder builder       = new AlertDialog.Builder(getActivity());

    builder.setIconAttribute(R.attr.dialog_alert_icon);
    builder.setTitle(getActivity().getResources().getQuantityString(R.plurals.ConversationFragment_delete_selected_messages, messagesCount, messagesCount));
    builder.setMessage(getActivity().getResources().getQuantityString(R.plurals.ConversationFragment_this_will_permanently_delete_all_n_selected_messages, messagesCount, messagesCount));
    builder.setCancelable(true);

    builder.setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
      @Override
      public void onClick(DialogInterface dialog, int which) {
        final Context context = requireContext().getApplicationContext();
        final MessageRecord[] records = messageRecords.toArray(new MessageRecord[messageRecords.size()]);
        deleteProgressDialog = showProgressDialog(R.string.ConversationFragment_deleting,
                                                  R.string.ConversationFragment_deleting_messages);
        deleteTask = AppTaskExecutor.getInstance().submitSerial(
            () -> {
              boolean threadDeleted = false;
              for (MessageRecord messageRecord : records) {
                if (messageRecord.isMms()) {
                  threadDeleted |= DatabaseFactory.getMmsDatabase(context).delete(messageRecord.getId());
                } else {
                  threadDeleted |= DatabaseFactory.getSmsDatabase(context).deleteMessage(messageRecord.getId());
                }
              }
              return threadDeleted;
            },
            threadDeleted -> {
              if (!isViewActive()) return;
              dismissDialog(deleteProgressDialog);
              deleteProgressDialog = null;
              deleteTask = null;
              if (threadDeleted) {
                threadId = -1;
                listener.setThreadId(threadId);
              }
            },
            exception -> {
              Log.w(TAG, "Unable to delete selected messages", exception);
              if (!isViewActive()) return;
              dismissDialog(deleteProgressDialog);
              deleteProgressDialog = null;
              deleteTask = null;
            });
      }
    });

    builder.setNegativeButton(R.string.no, null);
    builder.show();
  }

  private void handleDisplayDetails(MessageRecord message) {
    Intent intent = new Intent(getActivity(), MessageDetailsActivity.class);
    intent.putExtra(MessageDetailsActivity.MASTER_SECRET_EXTRA, masterSecret);
    intent.putExtra(MessageDetailsActivity.MESSAGE_ID_EXTRA, message.getId());
    intent.putExtra(MessageDetailsActivity.THREAD_ID_EXTRA, threadId);
    intent.putExtra(MessageDetailsActivity.TYPE_EXTRA, message.isMms() ? MmsSmsDatabase.MMS_TRANSPORT : MmsSmsDatabase.SMS_TRANSPORT);
    intent.putExtra(MessageDetailsActivity.RECIPIENTS_IDS_EXTRA, recipients.getIds());
    startActivity(intent);
  }

  private void handleForwardMessage(MessageRecord message) {
    Intent composeIntent = new Intent(getActivity(), ShareActivity.class);
    composeIntent.putExtra(Intent.EXTRA_TEXT, message.getDisplayBody().toString());
    if (message.isMms()) {
      MediaMmsMessageRecord mediaMessage = (MediaMmsMessageRecord) message;
      if (mediaMessage.containsMediaSlide()) {
        Slide slide = mediaMessage.getSlideDeck().getSlides().get(0);
        composeIntent.putExtra(Intent.EXTRA_STREAM, slide.getUri());
        composeIntent.setType(slide.getContentType());
      }
    }
    startActivity(composeIntent);
  }

  private void handleResendMessage(final MessageRecord message) {
    final Context context = getActivity().getApplicationContext();
    final MasterSecret taskMasterSecret = masterSecret;
    AppTaskExecutor.getInstance().submitSerial(
        () -> {
          MessageSender.resend(context, taskMasterSecret, message);
          return null;
        },
        ignored -> {},
        exception -> Log.w(TAG, "Unable to resend message", exception));
  }

  private void handleSaveAttachment(final MediaMmsMessageRecord message) {
    SaveAttachmentTask.showWarningDialog(getActivity(), new DialogInterface.OnClickListener() {
      public void onClick(DialogInterface dialog, int which) {
        for (Slide slide : message.getSlideDeck().getSlides()) {
          if (slide.hasImage() || slide.hasVideo() || slide.hasAudio()) {
            final Context context = requireContext().getApplicationContext();
            final Attachment attachment = new Attachment(slide.getUri(), slide.getContentType(), message.getDateReceived());
            saveProgressDialog = showProgressDialog(
                getResources().getQuantityString(R.plurals.ConversationFragment_saving_n_attachments, 1, 1),
                getResources().getQuantityString(R.plurals.ConversationFragment_saving_n_attachments_to_sd_card, 1, 1));
            saveAttachmentTask = AppTaskExecutor.getInstance().submitSerial(
                () -> SaveAttachmentTask.save(context, masterSecret, attachment),
                result -> {
                  if (!isViewActive()) return;
                  dismissDialog(saveProgressDialog);
                  saveProgressDialog = null;
                  saveAttachmentTask = null;
                  if (result != SaveAttachmentTask.SUCCESS) Log.w(TAG, "Unable to save attachment, result: " + result);
                  SaveAttachmentTask.showResultToast(context, result, 1);
                },
                exception -> {
                  Log.w(TAG, "Unable to save attachment", exception);
                  if (!isViewActive()) return;
                  dismissDialog(saveProgressDialog);
                  saveProgressDialog = null;
                  saveAttachmentTask = null;
                  SaveAttachmentTask.showResultToast(context, SaveAttachmentTask.FAILURE, 1);
                });
            return;
          }
        }

        Log.w(TAG, "No slide with attachable media found, failing nicely.");
        Toast.makeText(getActivity(),
                       getResources().getQuantityString(R.plurals.ConversationFragment_error_while_saving_attachments_to_sd_card, 1),
                       Toast.LENGTH_LONG).show();
      }
    });
  }

  @Override
  public Loader<Cursor> onCreateLoader(int id, Bundle args) {
    return new ConversationLoader(getActivity(), threadId, args.getLong("limit", PARTIAL_CONVERSATION_LIMIT), lastSeen);
  }


  @Override
  public void onLoadFinished(Loader<Cursor> cursorLoader, Cursor cursor) {
    Log.w(TAG, "onLoadFinished");
    ConversationLoader loader = (ConversationLoader)cursorLoader;

    if (list.getAdapter() != null) {
      if (cursor.getCount() >= PARTIAL_CONVERSATION_LIMIT && loader.hasLimit()) {
        getListAdapter().setFooterView(loadMoreView);
      } else {
        getListAdapter().setFooterView(null);
      }

      if (lastSeen == -1) {
        setLastSeen(loader.getLastSeen());
      }

      getListAdapter().changeCursor(cursor);

      int lastSeenPosition = getListAdapter().findLastSeenPosition(lastSeen);

      if (firstLoad) {
        scrollToLastSeenPosition(lastSeenPosition);
        firstLoad = false;
      }

      if (lastSeenPosition <= 0) {
        setLastSeen(0);
      }
    }
  }

  @Override
  public void onLoaderReset(Loader<Cursor> arg0) {
    if (list.getAdapter() != null) {
      getListAdapter().changeCursor(null);
    }
  }

  private void scrollToLastSeenPosition(final int lastSeenPosition) {
    if (lastSeenPosition > 0) {
      list.post(new Runnable() {
        @Override
        public void run() {
          ((LinearLayoutManager)list.getLayoutManager()).scrollToPositionWithOffset(lastSeenPosition, list.getHeight());
        }
      });
    }
  }

  public interface ConversationFragmentListener {
    void setThreadId(long threadId);
  }

  private class ConversationScrollListener extends OnScrollListener {

    private final Animation              scrollButtonInAnimation;
    private final Animation              scrollButtonOutAnimation;
    private final ConversationDateHeader conversationDateHeader;

    private boolean wasAtBottom           = true;
    private boolean wasAtZoomScrollHeight = false;
    private long    lastPositionId        = -1;

    ConversationScrollListener(@NonNull Context context) {
      this.scrollButtonInAnimation  = AnimationUtils.loadAnimation(context, R.anim.fade_scale_in);
      this.scrollButtonOutAnimation = AnimationUtils.loadAnimation(context, R.anim.fade_scale_out);
      this.conversationDateHeader   = new ConversationDateHeader(context, scrollDateHeader);

      this.scrollButtonInAnimation.setDuration(100);
      this.scrollButtonOutAnimation.setDuration(50);
    }

    @Override
    public void onScrolled(final RecyclerView rv, final int dx, final int dy) {
      boolean currentlyAtBottom           = isAtBottom();
      boolean currentlyAtZoomScrollHeight = isAtZoomScrollHeight();
      int     positionId                  = getHeaderPositionId();

      if (currentlyAtBottom && !wasAtBottom) {
        ViewUtil.fadeOut(composeDivider, 50, View.INVISIBLE);
        ViewUtil.animateOut(scrollToBottomButton, scrollButtonOutAnimation, View.INVISIBLE);
      } else if (!currentlyAtBottom && wasAtBottom) {
        ViewUtil.fadeIn(composeDivider, 500);
      }

      if (currentlyAtZoomScrollHeight && !wasAtZoomScrollHeight) {
        ViewUtil.animateIn(scrollToBottomButton, scrollButtonInAnimation);
      }

      if (positionId != lastPositionId) {
        bindScrollHeader(conversationDateHeader, positionId);
      }

      wasAtBottom           = currentlyAtBottom;
      wasAtZoomScrollHeight = currentlyAtZoomScrollHeight;
      lastPositionId        = positionId;
    }

    @Override
    public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
      if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
        conversationDateHeader.show();
      } else if (newState == RecyclerView.SCROLL_STATE_IDLE) {
        conversationDateHeader.hide();
      }
    }

    private boolean isAtBottom() {
      if (list.getChildCount() == 0) return true;

      View    bottomView       = list.getChildAt(0);
      int     firstVisibleItem = ((LinearLayoutManager) list.getLayoutManager()).findFirstVisibleItemPosition();
      boolean isAtBottom       = (firstVisibleItem == 0);

      return isAtBottom && bottomView.getBottom() <= list.getHeight();
    }

    private boolean isAtZoomScrollHeight() {
      return ((LinearLayoutManager) list.getLayoutManager()).findFirstCompletelyVisibleItemPosition() > 4;
    }

    private int getHeaderPositionId() {
      return ((LinearLayoutManager)list.getLayoutManager()).findLastVisibleItemPosition();
    }

    private void bindScrollHeader(HeaderViewHolder headerViewHolder, int positionId) {
      if (((ConversationAdapter)list.getAdapter()).getHeaderId(positionId) != -1) {
        ((ConversationAdapter) list.getAdapter()).onBindHeaderViewHolder(headerViewHolder, positionId);
      }
    }
  }

  private class ConversationFragmentItemClickListener implements ItemClickListener {

    @Override
    public void onItemClick(ConversationItem item) {
      if (actionMode != null) {
        MessageRecord messageRecord = item.getMessageRecord();
        ((ConversationAdapter) list.getAdapter()).toggleSelection(messageRecord);
        list.getAdapter().notifyDataSetChanged();

        setCorrectMenuVisibility(actionMode.getMenu());
      }
    }

    @Override
    public void onItemLongClick(ConversationItem item) {
      if (actionMode == null) {
        ((ConversationAdapter) list.getAdapter()).toggleSelection(item.getMessageRecord());
        list.getAdapter().notifyDataSetChanged();

        actionMode = ((AppCompatActivity)getActivity()).startSupportActionMode(actionModeCallback);
      }
    }
  }

  private class ActionModeCallback implements ActionMode.Callback {

    @Override
    public boolean onCreateActionMode(ActionMode mode, Menu menu) {
      MenuInflater inflater = mode.getMenuInflater();
      inflater.inflate(R.menu.conversation_context, menu);

      ((BaseActionBarActivity) requireActivity()).setSystemBarColors(
          androidx.core.content.ContextCompat.getColor(requireContext(), R.color.action_mode_status_bar),
          androidx.core.content.ContextCompat.getColor(requireContext(), android.R.color.black));

      setCorrectMenuVisibility(menu);
      return true;
    }

    @Override
    public boolean onPrepareActionMode(ActionMode actionMode, Menu menu) {
      return false;
    }

    @Override
    public void onDestroyActionMode(ActionMode mode) {
      ((ConversationAdapter)list.getAdapter()).clearSelection();
      list.getAdapter().notifyDataSetChanged();

      ((BaseActionBarActivity) requireActivity()).resetSystemBarColors();

      actionMode = null;
    }

  @Override
  @SuppressLint("NonConstantResourceId")
  public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
      int itemId = item.getItemId();
      if (itemId == R.id.menu_context_copy) {
          handleCopyMessage(getListAdapter().getSelectedItems());
          actionMode.finish();
          return true;
        } else if (itemId == R.id.menu_context_delete_message) {
          handleDeleteMessages(getListAdapter().getSelectedItems());
          actionMode.finish();
          return true;
        } else if (itemId == R.id.menu_context_details) {
          handleDisplayDetails(getSelectedMessageRecord());
          actionMode.finish();
          return true;
        } else if (itemId == R.id.menu_context_forward) {
          handleForwardMessage(getSelectedMessageRecord());
          actionMode.finish();
          return true;
        } else if (itemId == R.id.menu_context_resend) {
          handleResendMessage(getSelectedMessageRecord());
          actionMode.finish();
          return true;
        } else if (itemId == R.id.menu_context_save_attachment) {
          handleSaveAttachment((MediaMmsMessageRecord)getSelectedMessageRecord());
          actionMode.finish();
          return true;
        }

      return false;
    }
  }

  private static class ConversationDateHeader extends HeaderViewHolder {

    private final Animation animateIn;
    private final Animation animateOut;

    private boolean pendingHide = false;

    private ConversationDateHeader(Context context, TextView textView) {
      super(textView);
      this.animateIn  = AnimationUtils.loadAnimation(context, R.anim.slide_from_top);
      this.animateOut = AnimationUtils.loadAnimation(context, R.anim.slide_to_top);

      this.animateIn.setDuration(100);
      this.animateOut.setDuration(100);
    }

    public void show() {
      if (pendingHide) {
        pendingHide = false;
      } else {
        ViewUtil.animateIn(textView, animateIn);
      }
    }

    public void hide() {
      pendingHide = true;

      textView.postDelayed(new Runnable() {
        @Override
        public void run() {
          if (pendingHide) {
            pendingHide = false;
            ViewUtil.animateOut(textView, animateOut, View.GONE);
          }
        }
      }, 400);
    }
  }
}
