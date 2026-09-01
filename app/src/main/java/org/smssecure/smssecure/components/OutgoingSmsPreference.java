package org.smssecure.smssecure.components;

import android.content.Context;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.View;
import android.widget.CheckBox;

import androidx.annotation.NonNull;
import androidx.preference.DialogPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceDialogFragmentCompat;

import org.smssecure.smssecure.R;
import org.smssecure.smssecure.util.SilencePreferences;

public class OutgoingSmsPreference extends DialogPreference {

  public OutgoingSmsPreference(Context context, AttributeSet attrs) {
    super(context, attrs);
    setPersistent(false);
    setDialogLayoutResource(R.layout.outgoing_sms_preference);
  }

  public static class OutgoingSmsPreferenceDialogFragmentCompat extends PreferenceDialogFragmentCompat {

    private CheckBox dataUsers;
    private CheckBox askForFallback;
    private CheckBox neverFallbackMms;
    private CheckBox nonDataUsers;

    public static OutgoingSmsPreferenceDialogFragmentCompat newInstance(String key) {
      OutgoingSmsPreferenceDialogFragmentCompat fragment = new OutgoingSmsPreferenceDialogFragmentCompat();
      Bundle bundle = new Bundle(1);
      bundle.putString(PreferenceDialogFragmentCompat.ARG_KEY, key);
      fragment.setArguments(bundle);
      return fragment;
    }

    @Override
    protected void onBindDialogView(@NonNull View view) {
      super.onBindDialogView(view);

      dataUsers        = view.findViewById(R.id.data_users);
      askForFallback   = view.findViewById(R.id.ask_before_fallback_data);
      neverFallbackMms = view.findViewById(R.id.never_send_mms);
      nonDataUsers     = view.findViewById(R.id.non_data_users);

      Context context = requireContext();

      dataUsers.setChecked(SilencePreferences.isFallbackSmsAllowed(context));
      askForFallback.setChecked(SilencePreferences.isFallbackSmsAskRequired(context));
      neverFallbackMms.setChecked(!SilencePreferences.isFallbackMmsEnabled(context));
      nonDataUsers.setChecked(SilencePreferences.isDirectSmsAllowed(context));

      dataUsers.setOnClickListener(v -> updateEnabledViews());

      updateEnabledViews();
    }

    private void updateEnabledViews() {
      if (askForFallback != null && neverFallbackMms != null && dataUsers != null) {
        boolean enabled = dataUsers.isChecked();
        askForFallback.setEnabled(enabled);
        neverFallbackMms.setEnabled(enabled);
      }
    }

    @Override
    public void onDialogClosed(boolean positiveResult) {
      if (!positiveResult || dataUsers == null || askForFallback == null || neverFallbackMms == null || nonDataUsers == null) {
        return;
      }

      Context context = requireContext();

      SilencePreferences.setFallbackSmsAllowed(context, dataUsers.isChecked());
      SilencePreferences.setFallbackSmsAskRequired(context, askForFallback.isChecked());
      SilencePreferences.setDirectSmsAllowed(context, nonDataUsers.isChecked());
      SilencePreferences.setFallbackMmsEnabled(context, !neverFallbackMms.isChecked());

      Preference preference = getPreference();
      Preference.OnPreferenceChangeListener listener = preference.getOnPreferenceChangeListener();
      if (listener != null) {
        listener.onPreferenceChange(preference, null);
      }
    }
  }
}
