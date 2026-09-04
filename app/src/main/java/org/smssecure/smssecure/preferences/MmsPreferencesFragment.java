/**
 * Copyright (C) 2014 Open Whisper Systems
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
package org.smssecure.smssecure.preferences;

import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;
import android.util.Log;

import org.smssecure.smssecure.PassphraseRequiredActionBarActivity;
import org.smssecure.smssecure.R;
import org.smssecure.smssecure.components.CustomDefaultPreference;
import org.smssecure.smssecure.mms.LegacyMmsConnection;
import org.smssecure.smssecure.util.SilencePreferences;
import org.smssecure.smssecure.ui.LifecycleStateCollector;
import org.smssecure.smssecure.ui.mmspreferences.MmsPreferencesUiState;
import org.smssecure.smssecure.ui.mmspreferences.MmsPreferencesViewModel;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class MmsPreferencesFragment extends CorrectedPreferenceFragment {

  private static final String TAG = MmsPreferencesFragment.class.getSimpleName();

  private MmsPreferencesViewModel viewModel;

  @Override
  public void onCreate(Bundle paramBundle) {
    super.onCreate(paramBundle);

    ((PassphraseRequiredActionBarActivity) getActivity()).getSupportActionBar()
        .setTitle(R.string.preferences__advanced_mms_access_point_names);

    SilencePreferences.setManualMmsSettingsAsSeen(getActivity());
    viewModel = new ViewModelProvider(this).get(MmsPreferencesViewModel.class);
    LifecycleStateCollector.collect(this, viewModel.getState(), this::render);
  }

  @Override
  public void onCreatePreferences(@Nullable Bundle savedInstanceState, String rootKey) {
    addPreferencesFromResource(R.xml.preferences_manual_mms);
  }

  @Override
  public void onResume() {
    super.onResume();
    viewModel.load();
  }

  private void render(MmsPreferencesUiState state) {
    if (state.isFailed()) Log.w(TAG, "Unable to load default APN settings");
    applyApnDefaults(state.getDefaults());
  }

  private void applyApnDefaults(LegacyMmsConnection.Apn apnDefaults) {
    if (apnDefaults == null || !isAdded() || getPreferenceScreen() == null) return;

    ((CustomDefaultPreference)findPreference(SilencePreferences.MMSC_HOST_PREF))
        .setValidator(new CustomDefaultPreference.CustomDefaultPreferenceDialogFragmentCompat.UriValidator())
        .setDefaultValue(apnDefaults.getMmsc());

    ((CustomDefaultPreference)findPreference(SilencePreferences.MMSC_PROXY_HOST_PREF))
        .setValidator(new CustomDefaultPreference.CustomDefaultPreferenceDialogFragmentCompat.HostnameValidator())
        .setDefaultValue(apnDefaults.getProxy());

    ((CustomDefaultPreference)findPreference(SilencePreferences.MMSC_PROXY_PORT_PREF))
        .setValidator(new CustomDefaultPreference.CustomDefaultPreferenceDialogFragmentCompat.PortValidator())
        .setDefaultValue(apnDefaults.getPort());

    ((CustomDefaultPreference)findPreference(SilencePreferences.MMSC_USERNAME_PREF))
        .setDefaultValue(apnDefaults.getPort());

    ((CustomDefaultPreference)findPreference(SilencePreferences.MMSC_PASSWORD_PREF))
        .setDefaultValue(apnDefaults.getPassword());

    ((CustomDefaultPreference)findPreference(SilencePreferences.MMS_USER_AGENT))
        .setDefaultValue(LegacyMmsConnection.USER_AGENT);
  }

}
