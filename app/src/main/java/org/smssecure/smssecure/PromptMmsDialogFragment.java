package org.smssecure.smssecure;

import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;

public final class PromptMmsDialogFragment extends DialogFragment {
  private static final String TAG = PromptMmsDialogFragment.class.getName();

  public static void show(@NonNull FragmentManager fragmentManager) {
    if (fragmentManager.findFragmentByTag(TAG) == null) {
      new PromptMmsDialogFragment().show(fragmentManager, TAG);
    }
  }

  public static void show(@NonNull View source) {
    FragmentActivity activity = findActivity(source.getContext());
    if (activity != null) show(activity.getSupportFragmentManager());
  }

  private static FragmentActivity findActivity(Context context) {
    Context current = context;
    while (current instanceof ContextWrapper) {
      if (current instanceof FragmentActivity) return (FragmentActivity) current;
      Context base = ((ContextWrapper) current).getBaseContext();
      if (base == current) break;
      current = base;
    }
    return current instanceof FragmentActivity ? (FragmentActivity) current : null;
  }

  @NonNull
  @Override
  public Dialog onCreateDialog(Bundle savedInstanceState) {
    View content = LayoutInflater.from(requireContext()).inflate(R.layout.prompt_apn_activity, null);
    AlertDialog dialog = new AlertDialog.Builder(requireContext()).setView(content).create();
    content.findViewById(R.id.ok_button).setOnClickListener(view -> {
      FragmentActivity activity = getActivity();
      if (activity instanceof Listener) {
        ((Listener) activity).onMmsPreferencesRequested();
      } else if (activity != null) {
        startActivity(HostNavigationCommand.createIntent(
            activity, HostNavigationCommand.Destination.MMS_PREFERENCES));
      }
      dismiss();
    });
    content.findViewById(R.id.cancel_button).setOnClickListener(view -> dismiss());
    return dialog;
  }

  interface Listener {
    void onMmsPreferencesRequested();
  }
}