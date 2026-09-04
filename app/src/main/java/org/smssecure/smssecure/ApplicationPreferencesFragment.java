package org.smssecure.smssecure;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.navigation.fragment.NavHostFragment;
import androidx.preference.Preference;

import org.smssecure.smssecure.preferences.AppProtectionPreferenceFragment;
import org.smssecure.smssecure.preferences.AppearancePreferenceFragment;
import org.smssecure.smssecure.preferences.ChatsPreferenceFragment;
import org.smssecure.smssecure.preferences.CorrectedPreferenceFragment;
import org.smssecure.smssecure.preferences.NotificationsPreferenceFragment;
import org.smssecure.smssecure.preferences.SmsMmsPreferenceFragment;

public class ApplicationPreferencesFragment extends CorrectedPreferenceFragment {
  private static final String PREFERENCE_CATEGORY_SMS_MMS = "preference_category_sms_mms";
  private static final String PREFERENCE_CATEGORY_NOTIFICATIONS = "preference_category_notifications";
  private static final String PREFERENCE_CATEGORY_APP_PROTECTION = "preference_category_app_protection";
  private static final String PREFERENCE_CATEGORY_APPEARANCE = "preference_category_appearance";
  private static final String PREFERENCE_CATEGORY_CHATS = "preference_category_chats";
  private static final String PREFERENCE_CATEGORY_ADVANCED = "preference_category_advanced";
  private static final String PREFERENCE_ABOUT = "preference_about";
  private static final String PREFERENCE_PRIVACY_POLICY = "preference_privacy_policy";

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    bindCategory(PREFERENCE_CATEGORY_SMS_MMS, R.id.sms_mms_preferences);
    bindCategory(PREFERENCE_CATEGORY_NOTIFICATIONS, R.id.notification_preferences);
    bindCategory(PREFERENCE_CATEGORY_APP_PROTECTION, R.id.app_protection_preferences);
    bindCategory(PREFERENCE_CATEGORY_APPEARANCE, R.id.appearance_preferences);
    bindCategory(PREFERENCE_CATEGORY_CHATS, R.id.chat_preferences);
    bindCategory(PREFERENCE_CATEGORY_ADVANCED, R.id.advanced_preferences);
    findPreference(PREFERENCE_PRIVACY_POLICY).setOnPreferenceClickListener(preference -> {
      handlePrivacyPolicy();
      return true;
    });
  }

  @Override
  public void onCreatePreferences(@Nullable Bundle savedInstanceState, String rootKey) {
    addPreferencesFromResource(R.xml.preferences);
  }

  @Override
  public void onResume() {
    super.onResume();
    setCategorySummaries();
  }

  private void bindCategory(String key, int destinationId) {
    findPreference(key).setOnPreferenceClickListener(preference -> {
      NavHostFragment.findNavController(this).navigate(destinationId);
      return true;
    });
  }

  private void handlePrivacyPolicy() {
    try {
      startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://silence.im/privacy")));
    } catch (ActivityNotFoundException error) {
      Toast.makeText(requireContext().getApplicationContext(),
                     R.string.ConversationActivity_cant_open_link, Toast.LENGTH_LONG).show();
    }
  }

  private void setCategorySummaries() {
    findPreference(PREFERENCE_CATEGORY_SMS_MMS)
        .setSummary(SmsMmsPreferenceFragment.getSummary(requireContext()));
    findPreference(PREFERENCE_CATEGORY_NOTIFICATIONS)
        .setSummary(NotificationsPreferenceFragment.getSummary(requireContext()));
    findPreference(PREFERENCE_CATEGORY_APP_PROTECTION)
        .setSummary(AppProtectionPreferenceFragment.getSummary(requireContext()));
    findPreference(PREFERENCE_CATEGORY_APPEARANCE)
        .setSummary(AppearancePreferenceFragment.getSummary(requireContext()));
    findPreference(PREFERENCE_CATEGORY_CHATS)
        .setSummary(ChatsPreferenceFragment.getSummary(requireContext()));
    findPreference(PREFERENCE_ABOUT)
        .setSummary(getString(R.string.preferences__about_version, BuildConfig.VERSION_NAME));
  }
}