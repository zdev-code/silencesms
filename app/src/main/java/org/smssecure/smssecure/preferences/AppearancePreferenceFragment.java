package org.smssecure.smssecure.preferences;

import android.content.Context;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.preference.ListPreference;

import org.smssecure.smssecure.AppearancePreferenceHost;
import org.smssecure.smssecure.R;
import org.smssecure.smssecure.util.SilencePreferences;

import java.util.Arrays;

public class AppearancePreferenceFragment extends ListSummaryPreferenceFragment {

  @Override
  public void onCreate(Bundle paramBundle) {
    super.onCreate(paramBundle);

    this.findPreference(SilencePreferences.THEME_PREF).setOnPreferenceChangeListener(new AppearanceListener());
    this.findPreference(SilencePreferences.LANGUAGE_PREF).setOnPreferenceChangeListener(new AppearanceListener());
    initializeListSummary((ListPreference)findPreference(SilencePreferences.THEME_PREF));
    initializeListSummary((ListPreference)findPreference(SilencePreferences.LANGUAGE_PREF));
  }

  @Override
  public void onCreatePreferences(@Nullable Bundle savedInstanceState, String rootKey) {
    addPreferencesFromResource(R.xml.preferences_appearance);
  }

  public static CharSequence getSummary(Context context) {
    String[] languageEntries     = context.getResources().getStringArray(R.array.language_entries);
    String[] languageEntryValues = context.getResources().getStringArray(R.array.language_values);
    String[] themeEntries        = context.getResources().getStringArray(R.array.pref_theme_entries);
    String[] themeEntryValues    = context.getResources().getStringArray(R.array.pref_theme_values);

    int langIndex  = Arrays.asList(languageEntryValues).indexOf(SilencePreferences.getLanguage(context));
    int themeIndex = Arrays.asList(themeEntryValues).indexOf(SilencePreferences.getTheme(context));

    if (langIndex == -1)  langIndex = 0;
    if (themeIndex == -1) themeIndex = 0;

    return context.getString(R.string.ApplicationPreferencesActivity_appearance_summary,
                             themeEntries[themeIndex],
                             languageEntries[langIndex]);
  }

  private class AppearanceListener extends ListSummaryListener {
    @Override
    public boolean onPreferenceChange(androidx.preference.Preference preference, Object value) {
      boolean accepted = super.onPreferenceChange(preference, value);
      if (accepted && getActivity() instanceof AppearancePreferenceHost) {
        ((AppearancePreferenceHost) getActivity()).onAppearancePreferenceChanged(preference.getKey());
      }
      return accepted;
    }
  }
}
