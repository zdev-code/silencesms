package org.smssecure.smssecure;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import org.smssecure.smssecure.color.MaterialColor;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.database.MmsSmsDatabase;
import org.smssecure.smssecure.database.model.MessageRecord;
import org.smssecure.smssecure.domain.conversation.ConversationUnlockCapability;
import org.smssecure.smssecure.domain.security.UnlockSession;
import org.smssecure.smssecure.notifications.MessageNotifier;
import org.smssecure.smssecure.recipients.RecipientFactory;
import org.smssecure.smssecure.recipients.Recipients;
import org.smssecure.smssecure.ui.LifecycleStateCollector;
import org.smssecure.smssecure.ui.messagedetails.MessageDetailsUiState;
import org.smssecure.smssecure.ui.messagedetails.MessageDetailsViewModel;
import org.smssecure.smssecure.util.DateUtils;
import org.smssecure.smssecure.util.DynamicLanguage;
import org.smssecure.smssecure.util.SilencePreferences;
import org.smssecure.smssecure.util.Util;

import java.sql.Date;
import java.text.SimpleDateFormat;
import java.util.HashSet;
import java.util.Locale;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public final class MessageDetailsFragment extends Fragment
    implements Recipients.RecipientsModifiedListener {
  private static final String TAG = MessageDetailsFragment.class.getSimpleName();

  static final String MESSAGE_ID_ARGUMENT    = "message_details.message_id";
  static final String THREAD_ID_ARGUMENT     = "message_details.thread_id";
  static final String TRANSPORT_ARGUMENT     = "message_details.transport";
  static final String RECIPIENT_IDS_ARGUMENT = "message_details.recipient_ids";

  static final int TRANSPORT_SMS = 1;
  static final int TRANSPORT_MMS = 2;

  private final DynamicLanguage dynamicLanguage = new DynamicLanguage();

  private MasterSecret masterSecret;
  private UnlockSession unlockSession;
  private long threadId;
  private Recipients recipients;
  private ConversationItem conversationItem;
  private ViewGroup itemParent;
  private View metadataContainer;
  private TextView errorText;
  private TextView sentDate;
  private TextView receivedDate;
  private View receivedContainer;
  private TextView transport;
  private TextView toFrom;
  private ListView recipientsList;
  private LayoutInflater inflater;
  private MessageDetailsViewModel viewModel;

  static Bundle arguments(long messageId, long threadId, int transport, @NonNull long[] recipientIds) {
    Bundle arguments = new Bundle();
    arguments.putLong(MESSAGE_ID_ARGUMENT, messageId);
    arguments.putLong(THREAD_ID_ARGUMENT, threadId);
    arguments.putInt(TRANSPORT_ARGUMENT, transport);
    arguments.putLongArray(RECIPIENT_IDS_ARGUMENT, recipientIds.clone());
    requireValidArguments(arguments);
    return arguments;
  }

  static int transportOf(boolean isMms) {
    return isMms ? TRANSPORT_MMS : TRANSPORT_SMS;
  }

  static String databaseTransport(int transport) {
    switch (transport) {
      case TRANSPORT_SMS: return MmsSmsDatabase.SMS_TRANSPORT;
      case TRANSPORT_MMS: return MmsSmsDatabase.MMS_TRANSPORT;
      default: throw new SecurityException("Invalid message transport");
    }
  }

  static void requireValidArguments(@NonNull Bundle arguments) {
    Long messageId = androidx.core.os.BundleCompat.getSerializable(
        arguments, MESSAGE_ID_ARGUMENT, Long.class);
    Long threadId = androidx.core.os.BundleCompat.getSerializable(
        arguments, THREAD_ID_ARGUMENT, Long.class);
    Integer transport = androidx.core.os.BundleCompat.getSerializable(
        arguments, TRANSPORT_ARGUMENT, Integer.class);
    long[] recipientIds = androidx.core.os.BundleCompat.getSerializable(
        arguments, RECIPIENT_IDS_ARGUMENT, long[].class);

    if (messageId == null || messageId <= 0L || threadId == null || threadId <= 0L) {
      throw new SecurityException("Invalid message details IDs");
    }
    if (transport == null || (transport != TRANSPORT_SMS && transport != TRANSPORT_MMS)) {
      throw new SecurityException("Invalid message details transport");
    }
    if (recipientIds == null || recipientIds.length == 0) {
      throw new SecurityException("Message details recipient IDs are required");
    }
    for (long recipientId : recipientIds) {
      if (recipientId <= 0L) throw new SecurityException("Invalid message details recipient ID");
    }
  }

  @Override
  public void onCreate(@Nullable Bundle state) {
    super.onCreate(state);
    requireValidArguments(requireArguments());
    unlockSession = UnlockSession.capture();
    threadId = requireArguments().getLong(THREAD_ID_ARGUMENT);
    viewModel = new ViewModelProvider(this).get(MessageDetailsViewModel.class);
  }

  @Override
  public @NonNull View onCreateView(@NonNull LayoutInflater inflater,
                                    @Nullable ViewGroup container,
                                    @Nullable Bundle state) {
    return inflater.inflate(R.layout.message_details_activity, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
    super.onViewCreated(view, state);
    initializeResources(view);
    initializeActionBar();
    LifecycleStateCollector.collect(getViewLifecycleOwner(), viewModel.getState(), this::renderState);
    loadMessage();
  }

  @Override
  public void onResume() {
    super.onResume();
    dynamicLanguage.onResume(requireActivity());
    hostActivity().getSupportActionBar().setTitle(R.string.AndroidManifest__message_details);
    MessageNotifier.setVisibleThread(threadId);
  }

  @Override
  public void onPause() {
    super.onPause();
    MessageNotifier.setVisibleThread(-1L);
  }

  @Override
  public void onDestroyView() {
    clearSensitiveState();
    if (recipients != null) recipients.removeListener(this);
    recipients = null;
    super.onDestroyView();
  }

  public void clearSensitiveState() {
    if (viewModel != null) viewModel.clearSensitiveState();
    if (conversationItem != null) {
      conversationItem.unbind();
      conversationItem = null;
    }
    if (itemParent != null) itemParent.removeAllViews();
    if (recipientsList != null) {
      for (int index = 0; index < recipientsList.getChildCount(); index++) {
        View child = recipientsList.getChildAt(index);
        if (child instanceof MessageRecipientListItem) {
          ((MessageRecipientListItem) child).unbind();
        }
      }
      recipientsList.setAdapter(null);
    }
    masterSecret = null;
  }

  private ConversationListActivity hostActivity() {
    return (ConversationListActivity) requireActivity();
  }

  private void initializeActionBar() {
    hostActivity().getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    recipients = RecipientFactory.getRecipientsForIds(
        requireContext(), requireArguments().getLongArray(RECIPIENT_IDS_ARGUMENT), true);
    recipients.addListener(this);
    setActionBarColor(recipients.getColor());
  }

  private void setActionBarColor(MaterialColor color) {
    hostActivity().getSupportActionBar().setBackgroundDrawable(
        new ColorDrawable(color.toActionBarColor(requireContext())));
    hostActivity().setStatusBarColorCompat(color.toStatusBarColor(requireContext()));
  }

  @Override
  public void onModified(final Recipients recipients) {
    Util.runOnMain(() -> setActionBarColor(recipients.getColor()));
  }

  private void initializeResources(View view) {
    inflater = LayoutInflater.from(requireContext());
    recipientsList = view.findViewById(R.id.recipients_list);
    View header = inflater.inflate(R.layout.message_details_header, recipientsList, false);
    itemParent = header.findViewById(R.id.item_container);
    metadataContainer = header.findViewById(R.id.metadata_container);
    errorText = header.findViewById(R.id.error_text);
    sentDate = header.findViewById(R.id.sent_time);
    receivedContainer = header.findViewById(R.id.received_container);
    receivedDate = header.findViewById(R.id.received_time);
    transport = header.findViewById(R.id.transport);
    toFrom = header.findViewById(R.id.tofrom);
    recipientsList.setHeaderDividersEnabled(false);
    recipientsList.addHeaderView(header, null, false);
  }

  private void loadMessage() {
    viewModel.load(databaseTransport(requireArguments().getInt(TRANSPORT_ARGUMENT)),
        requireArguments().getLong(MESSAGE_ID_ARGUMENT),
        new ConversationUnlockCapability(unlockSession), result -> {
          if (!isAdded() || getView() == null) return;
          masterSecret = result.getMasterSecret();
          handleRecipientsResolved(result.getMessage(), result.getRecipients());
        });
  }

  private void renderState(MessageDetailsUiState state) {
    if (state.isFailed()) Log.w(TAG, "Unable to load message details");
  }

  private void handleRecipientsResolved(MessageRecord messageRecord, Recipients resolvedRecipients) {
    if (messageRecord == null || resolvedRecipients == null) {
      Log.w(TAG, "Message details record is unavailable");
      androidx.navigation.fragment.NavHostFragment.findNavController(this).navigateUp();
      return;
    }

    inflateMessageViewIfAbsent(messageRecord);
    updateRecipients(messageRecord, resolvedRecipients);
    if (messageRecord.isFailed()) {
      errorText.setVisibility(View.VISIBLE);
      metadataContainer.setVisibility(View.GONE);
    } else {
      updateTransport(messageRecord);
      updateTime(requireContext(), messageRecord);
      errorText.setVisibility(View.GONE);
      metadataContainer.setVisibility(View.VISIBLE);
    }
  }

  private void inflateMessageViewIfAbsent(MessageRecord messageRecord) {
    if (conversationItem != null) return;
    if (messageRecord.isGroupAction()) {
      conversationItem = (ConversationItem) inflater.inflate(
          R.layout.conversation_item_update, itemParent, false);
    } else if (messageRecord.isOutgoing()) {
      conversationItem = (ConversationItem) inflater.inflate(
          R.layout.conversation_item_sent, itemParent, false);
    } else {
      conversationItem = (ConversationItem) inflater.inflate(
          R.layout.conversation_item_received, itemParent, false);
    }
    itemParent.addView(conversationItem);
  }

  private void updateRecipients(MessageRecord messageRecord, Recipients resolvedRecipients) {
    final int toFromRes;
    if (messageRecord.isMms() && !messageRecord.isPush() && !messageRecord.isOutgoing()) {
      toFromRes = R.string.message_details_header__with;
    } else if (messageRecord.isOutgoing()) {
      toFromRes = R.string.message_details_header__to;
    } else {
      toFromRes = R.string.message_details_header__from;
    }
    toFrom.setText(toFromRes);
    conversationItem.bind(masterSecret, messageRecord, dynamicLanguage.getCurrentLocale(),
        new HashSet<MessageRecord>(), resolvedRecipients);
    conversationItem.hideClickForDetails();
    recipientsList.setAdapter(new MessageDetailsRecipientAdapter(
        requireContext(), masterSecret, messageRecord, resolvedRecipients));
  }

  private void updateTransport(MessageRecord messageRecord) {
    final String transportText;
    if (messageRecord.isOutgoing() && messageRecord.isFailed()) {
      transportText = "-";
    } else if (messageRecord.isPending()) {
      transportText = getString(R.string.ConversationFragment_pending);
    } else if (messageRecord.isMms()) {
      transportText = getString(R.string.ConversationFragment_mms);
    } else {
      transportText = getString(R.string.ConversationFragment_sms);
    }
    transport.setText(transportText);
  }

  private void updateTime(Context context, MessageRecord messageRecord) {
    boolean deliveryReports = SilencePreferences.isSmsDeliveryReportsEnabled(context);
    if (messageRecord.isPending() || messageRecord.isFailed()) {
      sentDate.setText("-");
      if (!deliveryReports) receivedContainer.setVisibility(View.GONE);
      receivedDate.setText("-");
      return;
    }

    Locale dateLocale = dynamicLanguage.getCurrentLocale();
    SimpleDateFormat formatter = DateUtils.getDetailedDateFormatter(requireContext(), dateLocale);
    sentDate.setText(formatter.format(new Date(messageRecord.getDateSent())));
    if (!messageRecord.isOutgoing()) {
      receivedDate.setText(formatter.format(new Date(messageRecord.getDateReceived())));
    } else if (deliveryReports) {
      if (!messageRecord.isDelivered()) {
        receivedDate.setText(R.string.no);
      } else if (messageRecord.getDateDeliveryReceived() == 0) {
        receivedDate.setText(R.string.yes);
      } else {
        receivedDate.setText(formatter.format(new Date(messageRecord.getDateDeliveryReceived())));
      }
    } else {
      receivedContainer.setVisibility(View.GONE);
    }
  }
}