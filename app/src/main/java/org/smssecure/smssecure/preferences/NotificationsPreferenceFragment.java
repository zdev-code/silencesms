package org.smssecure.smssecure.preferences;

import android.content.Context;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.lifecycle.ViewModelProvider;
import android.util.Log;

import org.smssecure.smssecure.R;
import org.smssecure.smssecure.domain.security.UnlockSession;
import org.smssecure.smssecure.preferences.widgets.AdvancedRingtonePreference;
import org.smssecure.smssecure.util.SilencePreferences;
import org.smssecure.smssecure.ui.LifecycleStateCollector;
import org.smssecure.smssecure.ui.notificationpreferences.NotificationPreferencesUiState;
import org.smssecure.smssecure.ui.notificationpreferences.NotificationPreferencesViewModel;
import org.smssecure.smssecure.domain.conversation.ConversationUnlockCapability;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class NotificationsPreferenceFragment extends ListSummaryPreferenceFragment {

  private static final String TAG = NotificationsPreferenceFragment.class.getSimpleName();

  private NotificationPreferencesViewModel viewModel;

  @Override
  public void onCreate(Bundle paramBundle) {
    super.onCreate(paramBundle);
    viewModel = new ViewModelProvider(this).get(NotificationPreferencesViewModel.class);
    LifecycleStateCollector.collect(this, viewModel.getState(), this::render);

    this.findPreference(SilencePreferences.LED_COLOR_PREF)
        .setOnPreferenceChangeListener(new ListSummaryListener());
    this.findPreference(SilencePreferences.LED_BLINK_PREF)
        .setOnPreferenceChangeListener(new ListSummaryListener());
    this.findPreference(SilencePreferences.RINGTONE_PREF)
        .setOnPreferenceChangeListener(new RingtoneSummaryListener());
    this.findPreference(SilencePreferences.REPEAT_ALERTS_PREF)
        .setOnPreferenceChangeListener(new ListSummaryListener());
    this.findPreference(SilencePreferences.NOTIFICATION_PRIVACY_PREF)
        .setOnPreferenceChangeListener(new NotificationPrivacyListener());

    initializeListSummary((ListPreference) findPreference(SilencePreferences.LED_COLOR_PREF));
    initializeListSummary((ListPreference) findPreference(SilencePreferences.LED_BLINK_PREF));
    initializeListSummary((ListPreference) findPreference(SilencePreferences.REPEAT_ALERTS_PREF));
    initializeListSummary((ListPreference) findPreference(SilencePreferences.NOTIFICATION_PRIVACY_PREF));
    initializeRingtoneSummary((AdvancedRingtonePreference) findPreference(SilencePreferences.RINGTONE_PREF));
  }

  @Override
  public void onCreatePreferences(@Nullable Bundle savedInstanceState, String rootKey) {
    addPreferencesFromResource(R.xml.preferences_notifications);
  }

  @Override
  public void onResume() {
    super.onResume();
  }

  private class RingtoneSummaryListener implements Preference.OnPreferenceChangeListener {
    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
      Uri value = (Uri) newValue;

      if (value == null) {
        preference.setSummary(R.string.preferences__silent);
      } else {
        Ringtone tone = RingtoneManager.getRingtone(getActivity(), value);
        if (tone != null) {
          preference.setSummary(tone.getTitle(getActivity()));
        }
      }

      return true;
    }
  }

  private void initializeRingtoneSummary(AdvancedRingtonePreference pref) {
    RingtoneSummaryListener listener   = (RingtoneSummaryListener) pref.getOnPreferenceChangeListener();
    String                  encodedUri = SilencePreferences.getNotificationRingtone(requireContext());
    Uri                     uri        = encodedUri == null || encodedUri.isEmpty() ? null : Uri.parse(encodedUri);

    listener.onPreferenceChange(pref, uri);
  }

  public static CharSequence getSummary(Context context) {
    final int onCapsResId   = R.string.ApplicationPreferencesActivity_On;
    final int offCapsResId  = R.string.ApplicationPreferencesActivity_Off;

    return context.getString(SilencePreferences.isNotificationsEnabled(context) ? onCapsResId : offCapsResId);
  }

  private void render(NotificationPreferencesUiState state) {
    if (state.isFailed()) {
      Log.w(TAG, "Unable to refresh notification privacy");
      viewModel.acknowledgeFailure();
    }
  }

  private class NotificationPrivacyListener extends ListSummaryListener {
    @Override
    public boolean onPreferenceChange(Preference preference, Object value) {
      viewModel.refresh(new ConversationUnlockCapability(UnlockSession.capture()));

      return super.onPreferenceChange(preference, value);
    }

  }
}
