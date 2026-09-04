package org.smssecure.smssecure;

import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

import org.smssecure.smssecure.components.AvatarImageView;
import org.smssecure.smssecure.domain.conversation.ConversationUnlockCapability;
import org.smssecure.smssecure.domain.security.UnlockSession;
import org.smssecure.smssecure.recipients.RecipientFactory;
import org.smssecure.smssecure.recipients.Recipients;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public final class RecipientPreferenceFragment extends Fragment {
  static final String RECIPIENT_IDS_ARGUMENT = "recipient_preferences.recipient_ids";
  private static final String SETTINGS_TAG = "recipient-preference-settings";

  private AvatarImageView avatar;
  private Toolbar toolbar;
  private TextView title;
  private TextView blockedIndicator;
  private long[] recipientIds;

  static Bundle arguments(@NonNull long[] recipientIds) {
    if (recipientIds.length == 0) throw new SecurityException("Missing recipient IDs");
    Bundle arguments = new Bundle();
    arguments.putLongArray(RECIPIENT_IDS_ARGUMENT, recipientIds.clone());
    requireValidArguments(arguments);
    return arguments;
  }

  static void requireValidArguments(@NonNull Bundle arguments) {
    long[] recipientIds;
    try {
      recipientIds = arguments.getLongArray(RECIPIENT_IDS_ARGUMENT);
    } catch (ClassCastException exception) {
      throw new SecurityException("Invalid recipient ID type", exception);
    }
    if (recipientIds == null || recipientIds.length == 0) {
      throw new SecurityException("Missing recipient IDs");
    }
    for (long recipientId : recipientIds) {
      if (recipientId < 0) throw new SecurityException("Invalid recipient ID");
    }
  }

  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                           @Nullable Bundle savedInstanceState) {
    return inflater.inflate(R.layout.recipient_preference_activity, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);
    requireValidArguments(requireArguments());
    recipientIds = requireArguments().getLongArray(RECIPIENT_IDS_ARGUMENT).clone();
    toolbar = view.findViewById(R.id.toolbar);
    avatar = toolbar.findViewById(R.id.avatar);
    title = toolbar.findViewById(R.id.name);
    blockedIndicator = toolbar.findViewById(R.id.blocked_indicator);
    toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
    toolbar.setNavigationOnClickListener(ignored -> NavHostFragment.findNavController(this).navigateUp());

    if (getChildFragmentManager().findFragmentByTag(SETTINGS_TAG) == null) {
      RecipientPreferenceSettingsFragment settings = new RecipientPreferenceSettingsFragment();
      settings.setArguments(arguments(recipientIds));
      getChildFragmentManager().beginTransaction()
                               .replace(R.id.preference_fragment, settings, SETTINGS_TAG)
                               .commitNow();
    }
    renderHeader();
  }

  @Override
  public void onResume() {
    super.onResume();
    renderHeader();
  }

  void renderHeader() {
    if (toolbar == null) return;
    try {
      Recipients recipients = new ConversationUnlockCapability(UnlockSession.capture()).use(
          masterSecret -> RecipientFactory.getRecipientsForIds(requireContext(), recipientIds.clone(), true));
      avatar.setAvatar(recipients, true);
      title.setText(recipients.toShortString());
      toolbar.setBackgroundColor(recipients.getColor().toActionBarColor(requireContext()));
      blockedIndicator.setVisibility(recipients.isBlocked() ? View.VISIBLE : View.GONE);
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        ((BaseActionBarActivity) requireActivity()).setSystemBarColors(
            recipients.getColor().toStatusBarColor(requireContext()),
            androidx.core.content.ContextCompat.getColor(requireContext(), android.R.color.black));
      }
    } catch (Exception exception) {
      clearRenderedState();
    }
  }

  void clearSensitiveState() {
    Fragment settings = getChildFragmentManager().findFragmentByTag(SETTINGS_TAG);
    if (settings instanceof RecipientPreferenceSettingsFragment) {
      ((RecipientPreferenceSettingsFragment) settings).clearSensitiveState();
    }
    clearRenderedState();
  }

  private void clearRenderedState() {
    if (avatar != null) avatar.setAvatar((Recipients) null, false);
    if (title != null) title.setText("");
    if (blockedIndicator != null) blockedIndicator.setVisibility(View.GONE);
  }

  @Override
  public void onDestroyView() {
    clearSensitiveState();
    toolbar = null;
    avatar = null;
    title = null;
    blockedIndicator = null;
    super.onDestroyView();
  }
}