package org.smssecure.smssecure;

import android.graphics.Rect;
import android.graphics.drawable.Drawable;
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
import androidx.appcompat.content.res.AppCompatResources;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

import org.smssecure.smssecure.components.AnimatingToggle;
import org.smssecure.smssecure.recipients.Recipient;
import org.smssecure.smssecure.recipients.RecipientFactory;
import org.smssecure.smssecure.util.ServiceUtil;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class PushContactSelectionFragment extends Fragment {
  static final String REQUEST_KEY = "group_create.contact_selection";
  static final String RECIPIENT_IDS_KEY = "group_create.selected_recipient_ids";

  private ContactSelectionListFragment contactsFragment;
  private EditText searchText;
  private AnimatingToggle toggle;
  private ImageView keyboardToggle;
  private ImageView dialpadToggle;
  private ImageView clearToggle;
  private LinearLayout toggleContainer;

  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                           @Nullable Bundle savedInstanceState) {
    return inflater.inflate(R.layout.contact_selection_activity, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);
    initializeToolbar(view);
    initializeResources(view);
    initializeSearch();
  }

  private void initializeToolbar(View view) {
    Toolbar toolbar = view.findViewById(R.id.toolbar);
    toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
    toolbar.setNavigationOnClickListener(ignored ->
        NavHostFragment.findNavController(this).navigateUp());
    ImageView action = view.findViewById(R.id.action_icon);
    Drawable check = AppCompatResources.getDrawable(requireContext(), R.drawable.ic_check_white_24dp);
    if (check != null) action.setImageDrawable(check);
    action.setOnClickListener(ignored -> returnSelection());
    expandTapArea(toolbar, action, 500);
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
    contactsFragment.setMultiSelect(true);

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

  private void returnSelection() {
    List<String> selectedContacts = contactsFragment.getSelectedContacts();
    Set<Long> recipientIds = new LinkedHashSet<>();
    if (selectedContacts != null) {
      for (String contact : selectedContacts) {
        Recipient recipient = RecipientFactory.getRecipientsFromString(requireContext(), contact, false)
            .getPrimaryRecipient();
        if (recipient != null && recipient.getRecipientId() > 0) {
          recipientIds.add(recipient.getRecipientId());
        }
      }
    }
    Bundle result = new Bundle();
    result.putLongArray(RECIPIENT_IDS_KEY,
        recipientIds.stream().mapToLong(Long::longValue).toArray());
    getParentFragmentManager().setFragmentResult(REQUEST_KEY, result);
    NavHostFragment.findNavController(this).navigateUp();
  }

  void clearSensitiveState() {
    getParentFragmentManager().clearFragmentResult(REQUEST_KEY);
    if (searchText != null) searchText.setText("");
    if (contactsFragment != null) contactsFragment.clearSensitiveState();
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