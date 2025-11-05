package org.smssecure.smssecure.preferences;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import org.smssecure.smssecure.components.CustomDefaultPreference;
import org.smssecure.smssecure.preferences.widgets.ColorPickerPreference;
import org.smssecure.smssecure.preferences.widgets.ColorPickerPreferenceDialogFragmentCompat;
import org.smssecure.smssecure.preferences.widgets.RingtonePreference;
import org.smssecure.smssecure.preferences.widgets.RingtonePreferenceDialogFragmentCompat;
import org.smssecure.smssecure.components.OutgoingSmsPreference;
import org.smssecure.smssecure.components.OutgoingSmsPreference.OutgoingSmsPreferenceDialogFragmentCompat;

public abstract class CorrectedPreferenceFragment extends PreferenceFragmentCompat {

  private static final String PREFERENCE_DIALOG_FRAGMENT_TAG = "androidx.preference.PreferenceFragment.DIALOG";

  @Override
  public void onCreate(Bundle icicle) {
    super.onCreate(icicle);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);

    View listView = view.findViewById(android.R.id.list);
    if (listView != null) {
      listView.setPadding(0, 0, 0, 0);
    }
  }

  @Override
  public void onDisplayPreferenceDialog(Preference preference) {
    DialogFragment dialogFragment = null;

    if (preference instanceof RingtonePreference) {
      dialogFragment = RingtonePreferenceDialogFragmentCompat.newInstance(preference.getKey());
    } else if (preference instanceof ColorPickerPreference) {
      dialogFragment = ColorPickerPreferenceDialogFragmentCompat.newInstance(preference.getKey());
    } else if (preference instanceof CustomDefaultPreference) {
      dialogFragment = CustomDefaultPreference.CustomDefaultPreferenceDialogFragmentCompat.newInstance(preference.getKey());
    } else if (preference instanceof OutgoingSmsPreference) {
      dialogFragment = OutgoingSmsPreferenceDialogFragmentCompat.newInstance(preference.getKey());
    }

    if (dialogFragment != null) {
      dialogFragment.show(getParentFragmentManager(), PREFERENCE_DIALOG_FRAGMENT_TAG);
    } else {
      super.onDisplayPreferenceDialog(preference);
    }
  }


}
