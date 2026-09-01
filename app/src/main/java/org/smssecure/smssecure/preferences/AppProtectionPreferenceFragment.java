package org.smssecure.smssecure.preferences;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.CheckBoxPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.NumberPicker;
import android.widget.Toast;

import org.smssecure.smssecure.ApplicationPreferencesActivity;
import org.smssecure.smssecure.BlockedContactsActivity;
import org.smssecure.smssecure.PassphraseChangeActivity;
import org.smssecure.smssecure.R;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.crypto.MasterSecretStorageException;
import org.smssecure.smssecure.crypto.MasterSecretUtil;
import org.smssecure.smssecure.service.KeyCachingService;
import org.smssecure.smssecure.util.SilencePreferences;

import java.util.concurrent.TimeUnit;

public class AppProtectionPreferenceFragment extends CorrectedPreferenceFragment {

  private static final String PREFERENCE_CATEGORY_BLOCKED = "preference_category_blocked";

  private MasterSecret       masterSecret;
  private CheckBoxPreference disablePassphrase;

  @Override
  public void onCreate(Bundle paramBundle) {
    super.onCreate(paramBundle);

    masterSecret      = androidx.core.os.BundleCompat.getParcelable(getArguments(), "master_secret", MasterSecret.class);
    disablePassphrase = (CheckBoxPreference) this.findPreference("pref_enable_passphrase_temporary");

    this.findPreference(SilencePreferences.CHANGE_PASSPHRASE_PREF)
        .setOnPreferenceClickListener(new ChangePassphraseClickListener());
    this.findPreference(SilencePreferences.PASSPHRASE_TIMEOUT_INTERVAL_PREF)
        .setOnPreferenceClickListener(new PassphraseIntervalClickListener());
    this.findPreference(PREFERENCE_CATEGORY_BLOCKED)
        .setOnPreferenceClickListener(new BlockedContactsClickListener());
    disablePassphrase
        .setOnPreferenceChangeListener(new DisablePassphraseClickListener());
  }

  @Override
  public void onCreatePreferences(@Nullable Bundle savedInstanceState, String rootKey) {
    addPreferencesFromResource(R.xml.preferences_app_protection);
  }

  @Override
  public void onResume() {
    super.onResume();
    ((ApplicationPreferencesActivity) getActivity()).getSupportActionBar().setTitle(R.string.preferences__privacy);

    initializePlatformSpecificOptions();
    initializeTimeoutSummary();

    disablePassphrase.setChecked(!SilencePreferences.isPasswordDisabled(getActivity()));
  }

  private void initializePlatformSpecificOptions() {
    PreferenceScreen preferenceScreen         = getPreferenceScreen();
    Preference       screenSecurityPreference = findPreference(SilencePreferences.SCREEN_SECURITY_PREF);

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.ICE_CREAM_SANDWICH &&
        screenSecurityPreference != null) {
      preferenceScreen.removePreference(screenSecurityPreference);
    }
  }

  private void initializeTimeoutSummary() {
    int timeoutMinutes = SilencePreferences.getPassphraseTimeoutInterval(getActivity());
    this.findPreference(SilencePreferences.PASSPHRASE_TIMEOUT_INTERVAL_PREF)
        .setSummary(getResources().getQuantityString(R.plurals.AppProtectionPreferenceFragment_minutes, timeoutMinutes, timeoutMinutes));
  }

  private class BlockedContactsClickListener implements Preference.OnPreferenceClickListener {
    @Override
    public boolean onPreferenceClick(Preference preference) {
      Intent intent = new Intent(getActivity(), BlockedContactsActivity.class);
      startActivity(intent);
      return true;
    }
  }

  private class ChangePassphraseClickListener implements Preference.OnPreferenceClickListener {
    @Override
    public boolean onPreferenceClick(Preference preference) {
      if (MasterSecretUtil.isPassphraseInitialized(getActivity())) {
        startActivity(new Intent(getActivity(), PassphraseChangeActivity.class));
      } else {
        Toast.makeText(getActivity(),
          R.string.ApplicationPreferenceActivity_you_havent_set_a_passphrase_yet,
          Toast.LENGTH_LONG).show();
      }

      return true;
    }
  }

  private class PassphraseIntervalClickListener implements Preference.OnPreferenceClickListener {

    @Override
    public boolean onPreferenceClick(Preference preference) {
      int          timeoutMinutes = SilencePreferences.getPassphraseTimeoutInterval(getActivity());
      View         view           = LayoutInflater.from(getActivity()).inflate(R.layout.passphrase_timeout_picker, null);
      NumberPicker hours          = view.findViewById(R.id.hours_picker);
      NumberPicker minutes        = view.findViewById(R.id.minutes_picker);
      NumberPicker seconds        = view.findViewById(R.id.seconds_picker);

      hours.setMinValue(0);   hours.setMaxValue(23);
      minutes.setMinValue(0); minutes.setMaxValue(59);
      seconds.setMinValue(0); seconds.setMaxValue(59);

      hours.setValue(timeoutMinutes / 60);
      minutes.setValue(timeoutMinutes % 60);
      seconds.setValue(0);

      new AlertDialog.Builder(getActivity())
          .setTitle(R.string.AppProtectionPreferenceFragment_inactivity_timeout_interval)
          .setView(view)
          .setNegativeButton(android.R.string.cancel, null)
          .setPositiveButton(android.R.string.ok, (dialog, which) -> {
            int newTimeoutMinutes = Math.max((int) TimeUnit.HOURS.toMinutes(hours.getValue()) +
                                             minutes.getValue()                               +
                                             (int) TimeUnit.SECONDS.toMinutes(seconds.getValue()), 1);

            SilencePreferences.setPassphraseTimeoutInterval(getActivity(), newTimeoutMinutes);
            initializeTimeoutSummary();
          })
          .show();

      return true;
    }
  }

  private class DisablePassphraseClickListener implements Preference.OnPreferenceChangeListener {

    @Override
    public boolean onPreferenceChange(final Preference preference, Object newValue) {
      if (((CheckBoxPreference)preference).isChecked()) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        builder.setTitle(R.string.ApplicationPreferencesActivity_disable_storage_encryption);
        builder.setMessage(R.string.ApplicationPreferencesActivity_warning_this_will_disable_storage_encryption_for_all_messages);
        builder.setIconAttribute(R.attr.dialog_alert_icon);
        builder.setPositiveButton(R.string.ApplicationPreferencesActivity_disable, new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface dialog, int which) {
            try {
              MasterSecretUtil.changeMasterSecretPassphrase(getActivity(),
                                                            masterSecret,
                                                            MasterSecretUtil.UNENCRYPTED_PASSPHRASE);
            } catch (MasterSecretStorageException error) {
              Toast.makeText(getActivity(), R.string.master_secret_storage_error,
                             Toast.LENGTH_LONG).show();
              return;
            }

            SilencePreferences.setPasswordDisabled(getActivity(), true);
            ((CheckBoxPreference)preference).setChecked(false);

            Intent intent = new Intent(getActivity(), KeyCachingService.class);
            intent.setAction(KeyCachingService.DISABLE_ACTION);
            getActivity().startService(intent);
          }
        });
        builder.setNegativeButton(android.R.string.cancel, null);
        builder.show();
      } else {
        Intent intent = new Intent(getActivity(), PassphraseChangeActivity.class);
        startActivity(intent);
      }

      return false;
    }
  }

  public static CharSequence getSummary(Context context) {
    final int    privacySummaryResId = R.string.ApplicationPreferencesActivity_privacy_summary;
    final String onRes               = context.getString(R.string.ApplicationPreferencesActivity_on);
    final String offRes              = context.getString(R.string.ApplicationPreferencesActivity_off);

    if (SilencePreferences.isPasswordDisabled(context)) {
      if (SilencePreferences.isScreenSecurityEnabled(context)) {
        return context.getString(privacySummaryResId, offRes, onRes);
      } else {
        return context.getString(privacySummaryResId, offRes, offRes);
      }
    } else {
      if (SilencePreferences.isScreenSecurityEnabled(context)) {
        return context.getString(privacySummaryResId, onRes, onRes);
      } else {
        return context.getString(privacySummaryResId, onRes, offRes);
      }
    }
  }
}
