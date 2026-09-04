package org.smssecure.smssecure;

import android.content.Intent;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.TouchDelegate;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;

import org.smssecure.smssecure.components.AnimatingToggle;
import org.smssecure.smssecure.database.ThreadDatabase;
import org.smssecure.smssecure.domain.conversation.ConversationPayload;
import org.smssecure.smssecure.domain.conversation.ConversationPayloadStore;
import org.smssecure.smssecure.domain.security.UnlockSession;
import org.smssecure.smssecure.mms.PartAuthority;
import org.smssecure.smssecure.recipients.RecipientFactory;
import org.smssecure.smssecure.recipients.Recipients;
import org.smssecure.smssecure.ui.LifecycleStateCollector;
import org.smssecure.smssecure.ui.newconversation.NewConversationUiState;
import org.smssecure.smssecure.ui.newconversation.NewConversationViewModel;
import org.smssecure.smssecure.ui.share.SharePayloadUiState;
import org.smssecure.smssecure.ui.share.SharePayloadViewModel;
import org.smssecure.smssecure.util.ServiceUtil;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public final class NewConversationFragment extends Fragment
    implements ContactSelectionListFragment.OnContactSelectedListener {
  static final String PAYLOAD_TOKEN_ARGUMENT = "new_conversation.payload_token";
  static final String PAYLOAD_OWNER = "new-conversation-selector";

  private ContactSelectionListFragment contactsFragment;
  private NewConversationViewModel viewModel;
  private SharePayloadViewModel sharePayloadViewModel;
  private EditText searchText;
  private AnimatingToggle toggle;
  private ImageView keyboardToggle;
  private ImageView dialpadToggle;
  private ImageView clearToggle;
  private LinearLayout toggleContainer;
  private long renderedThreadId = Long.MIN_VALUE;
  private String payloadToken;
  private ConversationPayload pendingPayload;
  private boolean invalidPayload;
  private boolean preparingPayload;
  @Inject ConversationPayloadStore payloadStore;

  static Bundle arguments(@NonNull String payloadToken) {
    if (payloadToken.isEmpty()) throw new IllegalArgumentException("Missing payload token");
    Bundle arguments = new Bundle();
    arguments.putString(PAYLOAD_TOKEN_ARGUMENT, payloadToken);
    return arguments;
  }

  static void requireValidArguments(@NonNull Bundle arguments) {
    String payloadToken = arguments.getString(PAYLOAD_TOKEN_ARGUMENT);
    if (payloadToken == null || payloadToken.isEmpty()) {
      throw new SecurityException("Missing payload token");
    }
  }

  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    payloadToken = requireArguments().getString(PAYLOAD_TOKEN_ARGUMENT);
    if (payloadToken != null) {
      try {
        payloadStore.requireValid(payloadToken, PAYLOAD_OWNER);
        pendingPayload = payloadStore.inspect(payloadToken, PAYLOAD_OWNER);
      } catch (ConversationPayloadStore.InvalidPayloadException error) {
        payloadToken = null;
        invalidPayload = true;
      }
    }
  }

  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                           @Nullable Bundle savedInstanceState) {
    if (invalidPayload) return new View(requireContext());
    return inflater.inflate(R.layout.contact_selection_activity, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);
    if (invalidPayload) {
      NavHostFragment.findNavController(this).navigateUp();
      return;
    }
    viewModel = new ViewModelProvider(this).get(NewConversationViewModel.class);
    sharePayloadViewModel = new ViewModelProvider(this).get(SharePayloadViewModel.class);
    initializeToolbar(view);
    initializeResources(view);
    initializeSearch();
    LifecycleStateCollector.collect(getViewLifecycleOwner(), viewModel.getState(), this::render);
    LifecycleStateCollector.collect(
        getViewLifecycleOwner(), sharePayloadViewModel.getState(), this::renderPayloadState);
    preparePayload(view);
  }

  private void preparePayload(View view) {
    if (pendingPayload == null) return;
    if (pendingPayload.getMedia() != null && !PartAuthority.isLocalUri(pendingPayload.getMedia())) {
      preparingPayload = true;
      view.setVisibility(View.INVISIBLE);
      try {
        UnlockSession.capture().use(masterSecret -> {
          sharePayloadViewModel.resolve(
              masterSecret, pendingPayload.getMedia(), pendingPayload.getMediaType(),
              this::handleResolvedMedia);
          return null;
        });
      } catch (Exception error) {
        rejectPayload();
      }
      return;
    }
    routePreparedPayload(view);
  }

  private void handleResolvedMedia(Uri media) {
    if (payloadToken == null || media == null) {
      if (media != null) sharePayloadViewModel.discard(media);
      rejectPayload();
      return;
    }
    try {
      payloadStore.replaceMedia(
          payloadToken, PAYLOAD_OWNER, media, pendingPayload.getMediaType(),
          () -> sharePayloadViewModel.discard(media));
      pendingPayload = payloadStore.inspect(payloadToken, PAYLOAD_OWNER);
      preparingPayload = false;
      if (getView() != null) routePreparedPayload(getView());
    } catch (ConversationPayloadStore.InvalidPayloadException error) {
      sharePayloadViewModel.discard(media);
      rejectPayload();
    }
  }

  private void routePreparedPayload(View view) {
    if (pendingPayload == null) {
      view.setVisibility(View.VISIBLE);
      return;
    }
    long[] recipientIds = pendingPayload.getRecipientIds();
    if (pendingPayload.getThreadId() > 0L && recipientIds.length > 0) {
      openPayloadConversation(pendingPayload.getThreadId(), recipientIds,
                              pendingPayload.getDistributionType());
    } else if (recipientIds.length > 0) {
      view.setVisibility(View.INVISIBLE);
      viewModel.selectRecipient(recipientIds);
    } else {
      view.setVisibility(View.VISIBLE);
    }
  }

  private void renderPayloadState(SharePayloadUiState state) {
    if (preparingPayload && state.isFailed()) rejectPayload();
  }

  private void initializeToolbar(View view) {
    Toolbar toolbar = view.findViewById(R.id.toolbar);
    toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
    toolbar.setNavigationOnClickListener(ignored -> NavHostFragment.findNavController(this).navigateUp());
    expandTapArea(toolbar, view.findViewById(R.id.action_icon), 500);
  }

  private void initializeResources(View view) {
    searchText = view.findViewById(R.id.search_view);
    toggle = view.findViewById(R.id.button_toggle);
    keyboardToggle = view.findViewById(R.id.search_keyboard);
    dialpadToggle = view.findViewById(R.id.search_dialpad);
    clearToggle = view.findViewById(R.id.search_clear);
    toggleContainer = view.findViewById(R.id.toggle_container);
    contactsFragment = (ContactSelectionListFragment) getChildFragmentManager()
        .findFragmentById(R.id.contact_selection_list_fragment);
    contactsFragment.setOnContactSelectedListener(this);

    keyboardToggle.setOnClickListener(ignored -> {
      searchText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME);
      ServiceUtil.getInputMethodManager(requireContext()).showSoftInput(searchText, 0);
      displayTogglingView(dialpadToggle);
    });
    dialpadToggle.setOnClickListener(ignored -> {
      searchText.setInputType(InputType.TYPE_CLASS_PHONE);
      ServiceUtil.getInputMethodManager(requireContext()).showSoftInput(searchText, 0);
      displayTogglingView(keyboardToggle);
    });
    clearToggle.setOnClickListener(ignored -> {
      searchText.setText("");
      displayTogglingView(isTextInput() ? dialpadToggle : keyboardToggle);
    });
    expandTapArea(toggleContainer, dialpadToggle, 500);
  }

  private void initializeSearch() {
    searchText.addTextChangedListener(new TextWatcher() {
      @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
      @Override public void onTextChanged(CharSequence text, int start, int before, int count) {}

      @Override public void afterTextChanged(Editable text) {
        if (text.length() > 0) displayTogglingView(clearToggle);
        else                  displayTogglingView(isTextInput() ? dialpadToggle : keyboardToggle);
        contactsFragment.setQueryFilter(text.toString());
      }
    });
  }

  @Override
  public void onContactSelected(String number) {
    Recipients recipients = RecipientFactory.getRecipientsFromString(requireContext(), number, true);
    viewModel.selectRecipient(recipients.getIds());
  }

  private void render(NewConversationUiState state) {
    if (!state.hasResult() || state.getThreadId() == renderedThreadId) return;
    renderedThreadId = state.getThreadId();
    String conversationToken = null;
    if (payloadToken != null) {
      try {
        int distributionType = pendingPayload == null
            ? ThreadDatabase.DistributionTypes.DEFAULT : pendingPayload.getDistributionType();
        conversationToken = payloadStore.retarget(
            payloadToken, PAYLOAD_OWNER, ConversationScreenFragment.PAYLOAD_OWNER,
            state.getThreadId(), state.getRecipientIds(),
            distributionType);
        payloadToken = null;
      } catch (ConversationPayloadStore.InvalidPayloadException error) {
        clearSensitiveState();
        NavHostFragment.findNavController(this).navigateUp();
        return;
      }
    }
    int distributionType = pendingPayload == null
        ? ThreadDatabase.DistributionTypes.DEFAULT : pendingPayload.getDistributionType();
    Bundle arguments = ConversationScreenFragment.arguments(
      state.getRecipientIds(), state.getThreadId(), distributionType,
      false, System.currentTimeMillis(), 0L, conversationToken);
    androidx.navigation.NavController controller = NavHostFragment.findNavController(this);
    controller.popBackStack();
    controller.navigate(ConversationListDestination.CONVERSATION.getId(), arguments);
    viewModel.acknowledgeResult();
  }

  private void openPayloadConversation(long threadId, long[] recipientIds, int distributionType) {
    try {
      String conversationToken = payloadStore.retarget(
          payloadToken, PAYLOAD_OWNER, ConversationScreenFragment.PAYLOAD_OWNER,
          threadId, recipientIds, distributionType);
      payloadToken = null;
      Bundle arguments = ConversationScreenFragment.arguments(
          recipientIds, threadId, distributionType, false,
          System.currentTimeMillis(), 0L, conversationToken);
      androidx.navigation.NavController controller = NavHostFragment.findNavController(this);
      controller.popBackStack();
      controller.navigate(ConversationListDestination.CONVERSATION.getId(), arguments);
    } catch (ConversationPayloadStore.InvalidPayloadException error) {
      rejectPayload();
    }
  }

  private void rejectPayload() {
    clearSensitiveState();
    if (isAdded()) NavHostFragment.findNavController(this).navigateUp();
  }

  void clearSensitiveState() {
    if (viewModel != null) viewModel.clearSensitiveState();
    if (searchText != null) searchText.setText("");
    if (contactsFragment != null) contactsFragment.clearSensitiveState();
    if (payloadToken != null) payloadStore.discard(payloadToken);
    payloadToken = null;
    pendingPayload = null;
    preparingPayload = false;
    renderedThreadId = Long.MIN_VALUE;
    invalidPayload = true;
  }

  @Override
  public void onDestroy() {
    if (isRemoving() && payloadToken != null) payloadStore.discard(payloadToken);
    super.onDestroy();
  }

  private boolean isTextInput() {
    return (searchText.getInputType() & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT;
  }

  private void displayTogglingView(View view) {
    toggle.display(view);
    expandTapArea(toggleContainer, view, 500);
  }

  private static void expandTapArea(View container, View child, int padding) {
    container.post(() -> {
      Rect rect = new Rect();
      child.getHitRect(rect);
      rect.inset(-padding, -padding);
      container.setTouchDelegate(new TouchDelegate(rect, child));
    });
  }
}