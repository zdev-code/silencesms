package org.smssecure.smssecure;

import android.Manifest;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import org.smssecure.smssecure.permissions.Permissions;
import org.smssecure.smssecure.util.SilencePreferences;

import java.util.Arrays;

public final class WelcomeFragment extends Fragment {

  interface Callback {
    void onWelcomeCompleted();
  }

  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater,
                           @Nullable ViewGroup container,
                           @Nullable Bundle savedInstanceState) {
    int layout = SilencePreferences.isFirstRun(requireContext())
        ? R.layout.welcome_activity
        : R.layout.welcome_activity_missing_perms;
    return inflater.inflate(layout, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);
    boolean firstRun = SilencePreferences.isFirstRun(requireContext());
    view.findViewById(R.id.welcome_continue_button).setOnClickListener(ignored -> {
      if (firstRun) requestFirstRunPermissions();
      else requestMissingPermissions();
    });
  }

  private void requestFirstRunPermissions() {
    Permissions.with(requireActivity())
        .request(withNotificationPermissionIfRequired(
            withPhoneNumberPermissionIfRequired(Manifest.permission.WRITE_CONTACTS,
                                                Manifest.permission.READ_CONTACTS,
                                                Manifest.permission.READ_PHONE_STATE,
                                                Manifest.permission.RECEIVE_SMS,
                                                Manifest.permission.RECEIVE_MMS,
                                                Manifest.permission.READ_SMS,
                                                Manifest.permission.SEND_SMS)))
        .ifNecessary()
        .withRationaleDialog(getString(
            R.string.WelcomeActivity_silence_needs_access_to_your_contacts_phone_status_and_sms),
            R.drawable.ic_contacts_white_48dp, R.drawable.ic_phone_white_48dp)
        .onAnyResult(() -> {
          Context context = requireContext();
          SilencePreferences.setFirstRun(context);
          SilencePreferences.setPermissionsAsked(context);
          callback().onWelcomeCompleted();
        })
        .execute();
  }

  private void requestMissingPermissions() {
    Permissions.with(requireActivity())
        .request(withNotificationPermissionIfRequired(Manifest.permission.READ_PHONE_STATE,
                                                       Manifest.permission.RECEIVE_SMS,
                                                       Manifest.permission.RECEIVE_MMS))
        .ifNecessary()
        .withPermanentDenialDialog(getString(
            R.string.WelcomeActivity_silence_requires_the_phone_and_sms_permissions_in_order_to_work_but_it_has_been_permanently_denied))
        .onSomeGranted(permissions -> callback().onWelcomeCompleted())
        .execute();
  }

  private Callback callback() {
    if (!(requireActivity() instanceof Callback)) {
      throw new IllegalStateException("Welcome host must implement callback");
    }
    return (Callback) requireActivity();
  }

  private static String[] withNotificationPermissionIfRequired(String... basePermissions) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return basePermissions;
    for (String permission : basePermissions) {
      if (Manifest.permission.POST_NOTIFICATIONS.equals(permission)) return basePermissions;
    }
    String[] extended = Arrays.copyOf(basePermissions, basePermissions.length + 1);
    extended[basePermissions.length] = Manifest.permission.POST_NOTIFICATIONS;
    return extended;
  }

  private static String[] withPhoneNumberPermissionIfRequired(String... basePermissions) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return basePermissions;
    String[] extended = Arrays.copyOf(basePermissions, basePermissions.length + 1);
    extended[basePermissions.length] = Manifest.permission.READ_PHONE_NUMBERS;
    return extended;
  }
}