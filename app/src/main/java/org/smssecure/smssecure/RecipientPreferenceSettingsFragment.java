package org.smssecure.smssecure;

import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.CheckBoxPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;

import org.smssecure.smssecure.color.MaterialColor;
import org.smssecure.smssecure.color.MaterialColors;
import org.smssecure.smssecure.database.RecipientPreferenceDatabase.VibrateState;
import org.smssecure.smssecure.domain.conversation.ConversationUnlockCapability;
import org.smssecure.smssecure.domain.security.UnlockSession;
import org.smssecure.smssecure.preferences.CorrectedPreferenceFragment;
import org.smssecure.smssecure.preferences.widgets.AdvancedRingtonePreference;
import org.smssecure.smssecure.preferences.widgets.ColorPickerPreference;
import org.smssecure.smssecure.recipients.RecipientFactory;
import org.smssecure.smssecure.recipients.Recipients;
import org.smssecure.smssecure.ui.LifecycleStateCollector;
import org.smssecure.smssecure.ui.recipientpreferences.RecipientPreferencesUiState;
import org.smssecure.smssecure.ui.recipientpreferences.RecipientPreferencesViewModel;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public final class RecipientPreferenceSettingsFragment extends CorrectedPreferenceFragment {
  private static final String TAG = RecipientPreferenceSettingsFragment.class.getSimpleName();
  private static final String PREFERENCE_MUTED = "pref_key_recipient_mute";
  private static final String PREFERENCE_TONE = "pref_key_recipient_ringtone";
  private static final String PREFERENCE_VIBRATE = "pref_key_recipient_vibrate";
  private static final String PREFERENCE_BLOCK = "pref_key_recipient_block";
  private static final String PREFERENCE_COLOR = "pref_key_recipient_color";

  private RecipientPreferencesViewModel viewModel;
  private long[] recipientIds;
  private AlertDialog blockDialog;
  private boolean sensitiveStateCleared;

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    RecipientPreferenceFragment.requireValidArguments(requireArguments());
    recipientIds = requireArguments().getLongArray(RecipientPreferenceFragment.RECIPIENT_IDS_ARGUMENT).clone();
    viewModel = new ViewModelProvider(this).get(RecipientPreferencesViewModel.class);
    LifecycleStateCollector.collect(this, viewModel.getState(), this::renderState);
    findPreference(PREFERENCE_TONE).setOnPreferenceChangeListener(this::changeRingtone);
    findPreference(PREFERENCE_VIBRATE).setOnPreferenceChangeListener(this::changeVibrate);
    findPreference(PREFERENCE_MUTED).setOnPreferenceClickListener(this::toggleMute);
    findPreference(PREFERENCE_BLOCK).setOnPreferenceClickListener(this::toggleBlock);
    findPreference(PREFERENCE_COLOR).setOnPreferenceChangeListener(this::changeColor);
  }

  @Override
  public void onCreatePreferences(@Nullable Bundle savedInstanceState, String rootKey) {
    addPreferencesFromResource(R.xml.recipient_preferences);
  }

  @Override
  public void onResume() {
    super.onResume();
    sensitiveStateCleared = false;
    withRecipients(this::setSummaries);
  }

  private boolean changeRingtone(Preference preference, Object newValue) {
    Uri value = (Uri) newValue;
    Uri ringtone = Settings.System.DEFAULT_NOTIFICATION_URI.equals(value) ? null : value;
    withRecipients(recipients -> {
      recipients.setRingtone(ringtone);
      viewModel.setRingtone(recipientIds.clone(), ringtone);
    });
    return false;
  }

  private boolean changeVibrate(Preference preference, Object newValue) {
    VibrateState vibrate = VibrateState.fromId(Integer.parseInt((String) newValue));
    withRecipients(recipients -> {
      recipients.setVibrate(vibrate);
      viewModel.setVibrate(recipientIds.clone(), vibrate);
    });
    return false;
  }

  private boolean changeColor(Preference preference, Object newValue) {
    MaterialColor selected = MaterialColors.CONVERSATION_PALETTE.getByColor(requireContext(), (Integer) newValue);
    if (selected == null) return true;
    withRecipients(recipients -> {
      if (preference.isEnabled() && !recipients.getColor().equals(selected)) {
        recipients.setColor(selected);
        viewModel.setColor(recipientIds.clone(), selected);
      }
    });
    return true;
  }

  private boolean toggleMute(Preference preference) {
    withRecipients(recipients -> {
      if (recipients.isMuted()) setMuted(0);
      else MuteDialog.show(requireContext(), this::setMuted);
    });
    return true;
  }

  private void setMuted(long until) {
    withRecipients(recipients -> {
      recipients.setMuted(until);
      viewModel.setMuted(recipientIds.clone(), until);
      setSummaries(recipients);
    });
  }

  private boolean toggleBlock(Preference preference) {
    withRecipients(recipients -> showBlockDialog(!recipients.isBlocked()));
    return true;
  }

  private void showBlockDialog(boolean blocked) {
    if (blockDialog != null) blockDialog.dismiss();
    blockDialog = new AlertDialog.Builder(requireContext())
        .setTitle(blocked ? R.string.RecipientPreferenceActivity_block_this_contact_question
                          : R.string.RecipientPreferenceActivity_unblock_this_contact_question)
        .setMessage(blocked ? R.string.RecipientPreferenceActivity_you_will_no_longer_see_messages_from_this_user
                            : R.string.RecipientPreferenceActivity_are_you_sure_you_want_to_unblock_this_contact)
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(blocked ? R.string.RecipientPreferenceActivity_block
                                   : R.string.RecipientPreferenceActivity_unblock,
                           (dialog, which) -> setBlocked(blocked))
        .create();
    blockDialog.setOnDismissListener(dialog -> blockDialog = null);
    blockDialog.show();
  }

  private void setBlocked(boolean blocked) {
    withRecipients(recipients -> {
      recipients.setBlocked(blocked);
      viewModel.setBlocked(recipientIds.clone(), blocked);
      setSummaries(recipients);
      if (getParentFragment() instanceof RecipientPreferenceFragment) {
        ((RecipientPreferenceFragment) getParentFragment()).renderHeader();
      }
    });
  }

  private void setSummaries(Recipients recipients) {
    CheckBoxPreference mute = findPreference(PREFERENCE_MUTED);
    AdvancedRingtonePreference tone = findPreference(PREFERENCE_TONE);
    ListPreference vibrate = findPreference(PREFERENCE_VIBRATE);
    ColorPickerPreference color = findPreference(PREFERENCE_COLOR);
    Preference block = findPreference(PREFERENCE_BLOCK);
    mute.setChecked(recipients.isMuted());

    Uri toneUri = recipients.getRingtone();
    if (toneUri == null) {
      tone.setSummary(R.string.preferences__default);
      tone.setCurrentRingtone(Settings.System.DEFAULT_NOTIFICATION_URI);
    } else if (toneUri.toString().isEmpty()) {
      tone.setSummary(R.string.preferences__silent);
      tone.setCurrentRingtone(null);
    } else {
      Ringtone ringtone = RingtoneManager.getRingtone(requireContext(), toneUri);
      if (ringtone != null) {
        tone.setSummary(ringtone.getTitle(requireContext()));
        tone.setCurrentRingtone(toneUri);
      }
    }

    if (recipients.getVibrate() == VibrateState.DEFAULT) {
      vibrate.setSummary(R.string.preferences__default);
      vibrate.setValueIndex(0);
    } else if (recipients.getVibrate() == VibrateState.ENABLED) {
      vibrate.setSummary(R.string.RecipientPreferenceActivity_enabled);
      vibrate.setValueIndex(1);
    } else {
      vibrate.setSummary(R.string.RecipientPreferenceActivity_disabled);
      vibrate.setValueIndex(2);
    }

    if (!recipients.isSingleRecipient() || recipients.isGroupRecipient()) {
      if (color != null) getPreferenceScreen().removePreference(color);
      if (block != null) getPreferenceScreen().removePreference(block);
    } else {
      color.setColors(MaterialColors.CONVERSATION_PALETTE.asConversationColorArray(requireContext()));
      color.setColor(recipients.getColor().toActionBarColor(requireContext()));
      block.setTitle(recipients.isBlocked() ? R.string.RecipientPreferenceActivity_unblock
                                            : R.string.RecipientPreferenceActivity_block);
    }
  }

  private void renderState(RecipientPreferencesUiState state) {
    if (sensitiveStateCleared) return;
    if (state.isFailed()) {
      Log.w(TAG, "Unable to update recipient preference");
      viewModel.acknowledgeFailure();
    }
    if (state.getPendingMutations() == 0) withRecipients(this::setSummaries);
  }

  private void withRecipients(@NonNull RecipientAction action) {
    if (sensitiveStateCleared) return;
    try {
      Recipients recipients = new ConversationUnlockCapability(UnlockSession.capture()).use(
          masterSecret -> RecipientFactory.getRecipientsForIds(requireContext(), recipientIds.clone(), true));
      action.run(recipients);
    } catch (Exception ignored) {
    }
  }

  void clearSensitiveState() {
    sensitiveStateCleared = true;
    if (blockDialog != null) blockDialog.dismiss();
    blockDialog = null;
    if (viewModel != null) viewModel.clearSensitiveState();
  }

  @Override
  public void onDestroy() {
    clearSensitiveState();
    super.onDestroy();
  }

  private interface RecipientAction {
    void run(Recipients recipients);
  }
}