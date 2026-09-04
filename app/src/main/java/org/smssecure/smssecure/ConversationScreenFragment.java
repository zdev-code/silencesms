/**
 * Copyright (C) 2011 Whisper Systems
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

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuff.Mode;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Browser;
import android.provider.ContactsContract;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.BlendModeColorFilterCompat;
import androidx.core.graphics.BlendModeCompat;
import androidx.core.view.WindowCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.fragment.app.Fragment;
import androidx.appcompat.app.AlertDialog;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.View;
import android.view.ViewGroup;
import android.view.View.OnClickListener;
import android.view.View.OnFocusChangeListener;
import android.view.View.OnKeyListener;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.protobuf.ByteString;

import org.smssecure.smssecure.TransportOptions.OnTransportChangedListener;
import org.smssecure.smssecure.audio.AudioSlidePlayer;
import org.smssecure.smssecure.attachments.AttachmentId;
import org.smssecure.smssecure.color.MaterialColor;
import org.smssecure.smssecure.components.AnimatingToggle;
import org.smssecure.smssecure.components.ComposeText;
import org.smssecure.smssecure.components.KeyboardAwareLinearLayout;
import org.smssecure.smssecure.components.KeyboardAwareLinearLayout.OnKeyboardShownListener;
import org.smssecure.smssecure.components.SendButton;
import org.smssecure.smssecure.components.InputAwareLayout;
import org.smssecure.smssecure.components.emoji.EmojiDrawer.EmojiEventListener;
import org.smssecure.smssecure.components.emoji.EmojiDrawer;
import org.smssecure.smssecure.components.emoji.EmojiToggle;
import org.smssecure.smssecure.contacts.ContactAccessor;
import org.smssecure.smssecure.contacts.ContactAccessor.ContactData;
import org.smssecure.smssecure.crypto.KeyExchangeInitiator;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.data.conversationscreen.ConversationScreenRepository;
import org.smssecure.smssecure.data.conversationscreen.ConversationScreenRepository.MediaSendRequest;
import org.smssecure.smssecure.data.conversationscreen.ConversationScreenRepository.TextSendRequest;
import org.smssecure.smssecure.domain.conversation.ConversationUnlockCapability;
import org.smssecure.smssecure.domain.conversation.ConversationPayload;
import org.smssecure.smssecure.domain.conversation.ConversationPayloadStore;
import org.smssecure.smssecure.domain.security.UnlockSession;
import org.smssecure.smssecure.ui.LifecycleStateCollector;
import org.smssecure.smssecure.ui.conversationscreen.ConversationScreenUiState;
import org.smssecure.smssecure.ui.conversationscreen.ConversationScreenViewModel;
import org.smssecure.smssecure.crypto.SecurityEvent;
import org.smssecure.smssecure.crypto.SessionUtil;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;
import org.smssecure.smssecure.database.DraftDatabase;
import org.smssecure.smssecure.database.DraftDatabase.Draft;
import org.smssecure.smssecure.database.DraftDatabase.Drafts;
import org.smssecure.smssecure.database.RecipientPreferenceDatabase.RecipientsPreferences;
import org.smssecure.smssecure.database.ThreadDatabase;
import org.smssecure.smssecure.mms.AttachmentManager;
import org.smssecure.smssecure.mms.PartAuthority;
import org.smssecure.smssecure.mms.AttachmentManager.MediaType;
import org.smssecure.smssecure.mms.AttachmentTypeSelectorAdapter;
import org.smssecure.smssecure.mms.MediaConstraints;
import org.smssecure.smssecure.mms.Slide;
import org.smssecure.smssecure.mms.SlideDeck;
import org.smssecure.smssecure.notifications.MessageNotifier;
import org.smssecure.smssecure.permissions.Permissions;
import org.smssecure.smssecure.protocol.AutoInitiate;
import org.smssecure.smssecure.recipients.Recipient;
import org.smssecure.smssecure.recipients.RecipientFactory;
import org.smssecure.smssecure.recipients.RecipientFormattingException;
import org.smssecure.smssecure.recipients.Recipients;
import org.smssecure.smssecure.recipients.Recipients.RecipientsModifiedListener;
import org.smssecure.smssecure.service.KeyCachingService;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor.TaskHandle;
import org.smssecure.smssecure.util.concurrent.AssertedSuccessListener;
import org.smssecure.smssecure.util.CharacterCalculator.CharacterState;
import org.smssecure.smssecure.util.Dialogs;
import org.smssecure.smssecure.util.DynamicLanguage;
import org.smssecure.smssecure.util.DynamicTheme;
import org.smssecure.smssecure.util.MediaUtil;
import org.smssecure.smssecure.util.SilencePreferences;
import org.smssecure.smssecure.util.views.Stub;
import org.smssecure.smssecure.util.TelephonyUtil;
import org.smssecure.smssecure.util.Util;
import org.smssecure.smssecure.util.ViewUtil;
import org.smssecure.smssecure.util.concurrent.ListenableFuture;
import org.smssecure.smssecure.util.concurrent.SettableFuture;
import org.smssecure.smssecure.util.dualsim.SubscriptionInfoCompat;
import org.smssecure.smssecure.util.dualsim.SubscriptionManagerCompat;
import org.signal.libsignal.protocol.InvalidMessageException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Optional;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

import static org.smssecure.smssecure.TransportOption.Type;

/**
 * Fragment for displaying a message thread, as well as
 * composing/sending a new message into that thread.
 *
 * @author Moxie Marlinspike
 *
 */
@AndroidEntryPoint
public class ConversationScreenFragment extends Fragment
    implements ConversationFragment.ConversationFragmentListener,
               AttachmentManager.AttachmentListener,
               RecipientsModifiedListener,
               OnKeyboardShownListener,
               ComposeText.MediaListener
{
  private static final String TAG = ConversationScreenFragment.class.getSimpleName();

  static final String RECIPIENTS_ARGUMENT        = "conversation.recipient_ids";
  static final String THREAD_ID_ARGUMENT         = "conversation.thread_id";
  static final String IS_ARCHIVED_ARGUMENT       = "conversation.is_archived";
  static final String DISTRIBUTION_TYPE_ARGUMENT = "conversation.distribution_type";
  static final String TIMING_ARGUMENT            = "conversation.timing";
  static final String LAST_SEEN_ARGUMENT         = "conversation.last_seen";
  static final String PAYLOAD_TOKEN_ARGUMENT     = "conversation.payload_token";
  static final String PAYLOAD_OWNER              = "conversation-screen";
  private static final String RECIPIENTS_EXTRA        = RECIPIENTS_ARGUMENT;
  private static final String THREAD_ID_EXTRA         = THREAD_ID_ARGUMENT;
  private static final String IS_ARCHIVED_EXTRA       = IS_ARCHIVED_ARGUMENT;
  private static final String DISTRIBUTION_TYPE_EXTRA = DISTRIBUTION_TYPE_ARGUMENT;
  private static final String TIMING_EXTRA            = TIMING_ARGUMENT;
  private static final String TEXT_EXTRA         = "draft_text";

  private Intent screenIntent;

  static Bundle arguments(@NonNull long[] recipientIds, long threadId, int distributionType,
                          boolean archived, long timing, long lastSeen,
                          @Nullable String payloadToken) {
    Bundle arguments = new Bundle();
    arguments.putLongArray(RECIPIENTS_ARGUMENT, recipientIds.clone());
    arguments.putLong(THREAD_ID_ARGUMENT, threadId);
    arguments.putInt(DISTRIBUTION_TYPE_ARGUMENT, distributionType);
    arguments.putBoolean(IS_ARCHIVED_ARGUMENT, archived);
    arguments.putLong(TIMING_ARGUMENT, timing);
    arguments.putLong(LAST_SEEN_ARGUMENT, lastSeen);
    if (payloadToken != null) arguments.putString(PAYLOAD_TOKEN_ARGUMENT, payloadToken);
    requireValidArguments(arguments);
    return arguments;
  }

  static Bundle arguments(@NonNull Intent intent, @Nullable String payloadToken) {
    return arguments(intent.getLongArrayExtra(ConversationActivity.RECIPIENTS_EXTRA),
                     intent.getLongExtra(ConversationActivity.THREAD_ID_EXTRA, -1L),
                     intent.getIntExtra(ConversationActivity.DISTRIBUTION_TYPE_EXTRA,
                                        ThreadDatabase.DistributionTypes.DEFAULT),
                     intent.getBooleanExtra(ConversationActivity.IS_ARCHIVED_EXTRA, false),
                     intent.getLongExtra(ConversationActivity.TIMING_EXTRA, 0L),
                     intent.getLongExtra(ConversationActivity.LAST_SEEN_EXTRA, 0L),
                     payloadToken);
  }

  static void requireValidArguments(@NonNull Bundle arguments) {
    long[] recipientIds = androidx.core.os.BundleCompat.getSerializable(
        arguments, RECIPIENTS_ARGUMENT, long[].class);
    if (recipientIds == null || recipientIds.length == 0) {
      throw new SecurityException("Conversation recipient IDs are required");
    }
    for (long recipientId : recipientIds) {
      if (recipientId <= 0L) throw new SecurityException("Invalid conversation recipient ID");
    }
    Long threadId = androidx.core.os.BundleCompat.getSerializable(
      arguments, THREAD_ID_ARGUMENT, Long.class);
    Integer distributionType = androidx.core.os.BundleCompat.getSerializable(
      arguments, DISTRIBUTION_TYPE_ARGUMENT, Integer.class);
    Boolean archived = androidx.core.os.BundleCompat.getSerializable(
      arguments, IS_ARCHIVED_ARGUMENT, Boolean.class);
    Long timing = androidx.core.os.BundleCompat.getSerializable(
      arguments, TIMING_ARGUMENT, Long.class);
    Long lastSeen = androidx.core.os.BundleCompat.getSerializable(
      arguments, LAST_SEEN_ARGUMENT, Long.class);
    if (threadId == null || threadId < -1L) {
      throw new SecurityException("Invalid conversation thread ID");
    }
    if (distributionType == null || archived == null || timing == null || timing < 0L ||
      lastSeen == null || lastSeen < 0L) {
      throw new SecurityException("Invalid conversation primitive arguments");
    }
    String payloadToken = arguments.containsKey(PAYLOAD_TOKEN_ARGUMENT)
      ? androidx.core.os.BundleCompat.getSerializable(
        arguments, PAYLOAD_TOKEN_ARGUMENT, String.class)
      : null;
    if (arguments.containsKey(PAYLOAD_TOKEN_ARGUMENT) &&
      (payloadToken == null || payloadToken.isEmpty())) {
      throw new SecurityException("Invalid conversation payload token");
    }
  }

  private static Intent intentFromArguments(@NonNull Bundle arguments) {
    requireValidArguments(arguments);
    return new Intent()
        .putExtra(RECIPIENTS_ARGUMENT, arguments.getLongArray(RECIPIENTS_ARGUMENT))
        .putExtra(THREAD_ID_ARGUMENT, arguments.getLong(THREAD_ID_ARGUMENT, -1L))
        .putExtra(DISTRIBUTION_TYPE_ARGUMENT,
                  arguments.getInt(DISTRIBUTION_TYPE_ARGUMENT,
                                   ThreadDatabase.DistributionTypes.DEFAULT))
        .putExtra(IS_ARCHIVED_ARGUMENT, arguments.getBoolean(IS_ARCHIVED_ARGUMENT, false))
        .putExtra(TIMING_ARGUMENT, arguments.getLong(TIMING_ARGUMENT, 0L))
        .putExtra(LAST_SEEN_ARGUMENT, arguments.getLong(LAST_SEEN_ARGUMENT, 0L));
  }

  private PassphraseRequiredActionBarActivity hostActivity() {
    return (PassphraseRequiredActionBarActivity) requireActivity();
  }

  private ConversationScreenHost screenHost() {
    return (ConversationScreenHost) requireActivity();
  }

  private Intent getIntent() {
    return screenIntent;
  }

  private boolean isFinishing() {
    return screenHost().isConversationHostFinishing();
  }

  private void finish() {
    screenHost().finishConversationScreen();
  }

  private ActionBar getSupportActionBar() {
    return hostActivity().getSupportActionBar();
  }

  private MenuInflater getMenuInflater() {
    return hostActivity().getMenuInflater();
  }

  private void supportInvalidateOptionsMenu() {
    hostActivity().supportInvalidateOptionsMenu();
  }

  private TypedArray obtainStyledAttributes(int[] attributes) {
    return requireContext().obtainStyledAttributes(attributes);
  }

  private void setSystemBarColors(int statusBarColor, int navigationBarColor) {
    hostActivity().setSystemBarColors(statusBarColor, navigationBarColor);
  }

  private void startActivitySceneTransition(Intent intent, View sharedView, String transitionName) {
    hostActivity().startActivitySceneTransition(intent, sharedView, transitionName);
  }

  private   UnlockSession         unlockSession;
  protected ComposeText           composeText;
  private   AnimatingToggle       buttonToggle;
  private   SendButton            sendButton;
  private   ImageButton           attachButton;
  protected ConversationTitleView titleView;
  private   TextView              charactersLeft;
  private   ConversationFragment  fragment;
  private   Button                unblockButton;
  private   InputAwareLayout      container;
  private   View                  composePanel;
  private   View                  composeBubble;

  private   AttachmentTypeSelectorAdapter attachmentAdapter;
  private   AttachmentManager             attachmentManager;
  private   BroadcastReceiver             securityUpdateReceiver;
  private   UnlockSession                 externalMediaSession;
  private   Uri                           externalMediaGrantUri;
  private final ActivityResultLauncher<Intent> imagePicker = registerForActivityResult(
      new ActivityResultContracts.StartActivityForResult(), result -> handleMediaResult(result, MediaType.IMAGE));
  private final ActivityResultLauncher<Intent> videoPicker = registerForActivityResult(
      new ActivityResultContracts.StartActivityForResult(), result -> handleMediaResult(result, MediaType.VIDEO));
  private final ActivityResultLauncher<Intent> audioPicker = registerForActivityResult(
      new ActivityResultContracts.StartActivityForResult(), result -> handleMediaResult(result, MediaType.AUDIO));
  private final ActivityResultLauncher<Intent> contactInfoPicker = registerForActivityResult(
      new ActivityResultContracts.StartActivityForResult(), result -> {
        Intent data = result.getData();
        if (result.getResultCode() == android.app.Activity.RESULT_OK && data != null) addAttachmentContactInfo(data.getData());
      });
  private final ActivityResultLauncher<Intent> photoCapture = registerForActivityResult(
      new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getResultCode() == android.app.Activity.RESULT_OK && attachmentManager.getCaptureUri() != null) {
          setMedia(attachmentManager.getCaptureUri(), MediaType.IMAGE);
        }
      });
  private final ActivityResultLauncher<Intent> addContact = registerForActivityResult(
      new ActivityResultContracts.StartActivityForResult(), this::handleAddContactResult);
  private final ActivityResultLauncher<Intent> externalMediaViewer = registerForActivityResult(
      new ActivityResultContracts.StartActivityForResult(), result -> {
        if (externalMediaSession != null) {
          try {
            externalMediaSession.use(ignored -> null);
          } catch (Exception error) {
            Log.w(TAG, "Discarding external media result from a stale unlock generation");
          }
        }
        clearExternalMediaGrant();
      });

  private   Stub<EmojiDrawer>             emojiDrawerStub;
  private   EmojiToggle                   emojiToggle;
  private   OnBackPressedCallback         backPressedCallback;

  private Recipients recipients;
  private long       threadId;
  private int        distributionType;
  private boolean    isEncryptedConversation;
  private boolean    isSecureSmsDestination;
  private boolean    archived;
  private boolean    isMmsEnabled = true;

  private DynamicLanguage dynamicLanguage = new DynamicLanguage();

  private List<SubscriptionInfoCompat> activeSubscriptions;
  private final List<TaskHandle> callbackTasks = new ArrayList<>();
  private final List<PendingFutureTask> futureTasks = new ArrayList<>();
  private boolean destroyed;
  private int recipientPreferencesGeneration;
  @Inject ConversationScreenRepository screenRepository;
  @Inject ConversationPayloadStore payloadStore;
  private ConversationScreenViewModel screenViewModel;

  private static final class PendingFutureTask {
    private final TaskHandle           task;
    private final SettableFuture<Long> future;

    private PendingFutureTask(TaskHandle task, SettableFuture<Long> future) {
      this.task   = task;
      this.future = future;
    }
  }

  @Override
  public void onCreate(@Nullable Bundle state) {
    super.onCreate(state);
    setHasOptionsMenu(true);
    this.unlockSession = UnlockSession.capture();
    this.screenViewModel = new ViewModelProvider(this).get(ConversationScreenViewModel.class);
    this.activeSubscriptions = SubscriptionManagerCompat.from(requireContext()).getActiveSubscriptionInfoList();
    this.screenIntent = intentFromArguments(requireArguments());
    consumePayload(requireArguments());
  }

  private void consumePayload(@NonNull Bundle arguments) {
    String token = arguments.getString(PAYLOAD_TOKEN_ARGUMENT);
    arguments.remove(PAYLOAD_TOKEN_ARGUMENT);
    if (token == null) return;

    try {
      ConversationPayload payload = payloadStore.consume(token, PAYLOAD_OWNER);
      if (!java.util.Arrays.equals(payload.getRecipientIds(),
                                   arguments.getLongArray(RECIPIENTS_ARGUMENT)) ||
          payload.getThreadId() != arguments.getLong(THREAD_ID_ARGUMENT, -1L) ||
          payload.getDistributionType() != arguments.getInt(
              DISTRIBUTION_TYPE_ARGUMENT, ThreadDatabase.DistributionTypes.DEFAULT)) {
        throw new SecurityException("Conversation payload destination mismatch");
      }
      if (payload.getText() != null) screenIntent.putExtra(TEXT_EXTRA, payload.getText());
      if (payload.getMedia() != null) {
        screenIntent.setDataAndType(payload.getMedia(), payload.getMediaType());
      }
    } catch (ConversationPayloadStore.InvalidPayloadException error) {
      throw new SecurityException("Invalid conversation payload token", error);
    }
  }

  @Override
  public @NonNull View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                                    @Nullable Bundle state) {
    return inflater.inflate(R.layout.conversation_activity, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
    super.onViewCreated(view, state);
    destroyed = false;
    Log.w(TAG, "onViewCreated()");
    fragment = (ConversationFragment) getChildFragmentManager().findFragmentById(R.id.fragment_content);
    if (fragment == null) {
      fragment = new ConversationFragment();
      Bundle fragmentArguments = new Bundle();
      fragmentArguments.putSerializable(PassphraseRequiredActionBarActivity.LOCALE_EXTRA,
                                        dynamicLanguage.getCurrentLocale());
      fragmentArguments.putLongArray(RECIPIENTS_ARGUMENT,
                 screenIntent.getLongArrayExtra(RECIPIENTS_EXTRA));
      fragmentArguments.putLong(THREAD_ID_ARGUMENT,
                screenIntent.getLongExtra(THREAD_ID_EXTRA, -1L));
      fragmentArguments.putLong(LAST_SEEN_ARGUMENT,
                screenIntent.getLongExtra(LAST_SEEN_ARGUMENT, -1L));
      fragment.setArguments(fragmentArguments);
      getChildFragmentManager().beginTransaction().replace(R.id.fragment_content, fragment).commitNow();
    }

    initializeReceivers();
    initializeActionBar();
    initializeViews();
    LifecycleStateCollector.collect(getViewLifecycleOwner(), screenViewModel.getState(), this::renderScreenState);
    initializeBackPressedCallback();
    initializeResources();
    initializeSecurity();
    updateRecipientPreferences();
    initializeDraft();
  }

  void updateArguments(@NonNull Bundle arguments) {
    requireValidArguments(arguments);
    Log.w(TAG, "updateArguments()");

    if (isFinishing()) {
      Log.w(TAG, "Host is finishing...");
      return;
    }

    if (!Util.isEmpty(composeText) || attachmentManager.isAttachmentPresent()) {
      saveDraft();
      attachmentManager.clear();
      composeText.setText("");
    }

    setArguments(new Bundle(arguments));
    screenIntent = intentFromArguments(arguments);
    initializeResources();
    initializeSecurity();
    updateRecipientPreferences();
    initializeDraft();

    if (fragment != null) {
      fragment.updateArguments(screenIntent.getLongArrayExtra(RECIPIENTS_EXTRA),
               screenIntent.getLongExtra(THREAD_ID_EXTRA, -1L),
               screenIntent.getLongExtra(LAST_SEEN_ARGUMENT, -1L));
    }
  }

  public void clearSensitiveState() {
    if (fragment != null) fragment.clearSensitiveState();
    if (composeText != null) composeText.setText("");
    if (attachmentManager != null) attachmentManager.cleanup();
    clearExternalMediaGrant();
  }

    @Override
    public void displayMessageDetails(long messageId, long threadId, int transport,
                    long[] recipientIds) {
    Bundle arguments = MessageDetailsFragment.arguments(
      messageId, threadId, transport, recipientIds);
    ConversationListDestination.MESSAGE_DETAILS.requireAllowedArguments(arguments);
    androidx.navigation.fragment.NavHostFragment.findNavController(this)
      .navigate(ConversationListDestination.MESSAGE_DETAILS.getId(), arguments);
    }

  @Override
  public void onResume() {
    super.onResume();
    dynamicLanguage.onResume(requireActivity());

    initializeEnabledCheck();
    initializeMmsEnabledCheck();
    composeText.setTransport(sendButton.getSelectedTransport());

    titleView.setTitle(recipients);
    setActionBarColor(recipients.getColor());
    setBlockedUserState(recipients);
    calculateCharactersRemaining();

    MessageNotifier.setVisibleThread(threadId);
    markThreadAsRead();

    Log.w(TAG, "onResume() Finished: " + (System.currentTimeMillis() - getIntent().getLongExtra(TIMING_EXTRA, 0)));
  }

  @Override
  public void onPause() {
    super.onPause();
    MessageNotifier.setVisibleThread(-1L);
    fragment.setLastSeen(System.currentTimeMillis());
    markLastSeen();
    AudioSlidePlayer.stopAll();
  }

  @Override public void onConfigurationChanged(@NonNull Configuration newConfig) {
    Log.w(TAG, "onConfigurationChanged(" + newConfig.orientation + ")");
    super.onConfigurationChanged(newConfig);
    composeText.setTransport(sendButton.getSelectedTransport());

    if (emojiDrawerStub.resolved() && container.getCurrentInput() == emojiDrawerStub.get()) {
      container.hideAttachedInput(true);
    }
  }

  @Override
  public void onDestroyView() {
    destroyed = true;
    for (TaskHandle task : callbackTasks) task.cancel();
    callbackTasks.clear();
    for (PendingFutureTask pending : futureTasks) {
      pending.future.setException(new CancellationException("Conversation activity destroyed"));
      pending.task.cancel();
    }
    futureTasks.clear();
    saveDraft(false);
    if (recipients != null) recipients.removeListener(this);
    if (securityUpdateReceiver != null) requireContext().unregisterReceiver(securityUpdateReceiver);
    securityUpdateReceiver = null;
    super.onDestroyView();
  }

  private void trackCallbackTask(TaskHandle task) {
    if (destroyed) task.cancel();
    else           callbackTasks.add(task);
  }

  private void trackFutureTask(TaskHandle task, SettableFuture<Long> future) {
    if (destroyed) {
      future.setException(new CancellationException("Conversation activity destroyed"));
      task.cancel();
    } else {
      futureTasks.add(new PendingFutureTask(task, future));
    }
  }

  private <T> ConversationScreenRepository.Callback<T> screenCallback(Consumer<T> success,
                                                                       String failureMessage) {
    WeakReference<ConversationScreenFragment> owner = new WeakReference<>(this);
    return new ConversationScreenRepository.Callback<T>() {
      @Override public void onSuccess(T result) {
        ConversationScreenFragment screen = owner.get();
        if (screen != null && !screen.destroyed) success.accept(result);
      }

      @Override public void onFailure(Exception exception) {
        Log.w(TAG, failureMessage, exception);
      }
    };
  }

  private void handleMediaResult(ActivityResult result, MediaType mediaType) {
    Intent data = result.getData();
    if (result.getResultCode() != android.app.Activity.RESULT_OK || data == null || data.getData() == null) return;

    if (mediaType == MediaType.IMAGE && MediaUtil.isGif(MediaUtil.getMimeType(requireContext(), data.getData()))) {
      mediaType = MediaType.GIF;
    }
    setMedia(data.getData(), mediaType);
  }

  private void handleAddContactResult(ActivityResult result) {
    if (result.getResultCode() != android.app.Activity.RESULT_OK) return;

    recipients = RecipientFactory.getRecipientsForIds(requireContext(), recipients.getIds(), true);
    recipients.addListener(this);
    fragment.reloadList();
  }

  @Override
  public void startActivity(Intent intent) {
    try {
      if (intent.getStringExtra(Browser.EXTRA_APPLICATION_ID) != null) {
        intent.removeExtra(Browser.EXTRA_APPLICATION_ID);
      }
      super.startActivity(intent);
      Log.d(TAG, "Opened link: " + intent.getDataString());
    } catch (ActivityNotFoundException anfe) {
      Log.w(TAG, "No app found to view the link '" + intent.getDataString() + "', ignoring...");
      Toast.makeText(requireContext(), R.string.ConversationActivity_cant_open_link, Toast.LENGTH_SHORT).show();
    }
  }

  @Override
  public void onPrepareOptionsMenu(@NonNull Menu menu) {
    MenuInflater inflater = getMenuInflater();
    menu.clear();

    boolean isEncryptedForAllSubscriptionIdsConversation = useMasterSecret(
      secret -> SessionUtil.hasSession(requireContext(), secret, recipients.getPrimaryRecipient().getNumber(), activeSubscriptions),
      false);

    if (isSingleConversation() && isEncryptedConversation) {
      inflater.inflate(R.menu.conversation_secure_identity, menu);
      inflateSubMenuVerifyIdentity(menu);
      inflater.inflate(R.menu.conversation_secure_sms, menu.findItem(R.id.menu_security).getSubMenu());
      inflateSubMenuAbortSecureSession(menu);
    } else if (isSingleConversation() && !isEncryptedConversation) {
      inflater.inflate(R.menu.conversation_insecure_no_push, menu);
      inflater.inflate(R.menu.conversation_insecure, menu);
    }

    if (isSingleConversation() && !isEncryptedForAllSubscriptionIdsConversation) {
      inflateSubMenuStartSecureSession(menu);
    } else {
      MenuItem item = menu.findItem(R.id.menu_start_secure_session);
      if (item != null) item.setVisible(false);
    }

    if (isSingleConversation()) {
      inflater.inflate(R.menu.conversation_callable, menu);
    } else if (isGroupConversation()) {
      inflater.inflate(R.menu.conversation_group_options, menu);

      if (!isPushGroupConversation()) {
        inflater.inflate(R.menu.conversation_mms_group_options, menu);
        if (distributionType == ThreadDatabase.DistributionTypes.BROADCAST) {
          menu.findItem(R.id.menu_distribution_broadcast).setChecked(true);
        } else {
          menu.findItem(R.id.menu_distribution_conversation).setChecked(true);
        }
      }
    }

    inflater.inflate(R.menu.conversation, menu);

    if (recipients != null && recipients.isMuted()) inflater.inflate(R.menu.conversation_muted, menu);
    else                                            inflater.inflate(R.menu.conversation_unmuted, menu);

    if (isSingleConversation() && getRecipients().getPrimaryRecipient().getContactUri() == null) {
      inflater.inflate(R.menu.conversation_add_to_contacts, menu);
    }

    if (archived) menu.findItem(R.id.menu_archive_conversation)
                      .setTitle(R.string.conversation__menu_unarchive_conversation);

    super.onPrepareOptionsMenu(menu);
  }

  @Override
  @SuppressLint("NonConstantResourceId")
  public boolean onOptionsItemSelected(MenuItem item) {
    super.onOptionsItemSelected(item);
    int itemId = item.getItemId();
    if      (itemId == R.id.menu_call)                          { handleDial(getRecipients().getPrimaryRecipient()); return true; }
    else if (itemId == R.id.menu_delete_conversation)           { handleDeleteConversation();                        return true; }
    else if (itemId == R.id.menu_archive_conversation)          { handleArchiveConversation();                       return true; }
    else if (itemId == R.id.menu_add_attachment)                { handleAddAttachment();                             return true; }
    else if (itemId == R.id.menu_view_media)                    { handleViewMedia();                                 return true; }
    else if (itemId == R.id.menu_add_to_contacts)               { handleAddToContacts();                             return true; }
    else if (itemId == R.id.menu_start_secure_session)          { handleStartSecureSession();                        return true; }
    else if (itemId == R.id.menu_start_secure_session_dual_sim) { handleStartSecureSession();                        return true; }
    else if (itemId == R.id.menu_abort_session)                 { handleAbortSecureSession();                        return true; }
    else if (itemId == R.id.menu_abort_session_dual_sim)        { handleAbortSecureSession();                        return true; }
    else if (itemId == R.id.menu_verify_identity)               { handleVerifyIdentity();                            return true; }
    else if (itemId == R.id.menu_verify_identity_dual_sim)      { handleVerifyIdentity();                            return true; }
    else if (itemId == R.id.menu_group_recipients)              { handleDisplayGroupRecipients();                    return true; }
    else if (itemId == R.id.menu_distribution_broadcast)        { handleDistributionBroadcastEnabled(item);          return true; }
    else if (itemId == R.id.menu_distribution_conversation)     { handleDistributionConversationEnabled(item);       return true; }
    else if (itemId == R.id.menu_invite)                        { handleInviteLink();                                return true; }
    else if (itemId == R.id.menu_mute_notifications)            { handleMuteNotifications();                         return true; }
    else if (itemId == R.id.menu_unmute_notifications)          { handleUnmuteNotifications();                       return true; }
    else if (itemId == R.id.menu_conversation_settings)         { handleConversationSettings();                      return true; }
    else if (itemId == android.R.id.home)                       { handleReturnToConversationList();                  return true; }

    return false;
  }

  @Override
  public void onKeyboardShown() {
    emojiToggle.setToEmoji();
  }

  private void inflateSubMenuVerifyIdentity(Menu menu) {
    if (Build.VERSION.SDK_INT >= 22 && activeSubscriptions.size() > 1) {
      menu.findItem(R.id.menu_verify_identity).setVisible(false);
      SubMenu identitiesMenu = menu.findItem(R.id.menu_verify_identity_dual_sim).getSubMenu();

      for (SubscriptionInfoCompat subscriptionInfo : activeSubscriptions) {
        final int subscriptionId = subscriptionInfo.getSubscriptionId();
        identitiesMenu.add(Menu.NONE, Menu.NONE, Menu.NONE, subscriptionInfo.getDisplayName())
                      .setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
                        @Override
                        public boolean onMenuItemClick(MenuItem item) {
                          handleVerifyIdentity(subscriptionId);
                          return true;
                        }
                      });
      }
    } else {
      menu.findItem(R.id.menu_verify_identity_dual_sim).setVisible(false);
    }
  }

  private void inflateSubMenuStartSecureSession(Menu menu) {
    if (Build.VERSION.SDK_INT >= 22 && activeSubscriptions.size() > 1) {
      menu.findItem(R.id.menu_start_secure_session).setVisible(false);
      SubMenu startSecureSessionMenu = menu.findItem(R.id.menu_start_secure_session_dual_sim).getSubMenu();

      for (SubscriptionInfoCompat subscriptionInfo : activeSubscriptions) {
        final int subscriptionId = subscriptionInfo.getSubscriptionId();

        if (!useMasterSecret(secret -> SessionUtil.hasSession(requireContext(), secret,
          recipients.getPrimaryRecipient().getNumber(), subscriptionId), false)) {

          startSecureSessionMenu.add(Menu.NONE, Menu.NONE, Menu.NONE, subscriptionInfo.getDisplayName())
                                .setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
                                  @Override
                                  public boolean onMenuItemClick(MenuItem item) {
                                    handleStartSecureSession(subscriptionId);
                                    return true;
                                  }
                                });
        }
      }
    } else {
      menu.findItem(R.id.menu_start_secure_session_dual_sim).setVisible(false);
    }
  }

  private void inflateSubMenuAbortSecureSession(Menu menu) {
    if (Build.VERSION.SDK_INT >= 22 && activeSubscriptions.size() > 1) {
      menu.findItem(R.id.menu_abort_session).setVisible(false);
      SubMenu abortSecureSessionMenu = menu.findItem(R.id.menu_abort_session_dual_sim).getSubMenu();

      for (SubscriptionInfoCompat subscriptionInfo : activeSubscriptions) {
        final int subscriptionId = subscriptionInfo.getSubscriptionId();
        Log.w(TAG, "inflateSubMenuAbortSecureSession( " + subscriptionId + " )");

        if (useMasterSecret(secret -> SessionUtil.hasSession(requireContext(), secret,
          recipients.getPrimaryRecipient().getNumber(), subscriptionId), false)) {
          Log.w(TAG, "Subscription ID " + subscriptionId + " has a secure session.");

          abortSecureSessionMenu.add(Menu.NONE, Menu.NONE, Menu.NONE, subscriptionInfo.getDisplayName())
                                .setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
                                  @Override
                                  public boolean onMenuItemClick(MenuItem item) {
                                    handleAbortSecureSession(subscriptionId);
                                    return true;
                                  }
                                });
        }
      }
    } else {
      menu.findItem(R.id.menu_abort_session).setVisible(false);
    }
  }

  //////// Event Handlers

  private void handleReturnToConversationList() {
    finish();
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    Permissions.onRequestPermissionsResult(requireActivity(), requestCode, permissions, grantResults);
  }

  private void handleMuteNotifications() {
    MuteDialog.show(requireContext(), new MuteDialog.MuteSelectionListener() {
      @Override
      public void onMuted(final long until) {
        recipients.setMuted(until);
        trackCallbackTask(screenRepository.setMuted(recipients.getIds(), until,
            screenCallback(ignored -> {}, "Unable to mute conversation notifications")));
      }
    });
  }

  private void handleConversationSettings() {
    titleView.performClick();
  }

  private void handleUnmuteNotifications() {
    recipients.setMuted(0);
    trackCallbackTask(screenRepository.setMuted(recipients.getIds(), 0,
        screenCallback(ignored -> {}, "Unable to unmute conversation notifications")));
  }

  private void handleUnblock() {
    new AlertDialog.Builder(requireContext())
        .setTitle(R.string.RecipientPreferenceActivity_unblock_this_contact_question)
        .setMessage(R.string.RecipientPreferenceActivity_are_you_sure_you_want_to_unblock_this_contact)
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(R.string.RecipientPreferenceActivity_unblock, new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface dialog, int which) {
            recipients.setBlocked(false);
            screenViewModel.setBlocked(false);
            trackCallbackTask(screenRepository.setBlocked(recipients.getIds(), false,
                screenCallback(ignored -> {}, "Unable to unblock conversation recipient")));
          }
        }).show();
  }

  private void handleInviteLink() {
    composeText.appendInvite(getString(R.string.ConversationActivity_install_smssecure, "https://silence.im"));
  }

  private void handleVerifyIdentity() {
    if (activeSubscriptions.size() < 2) {
      int subscriptionId = activeSubscriptions.get(0).getSubscriptionId();
      handleVerifyIdentity(subscriptionId);
    }
  }

  private void handleVerifyIdentity(int subscriptionId) {
    screenHost().openVerifyIdentity(getRecipients().getPrimaryRecipient().getRecipientId(), subscriptionId);
  }

  private void handleStartSecureSession() {
    if (activeSubscriptions.size() < 2) {
      int subscriptionId = activeSubscriptions.get(0).getSubscriptionId();
      handleStartSecureSession(subscriptionId);
    }
  }

  private void handleStartSecureSession(final int subscriptionId) {
    if (getRecipients() == null) {
      Toast.makeText(requireContext(), getString(R.string.ConversationActivity_invalid_recipient),
                     Toast.LENGTH_LONG).show();
      return;
    }

    if (TelephonyUtil.isMyPhoneNumber(requireContext(), recipients.getPrimaryRecipient().getNumber())) {
      Toast.makeText(requireContext(), getString(R.string.ConversationActivity_recipient_self),
              Toast.LENGTH_LONG).show();
      return;
    }

    final Recipients recipients = getRecipients();
    final Recipient recipient   = recipients.getPrimaryRecipient();
    String recipientName        = (recipient.getName() == null ? recipient.getNumber() : recipient.getName());

    AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
    builder.setTitle(R.string.ConversationActivity_initiate_secure_session_question);
    builder.setIconAttribute(R.attr.dialog_info_icon);
    builder.setCancelable(true);
    builder.setMessage(String.format(getString(R.string.ConversationActivity_initiate_secure_session_with_s_question),
                       recipientName));
    builder.setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
      @Override
      public void onClick(DialogInterface dialog, int which) {
        useMasterSecret(secret -> {
          KeyExchangeInitiator.initiate(requireContext(), secret, recipients, true, subscriptionId);
          return null;
        }, null);
        if (threadId == -1) {
          trackCallbackTask(screenRepository.getOrCreateThread(recipients.getIds(), distributionType,
              screenCallback(allocatedThreadId -> {
                Log.w(TAG, "Refreshing thread " + allocatedThreadId + "...");
                sendComplete(allocatedThreadId);
              }, "Unable to allocate conversation thread")));
        } else {
          Log.w(TAG, "Refreshing thread " + threadId + "...");
          sendComplete(threadId);
        }
      }
    });

    builder.setNegativeButton(R.string.no, null);
    builder.show();
  }

  private void handleAbortSecureSession() {
    if (activeSubscriptions.size() < 2) {
      int subscriptionId = activeSubscriptions.get(0).getSubscriptionId();
      handleAbortSecureSession(subscriptionId);
    }
  }

  private void handleAbortSecureSession(final int subscriptionId) {
    AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
    builder.setTitle(R.string.ConversationActivity_abort_secure_session_confirmation);
    builder.setIconAttribute(R.attr.dialog_alert_icon);
    builder.setCancelable(true);
    builder.setMessage(R.string.ConversationActivity_are_you_sure_that_you_want_to_abort_this_secure_session_question);
    builder.setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
      @Override
      public void onClick(DialogInterface dialog, int which) {
        if (isSingleConversation()) {
          Recipients recipients = getRecipients();
          useMasterSecret(secret -> {
            KeyExchangeInitiator.abort(requireContext(), secret, recipients, subscriptionId);
            return null;
          }, null);

          if (threadId == -1) {
            trackCallbackTask(screenRepository.getOrCreateThread(recipients.getIds(), distributionType,
                screenCallback(allocatedThreadId -> {
                  Log.w(TAG, "Refreshing thread " + allocatedThreadId + "...");
                  sendComplete(allocatedThreadId);
                }, "Unable to allocate conversation thread")));
          } else {
            Log.w(TAG, "Refreshing thread " + threadId + "...");
            sendComplete(threadId);
          }
        }
      }
    });
    builder.setNegativeButton(R.string.no, null);
    builder.show();
  }

  private void handleViewMedia() {
    ((MediaNavigationHost) requireActivity()).openMediaOverview(
        threadId, recipients.getPrimaryRecipient().getRecipientId());
  }

  private void launchExternalMedia(long partRowId, long partUniqueId, String contentType) {
    clearExternalMediaGrant();
    externalMediaSession = UnlockSession.capture();
    try {
      externalMediaSession.use(ignored -> null);
      Uri localUri = PartAuthority.getAttachmentDataUri(new AttachmentId(partRowId, partUniqueId));
      externalMediaGrantUri = PartAuthority.getAttachmentPublicUri(localUri);
        Intent intent = new Intent(Intent.ACTION_VIEW)
          .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
          .setDataAndType(externalMediaGrantUri, contentType);
        intent.setClipData(ClipData.newRawUri("media", externalMediaGrantUri));
      externalMediaViewer.launch(intent);
    } catch (ActivityNotFoundException error) {
      clearExternalMediaGrant();
      Toast.makeText(requireContext(), R.string.ConversationItem_unable_to_open_media,
          Toast.LENGTH_LONG).show();
    } catch (Exception error) {
      clearExternalMediaGrant();
      Log.w(TAG, "Unable to open media for the current unlock generation", error);
    }
  }

  private void clearExternalMediaGrant() {
    if (externalMediaGrantUri != null) {
      requireContext().revokeUriPermission(
          externalMediaGrantUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }
    externalMediaGrantUri = null;
    externalMediaSession = null;
  }

  private void handleDistributionBroadcastEnabled(MenuItem item) {
    distributionType = ThreadDatabase.DistributionTypes.BROADCAST;
    screenViewModel.setDistributionType(distributionType);
    item.setChecked(true);

    if (threadId != -1) {
      trackCallbackTask(screenRepository.setDistributionType(
          threadId, ThreadDatabase.DistributionTypes.BROADCAST,
          screenCallback(ignored -> {}, "Unable to set broadcast distribution type")));
    }
  }

  private void handleDistributionConversationEnabled(MenuItem item) {
    distributionType = ThreadDatabase.DistributionTypes.CONVERSATION;
    screenViewModel.setDistributionType(distributionType);
    item.setChecked(true);

    if (threadId != -1) {
      trackCallbackTask(screenRepository.setDistributionType(
          threadId, ThreadDatabase.DistributionTypes.CONVERSATION,
          screenCallback(ignored -> {}, "Unable to set conversation distribution type")));
    }
  }

  private void handleDial(Recipient recipient) {
    try {
      if (recipient == null) return;

      Intent dialIntent = new Intent(Intent.ACTION_DIAL,
                              Uri.parse("tel:" + recipient.getNumber()));
      startActivity(dialIntent);
    } catch (ActivityNotFoundException anfe) {
      Log.w(TAG, anfe);
      Dialogs.showAlertDialog(requireContext(),
                           getString(R.string.ConversationActivity_calls_not_supported),
                           getString(R.string.ConversationActivity_this_device_does_not_appear_to_support_dial_actions));
    }
  }

  private void handleDisplayGroupRecipients() {
    new GroupMembersDialog(requireContext(), getRecipients()).display();
  }

  private void handleDeleteConversation() {
    AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
    builder.setTitle(R.string.ConversationActivity_delete_thread_question);
    builder.setIconAttribute(R.attr.dialog_alert_icon);
    builder.setCancelable(true);
    builder.setMessage(R.string.ConversationActivity_this_will_permanently_delete_all_messages_in_this_conversation);
    builder.setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
      @Override
      public void onClick(DialogInterface dialog, int which) {
        if (threadId > 0) {
          trackCallbackTask(screenRepository.deleteThread(threadId,
              screenCallback(ignored -> finishAfterConversationRemoval(),
                             "Unable to delete conversation")));
        } else {
          finishAfterConversationRemoval();
        }
      }
    });

    builder.setNegativeButton(android.R.string.cancel, null);
    builder.show();
  }

  private void handleArchiveConversation() {
    if (threadId > 0) {
      trackCallbackTask(screenRepository.setArchived(threadId, !archived,
          screenCallback(ignored -> finishAfterConversationRemoval(),
                         "Unable to update conversation archive state")));
    } else {
      finishAfterConversationRemoval();
    }
  }

  private void finishAfterConversationRemoval() {
    composeText.getText().clear();
    threadId = -1;
    screenViewModel.setThreadId(threadId);
    finish();
  }

  private void handleAddToContacts() {
    try {
      final Intent intent = new Intent(Intent.ACTION_INSERT_OR_EDIT);
      intent.putExtra(ContactsContract.Intents.Insert.PHONE, recipients.getPrimaryRecipient().getNumber());
      intent.setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE);
      addContact.launch(intent);
    } catch (ActivityNotFoundException e) {
      Log.w(TAG, e);
    }
  }

  private void handleAddAttachment() {
    if (this.isMmsEnabled) {
      new AlertDialog.Builder(requireContext()).setAdapter(attachmentAdapter, new AttachmentTypeListener())
                                          .show();
    } else {
      handleManualMmsRequired();
    }
  }

  private void handleManualMmsRequired() {
    Toast.makeText(requireContext(), R.string.MmsDownloader_error_reading_mms_settings, Toast.LENGTH_LONG).show();
    PromptMmsDialogFragment.show(requireActivity().getSupportFragmentManager());
  }

  ///// Initializers

  private void initializeDraft() {
    final String    draftText      = getIntent().getStringExtra(TEXT_EXTRA);
    final Uri       draftMedia     = getIntent().getData();
    final MediaType draftMediaType = MediaType.from(getIntent().getType());

    if (draftText != null)                            composeText.setText(draftText);
    if (draftMedia != null && draftMediaType != null) setMedia(draftMedia, draftMediaType);

    if (draftText == null && draftMedia == null && draftMediaType == null) {
      initializeDraftFromDatabase();
    } else {
      updateToggleButtonState();
    }
  }

  private void initializeEnabledCheck() {
    boolean enabled = !(isPushGroupConversation() && !isActiveGroup());
    composeText.setEnabled(enabled);
    sendButton.setEnabled(enabled);
  }

  private void initializeDraftFromDatabase() {
    TaskHandle task = screenRepository.restoreDrafts(threadId,
      new ConversationUnlockCapability(unlockSession),
        screenCallback(this::restoreDrafts, "Unable to restore conversation drafts"));
    trackCallbackTask(task);
  }

  private void restoreDrafts(List<Draft> drafts) {
    for (Draft draft : drafts) {
      if (draft.getType().equals(Draft.TEXT)) {
        composeText.setText(draft.getValue());
      } else if (draft.getType().equals(Draft.IMAGE)) {
        setMedia(Uri.parse(draft.getValue()), MediaType.IMAGE);
      } else if (draft.getType().equals(Draft.AUDIO)) {
        setMedia(Uri.parse(draft.getValue()), MediaType.AUDIO);
      } else if (draft.getType().equals(Draft.VIDEO)) {
        setMedia(Uri.parse(draft.getValue()), MediaType.VIDEO);
      }
    }
    updateToggleButtonState();
  }

  private void initializeSecurity() {
    Recipient    primaryRecipient = getRecipients() == null ? null : getRecipients().getPrimaryRecipient();
    boolean      isMediaMessage   = !recipients.isSingleRecipient() || attachmentManager.isAttachmentPresent();

    isSecureSmsDestination = isSingleConversation() && useMasterSecret(
      secret -> SessionUtil.hasAtLeastOneSession(requireContext(), secret, primaryRecipient.getNumber(), activeSubscriptions),
      false);

    if (isSecureSmsDestination) {
      this.isEncryptedConversation = true;
    } else {
      this.isEncryptedConversation = false;
    }
    screenViewModel.setSecurity(isSecureSmsDestination, isEncryptedConversation);

    sendButton.resetAvailableTransports(isMediaMessage);
    if (!isSecureSmsDestination      ) sendButton.disableTransport(Type.SECURE_SMS);
    if (recipients.isGroupRecipient()) sendButton.disableTransport(Type.INSECURE_SMS);

    if (Build.VERSION.SDK_INT >= 22) {
            List<Integer> subscriptionsWithoutSession = useMasterSecret(
              secret -> SessionUtil.getSubscriptionIdWithoutSession(requireContext(), secret,
                primaryRecipient.getNumber(), activeSubscriptions),
                Collections.emptyList());
            sendButton.disableTransport(Type.SECURE_SMS, subscriptionsWithoutSession);
    }

    if (isSecureSmsDestination) {
      sendButton.setDefaultTransport(Type.SECURE_SMS);
    } else {
      sendButton.setDefaultTransport(Type.INSECURE_SMS);
    }

    calculateCharactersRemaining();
    supportInvalidateOptionsMenu();
  }

  private void updateRecipientPreferences() {
    if (recipients.getPrimaryRecipient() != null &&
        recipients.getPrimaryRecipient().getContactUri() != null)
    {
      int generation = ++recipientPreferencesGeneration;
      TaskHandle task = screenRepository.loadDefaultSubscription(recipients.getIds(),
          screenCallback(subscriptionId -> {
            if (generation == recipientPreferencesGeneration) {
              updateDefaultSubscriptionId(subscriptionId.isPresent()
                  ? subscriptionId
                  : SubscriptionManagerCompat.getDefaultMessagingSubscriptionId());
            }
          }, "Unable to load recipient preferences"));
      trackCallbackTask(task);
    }
  }

  private void updateDefaultSubscriptionId(Optional<Integer> defaultSubscriptionId) {
    Log.w(TAG, "updateDefaultSubscriptionId(" + defaultSubscriptionId.orElse(null) + ")");
    sendButton.setDefaultSubscriptionId(defaultSubscriptionId);
  }

  private void initializeMmsEnabledCheck() {
    screenViewModel.refreshMmsCapability();
  }

  private void renderScreenState(ConversationScreenUiState state) {
    isMmsEnabled = state.isMmsEnabled();
    if (state.getSentThreadId() != -1L) {
      long sentThreadId = state.getSentThreadId();
      screenViewModel.acknowledgeSendResult();
      sendComplete(sentThreadId);
    }
    if (state.getError() != ConversationScreenUiState.Error.NONE) {
      Log.w(TAG, "Conversation operation failed: " + state.getError());
      screenViewModel.acknowledgeError();
    }
  }

  private void initializeViews() {
    titleView       = (ConversationTitleView) getSupportActionBar().getCustomView();
    View root       = requireView();
    buttonToggle    = ViewUtil.findById(root, R.id.button_toggle);
    sendButton      = ViewUtil.findById(root, R.id.send_button);
    attachButton    = ViewUtil.findById(root, R.id.attach_button);
    composeText     = ViewUtil.findById(root, R.id.embedded_text_editor);
    charactersLeft  = ViewUtil.findById(root, R.id.space_left);
    emojiToggle     = ViewUtil.findById(root, R.id.emoji_toggle);
    emojiDrawerStub = new Stub<>((android.view.ViewStub) root.findViewById(R.id.emoji_drawer_stub),
                   EmojiDrawer.class);
    unblockButton   = ViewUtil.findById(root, R.id.unblock_button);
    composePanel    = ViewUtil.findById(root, R.id.bottom_panel);
    composeBubble   = ViewUtil.findById(root, R.id.compose_bubble);
    container       = ViewUtil.findById(root, R.id.layout_container);

    if (SilencePreferences.isEmojiDrawerDisabled(requireContext()))
      emojiToggle.setVisibility(View.GONE);

    container.addOnKeyboardShownListener(this);
    composeText.setMediaListener(this);

    int[]      attributes   = new int[]{R.attr.conversation_item_bubble_background};
    TypedArray colors       = obtainStyledAttributes(attributes);
    int        defaultColor = colors.getColor(0, Color.WHITE);
    composeBubble.getBackground().setColorFilter(BlendModeColorFilterCompat.createBlendModeColorFilterCompat(defaultColor, BlendModeCompat.MODULATE));
    colors.recycle();

    attachmentAdapter = new AttachmentTypeSelectorAdapter(requireContext());
    attachmentManager = new AttachmentManager(requireActivity(), this);

    SendButtonListener        sendButtonListener        = new SendButtonListener();
    ComposeKeyPressedListener composeKeyPressedListener = new ComposeKeyPressedListener();

    if (SilencePreferences.isSystemEmojiPreferred(requireContext())) {
      emojiToggle.setVisibility(View.GONE);
    } else {
      emojiToggle.attach(emojiDrawerStub.get());
      emojiToggle.setOnClickListener(new EmojiToggleListener());
      emojiDrawerStub.get().setEmojiEventListener(new EmojiEventListener() {
        @Override public void onKeyEvent(KeyEvent keyEvent) {
          composeText.dispatchKeyEvent(keyEvent);
        }

        @Override public void onEmojiSelected(String emoji) {
          composeText.insertEmoji(emoji);
        }
      });
    }

    composeText.setOnEditorActionListener(sendButtonListener);
    attachButton.setOnClickListener(new AttachButtonListener());
    attachButton.setOnLongClickListener(new AttachButtonLongClickListener());
    sendButton.setOnClickListener(sendButtonListener);
    sendButton.setEnabled(true);
    sendButton.addOnTransportChangedListener(new OnTransportChangedListener() {
      @Override
      public void onChange(TransportOption newTransport, boolean manuallySelected) {
        calculateCharactersRemaining();
        composeText.setTransport(newTransport);
        buttonToggle.getBackground().setColorFilter(BlendModeColorFilterCompat.createBlendModeColorFilterCompat(newTransport.getBackgroundColor(), BlendModeCompat.MODULATE));
        buttonToggle.getBackground().invalidateSelf();
        if (manuallySelected) {
          recordSubscriptionIdPreference(newTransport.getSimSubscriptionId());
          sendIfSimCardNotAsked(false);
        }
      }
    });

    titleView.setOnClickListener(new OnClickListener() {
      @Override
      public void onClick(View v) {
        screenHost().openRecipientPreferences(recipients.getIds());
      }
    });

    unblockButton.setOnClickListener(new OnClickListener() {
      @Override
      public void onClick(View v) {
        handleUnblock();
      }
    });

    composeText.setOnKeyListener(composeKeyPressedListener);
    composeText.addTextChangedListener(composeKeyPressedListener);
    composeText.setOnEditorActionListener(sendButtonListener);
    composeText.setOnClickListener(composeKeyPressedListener);
    composeText.setOnFocusChangeListener(composeKeyPressedListener);
  }

  private void initializeBackPressedCallback() {
    if (backPressedCallback != null) {
      backPressedCallback.remove();
    }

    backPressedCallback = new OnBackPressedCallback(true) {
      @Override
      public void handleOnBackPressed() {
        Log.w(TAG, "onBackPressed()");
        if (container != null && container.isInputOpen()) {
          container.hideCurrentInput(composeText);
        } else {
          setEnabled(false);
          requireActivity().getOnBackPressedDispatcher().onBackPressed();
          setEnabled(true);
        }
      }
    };

    requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), backPressedCallback);
  }

  protected void initializeActionBar() {
    getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    getSupportActionBar().setCustomView(R.layout.conversation_title_view);
    getSupportActionBar().setDisplayShowCustomEnabled(true);
    getSupportActionBar().setDisplayShowTitleEnabled(false);
  }

  void focusCompose() {
    if (composeText != null) composeText.requestFocus();
  }

  void disableTitleClick() {
    if (titleView != null) titleView.setOnClickListener(null);
  }

  private void initializeResources() {
    if (recipients != null) recipients.removeListener(this);

    screenViewModel.setConversation(
        getIntent().getLongArrayExtra(RECIPIENTS_EXTRA),
        getIntent().getLongExtra(THREAD_ID_EXTRA, -1),
        getIntent().getIntExtra(DISTRIBUTION_TYPE_EXTRA, ThreadDatabase.DistributionTypes.DEFAULT),
        getIntent().getBooleanExtra(IS_ARCHIVED_EXTRA, false));
    ConversationScreenUiState screenState = screenViewModel.getState().getValue();
    recipients = RecipientFactory.getRecipientsForIds(requireContext(), screenState.getRecipientIds(), true);
    threadId = screenState.getThreadId();
    archived = screenState.isArchived();
    distributionType = screenState.getDistributionType();

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) {
      LinearLayout conversationContainer = ViewUtil.findById(requireView(), R.id.conversation_container);
      conversationContainer.setClipChildren(true);
      conversationContainer.setClipToPadding(true);
    }

    if (!(requireActivity() instanceof ConversationActivity)) {
      LinearLayout conversationContainer = ViewUtil.findById(requireView(), R.id.conversation_container);
      conversationContainer.setPadding(conversationContainer.getPaddingLeft(), 0,
                                       conversationContainer.getPaddingRight(),
                                       conversationContainer.getPaddingBottom());
    }

    recipients.addListener(this);
  }

  @Override
  public void onModified(final Recipients recipients) {
    titleView.post(new Runnable() {
      @Override
      public void run() {
        titleView.setTitle(recipients);
        setBlockedUserState(recipients);
        setActionBarColor(recipients.getColor());
        updateRecipientPreferences();
      }
    });
  }

  private void initializeReceivers() {
    securityUpdateReceiver = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
        long eventThreadId = intent.getLongExtra("thread_id", -1);

        if (eventThreadId == threadId || eventThreadId == -2) {
          initializeSecurity();
          updateRecipientPreferences();
          calculateCharactersRemaining();
        }
      }
    };

  ContextCompat.registerReceiver(requireContext(),
                   securityUpdateReceiver,
                   new IntentFilter(SecurityEvent.SECURITY_UPDATE_EVENT),
                   KeyCachingService.KEY_PERMISSION,
                   null,
                   ContextCompat.RECEIVER_NOT_EXPORTED);
  }

  //////// Helper Methods

  private void addAttachment(int type) {
    Log.w("ComposeMessageActivity", "Selected: " + type);
    switch (type) {
    case AttachmentTypeSelectorAdapter.ADD_IMAGE:
      AttachmentManager.selectImage(requireActivity(), imagePicker); break;
    case AttachmentTypeSelectorAdapter.ADD_VIDEO:
      AttachmentManager.selectVideo(requireActivity(), videoPicker); break;
    case AttachmentTypeSelectorAdapter.ADD_SOUND:
      AttachmentManager.selectAudio(requireActivity(), audioPicker); break;
    case AttachmentTypeSelectorAdapter.ADD_CONTACT_INFO:
      AttachmentManager.selectContactInfo(requireActivity(), contactInfoPicker); break;
    case AttachmentTypeSelectorAdapter.TAKE_PHOTO:
      attachmentManager.capturePhoto(requireActivity(), photoCapture); break;
    }
  }

  private void setMedia(@Nullable Uri uri, @NonNull MediaType mediaType) {
    if (uri == null) return;
    useMasterSecret(secret -> {
      attachmentManager.setMedia(secret, uri, mediaType, getCurrentMediaConstraints());
      return null;
    }, null);
  }

  private <T> T useMasterSecret(@NonNull UnlockSession.Operation<T> operation, T lockedValue) {
    try {
      return unlockSession.use(operation);
    } catch (UnlockSession.LockedException error) {
      return lockedValue;
    } catch (Exception error) {
      throw new AssertionError(error);
    }
  }

  private void addAttachmentContactInfo(Uri contactUri) {
    ContactAccessor contactDataList = ContactAccessor.getInstance();
    ContactData contactData = contactDataList.getContactData(requireContext(), contactUri);

    if      (contactData.numbers.size() == 1) composeText.append(contactData.numbers.get(0).number);
    else if (contactData.numbers.size() > 1)  selectContactInfo(contactData);
  }

  private void selectContactInfo(ContactData contactData) {
    final CharSequence[] numbers     = new CharSequence[contactData.numbers.size()];
    final CharSequence[] numberItems = new CharSequence[contactData.numbers.size()];

    for (int i = 0; i < contactData.numbers.size(); i++) {
      numbers[i]     = contactData.numbers.get(i).number;
      numberItems[i] = contactData.numbers.get(i).type + ": " + contactData.numbers.get(i).number;
    }

    AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
    builder.setIconAttribute(R.attr.conversation_attach_contact_info);
    builder.setTitle(R.string.ConversationActivity_select_contact_info);

    builder.setItems(numberItems, new DialogInterface.OnClickListener() {
      @Override
      public void onClick(DialogInterface dialog, int which) {
        composeText.append(numbers[which]);
      }
    });
    builder.show();
  }

  private Drafts getDraftsForCurrentState() {
    Drafts drafts = new Drafts();

    if (!Util.isEmpty(composeText)) {
      drafts.add(new Draft(Draft.TEXT, composeText.getText().toString()));
    }

    for (Slide slide : attachmentManager.buildSlideDeck().getSlides()) {
      if      (slide.hasAudio()) drafts.add(new Draft(Draft.AUDIO, slide.getUri().toString()));
      else if (slide.hasVideo()) drafts.add(new Draft(Draft.VIDEO, slide.getUri().toString()));
      else if (slide.hasImage()) drafts.add(new Draft(Draft.IMAGE, slide.getUri().toString()));
    }

    return drafts;
  }

  protected ListenableFuture<Long> saveDraft() {
    return saveDraft(true);
  }

  private ListenableFuture<Long> saveDraft(boolean trackTask) {
    final SettableFuture<Long> future = new SettableFuture<>();

    if (this.recipients == null || this.recipients.isEmpty()) {
      future.set(threadId);
      return future;
    }

    final Drafts       drafts               = getDraftsForCurrentState();
    final long         thisThreadId         = this.threadId;
    final int          thisDistributionType = this.distributionType;
    final long[]       recipientIds         = recipients.getIds().clone();

    TaskHandle task = screenRepository.saveDrafts(
        thisThreadId, recipientIds, thisDistributionType, drafts,
        new ConversationUnlockCapability(unlockSession),
        new ConversationScreenRepository.Callback<Long>() {
          @Override public void onSuccess(Long savedThreadId) { future.set(savedThreadId); }
          @Override public void onFailure(Exception exception) {
            Log.w(TAG, "Unable to save conversation draft", exception);
            future.setException(exception);
          }
        });
    if (trackTask) trackFutureTask(task, future);

    return future;
  }

  private void setActionBarColor(MaterialColor color) {
    getSupportActionBar().setBackgroundDrawable(new ColorDrawable(color.toActionBarColor(requireContext())));

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      setSystemBarColors(color.toStatusBarColor(requireContext()),
                         ContextCompat.getColor(requireContext(), android.R.color.black));
    }
  }

  private void setBlockedUserState(Recipients recipients) {
    screenViewModel.setBlocked(recipients.isBlocked());
    if (recipients.isBlocked()) {
      unblockButton.setVisibility(View.VISIBLE);
      composePanel.setVisibility(View.GONE);
    } else {
      composePanel.setVisibility(View.VISIBLE);
      unblockButton.setVisibility(View.GONE);
    }
  }

  private void calculateCharactersRemaining() {
    String          messageBody     = composeText.getText().toString();
    TransportOption transportOption = sendButton.getSelectedTransport();
    CharacterState  characterState  = transportOption.calculateCharacters(messageBody);

    if (characterState.charactersRemaining <= 15 || characterState.messagesSpent > 1) {
      charactersLeft.setText(characterState.charactersRemaining + "/" + characterState.maxMessageSize
                                 + " (" + characterState.messagesSpent + ")");
      charactersLeft.setVisibility(View.VISIBLE);
    } else {
      charactersLeft.setVisibility(View.GONE);
    }
  }

  private boolean isSingleConversation() {
    return getRecipients() != null && getRecipients().isSingleRecipient() && !getRecipients().isGroupRecipient();
  }

  private boolean isActiveGroup() {
    return false;
  }

  private boolean isGroupConversation() {
    return getRecipients() != null &&
        (!getRecipients().isSingleRecipient() || getRecipients().isGroupRecipient());
  }

  private boolean isPushGroupConversation() {
    return getRecipients() != null && getRecipients().isGroupRecipient();
  }

  protected Recipients getRecipients() {
    return this.recipients;
  }

  protected long getThreadId() {
    return this.threadId;
  }

  private String getMessage() throws InvalidMessageException {
    String rawText = composeText.getText().toString();

    if (rawText.length() < 1 && !attachmentManager.isAttachmentPresent())
      throw new InvalidMessageException(getString(R.string.ConversationActivity_message_is_empty_exclamation));

    if (!isEncryptedConversation &&
        AutoInitiate.isTaggableMessage(rawText) &&
        AutoInitiate.isTaggableDestination(getRecipients())) {
      rawText = AutoInitiate.getTaggedMessage(rawText);
    }

    return rawText;
  }

  private MediaConstraints getCurrentMediaConstraints() {
    return MediaConstraints.getMmsMediaConstraints(sendButton.getSelectedTransport().getSimSubscriptionId().orElse(-1), isSecureSmsDestination);
  }

  private void markThreadAsRead() {
    trackCallbackTask(screenRepository.markRead(threadId,
      new ConversationUnlockCapability(unlockSession),
        screenCallback(ignored -> {}, "Unable to mark conversation as read")));
  }

  private void markLastSeen() {
    trackCallbackTask(screenRepository.markLastSeen(threadId,
        screenCallback(ignored -> {}, "Unable to update conversation last-seen time")));
  }

  protected void sendComplete(long threadId) {
    boolean refreshFragment = (threadId != this.threadId);
    this.threadId = threadId;
    screenViewModel.setThreadId(threadId);

    if (fragment == null || !fragment.isVisible() || isFinishing()) {
      return;
    }

    fragment.setLastSeen(0);

    if (refreshFragment) {
      fragment.reload(recipients, threadId);

      initializeSecurity();
      updateRecipientPreferences();
    }

    fragment.scrollToBottom();
    attachmentManager.cleanup();
    screenHost().onConversationSendComplete(threadId);
  }

  private void sendMessage() {
    TransportOption transportOption = sendButton.getSelectedTransport();

    if (transportOption == null || transportOption.getType() == Type.DISABLED) return;

    try {
      Recipients recipients = getRecipients();

      if (recipients == null) {
        throw new RecipientFormattingException("Badly formatted");
      }

      boolean    forcePlaintext = sendButton.getSelectedTransport().isPlaintext();
      int        subscriptionId = sendButton.getSelectedTransport().getSimSubscriptionId().orElse(-1);

      Log.w(TAG, "isManual Selection: " + sendButton.isManualSelection());
      Log.w(TAG, "forcePlaintext: " + forcePlaintext);

      if ((!recipients.isSingleRecipient() || recipients.isEmailRecipient()) && !isMmsEnabled) {
        handleManualMmsRequired();
      } else if (attachmentManager.isAttachmentPresent() || !recipients.isSingleRecipient() || recipients.isGroupRecipient() || recipients.isEmailRecipient()) {
        sendMediaMessage(forcePlaintext, subscriptionId);
      } else {
        sendTextMessage(forcePlaintext, subscriptionId);
      }
    } catch (RecipientFormattingException ex) {
      Toast.makeText(requireContext(),
                     R.string.ConversationActivity_recipient_is_not_a_valid_sms_or_email_address_exclamation,
                     Toast.LENGTH_LONG).show();
      Log.w(TAG, ex);
    } catch (InvalidMessageException ex) {
      Toast.makeText(requireContext(), R.string.ConversationActivity_message_is_empty_exclamation,
                     Toast.LENGTH_SHORT).show();
      Log.w(TAG, ex);
    }
  }

  private void sendMediaMessage(final boolean forcePlaintext, final int subscriptionId)
      throws InvalidMessageException
  {
    final long[]    recipientIds     = recipients.getIds().clone();
    final SlideDeck slideDeck        = attachmentManager.buildSlideDeck();
    final String    messageBody      = getMessage();
    final long      sentTimeMillis   = System.currentTimeMillis();
    final int       distributionType = this.distributionType;
    final boolean   secureMessage    = isEncryptedConversation && !forcePlaintext;

    Permissions.with(requireActivity())
               .request(Manifest.permission.SEND_SMS)
               .ifNecessary()
               .withPermanentDenialDialog(getString(R.string.ConversationActivity_silence_needs_sms_permission_in_order_to_send_an_sms))
               .onAllGranted(() -> {
                 attachmentManager.clear();
                 composeText.setText("");
                 screenViewModel.sendMedia(
                     new MediaSendRequest(recipientIds, slideDeck, messageBody, sentTimeMillis,
                                          subscriptionId, distributionType, secureMessage, threadId),
                     new ConversationUnlockCapability(unlockSession));
               })
               .onAnyDenied(() -> sendComplete(threadId))
               .execute();
  }

  private void sendTextMessage(boolean forcePlaintext, final int subscriptionId)
      throws InvalidMessageException
  {
    final long[]  recipientIds  = recipients.getIds().clone();
    final String  messageBody   = getMessage();
    final boolean secureMessage = isEncryptedConversation && !forcePlaintext;

    Permissions.with(requireActivity())
               .request(Manifest.permission.SEND_SMS)
               .ifNecessary()
               .withPermanentDenialDialog(getString(R.string.ConversationActivity_silence_needs_sms_permission_in_order_to_send_an_sms))
               .onAllGranted(() -> {
                 this.composeText.setText("");
                 screenViewModel.sendText(
                     new TextSendRequest(recipientIds, messageBody, secureMessage, subscriptionId, threadId),
                   new ConversationUnlockCapability(unlockSession));
               })
               .execute();
  }

  private void updateToggleButtonState() {
    boolean draftPresent = composeText.getText().length() > 0 || attachmentManager.isAttachmentPresent();
    screenViewModel.setComposeStatus(draftPresent, draftPresent && !recipients.isBlocked());
    if (composeText.getText().length() == 0 && !attachmentManager.isAttachmentPresent()) {
      buttonToggle.display(attachButton);
    } else {
      buttonToggle.display(sendButton);
    }
  }

  private void recordSubscriptionIdPreference(final Optional<Integer> subscriptionId) {
    trackCallbackTask(screenRepository.setDefaultSubscription(
        recipients.getIds(), subscriptionId.orElse(-1),
        screenCallback(ignored -> {}, "Unable to record default subscription")));
  }

  private boolean sendIfSimCardNotAsked(boolean fromSendButton) {
    if (!SilencePreferences.isSimCardAsked(requireContext()) || (!fromSendButton && sendButton.isForceSend())) {
      sendMessage();
      return true;
    }
    return false;
  }

  // Listeners

  private class AttachmentTypeListener implements DialogInterface.OnClickListener {
    @Override
    public void onClick(DialogInterface dialog, int which) {
      addAttachment(attachmentAdapter.buttonToCommand(which));
      dialog.dismiss();
    }
  }

  private class EmojiToggleListener implements OnClickListener {

    @Override public void onClick(View v) {
      if (container.getCurrentInput() == emojiDrawerStub.get()) {
        container.showSoftkey(composeText);
      } else {
        container.show(composeText, emojiDrawerStub.get());
      }
    }
  }

  @Override
  public void onMediaSelected(@NonNull Uri uri, String contentType) {
    if (!TextUtils.isEmpty(contentType) && contentType.trim().equals("image/gif")) {
      setMedia(uri, MediaType.GIF);
    } else if (MediaUtil.isImageType(contentType)) {
      setMedia(uri, MediaType.IMAGE);
    } else if (MediaUtil.isVideoType(contentType)) {
      setMedia(uri, MediaType.VIDEO);
    } else if (MediaUtil.isAudioType(contentType)) {
      setMedia(uri, MediaType.AUDIO);
    }
  }

  private class SendButtonListener implements OnClickListener, TextView.OnEditorActionListener {
    @Override
    public void onClick(View v) {
      if (!sendIfSimCardNotAsked(true)) {
        sendButton.displayTransports(true);
      }
    }

    @Override
    public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
      if (actionId == EditorInfo.IME_ACTION_SEND) {
        sendButton.performClick();
        return true;
      }
      return false;
    }
  }

  private class AttachButtonListener implements OnClickListener {
    @Override
    public void onClick(View v) {
      handleAddAttachment();
    }
  }

  private class AttachButtonLongClickListener implements View.OnLongClickListener {
    @Override
    public boolean onLongClick(View v) {
      return sendButton.performLongClick();
    }
  }

  private class ComposeKeyPressedListener implements OnKeyListener, OnClickListener, TextWatcher, OnFocusChangeListener {

    int beforeLength;

    @Override
    public boolean onKey(View v, int keyCode, KeyEvent event) {
      if (event.getAction() == KeyEvent.ACTION_DOWN) {
        if (keyCode == KeyEvent.KEYCODE_ENTER) {
          if (SilencePreferences.getEnterKeyType(requireContext()).equals("send")) {
            sendButton.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
            sendButton.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
            return true;
          }
        }
      }
      return false;
    }

    @Override
    public void onClick(View v) {
      container.showSoftkey(composeText);
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count,int after) {
      beforeLength = composeText.getText().length();
    }

    @Override
    public void afterTextChanged(Editable s) {
      calculateCharactersRemaining();

      if (composeText.getText().length() == 0 || beforeLength == 0) {
        composeText.postDelayed(new Runnable() {
          @Override
          public void run() {
            updateToggleButtonState();
          }
        }, 50);
      }
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before,int count) {}

    @Override
    public void onFocusChange(View v, boolean hasFocus) {}
  }

  @Override
  public void setThreadId(long threadId) {
    this.threadId = threadId;
  }

  @Override
  public void displayMediaPreview(long partRowId, long partUniqueId, long messageId,
                                  long threadId, long recipientId, long date, long size) {
    ((MediaNavigationHost) requireActivity()).openMediaPreview(
        partRowId, partUniqueId, messageId, threadId, recipientId, date, size);
  }

  @Override
  public void displayExternalMedia(long partRowId, long partUniqueId, String contentType) {
    launchExternalMedia(partRowId, partUniqueId, contentType);
  }

  @Override
  public void onMediaPreviewRequested(@NonNull Uri uri, @NonNull String contentType, long size) {
    ((MediaNavigationHost) requireActivity()).openDraftMediaPreview(uri, contentType, size);
  }

  @Override
  public void onAttachmentChanged() {
    initializeSecurity();
    updateRecipientPreferences();
    updateToggleButtonState();
  }

}
