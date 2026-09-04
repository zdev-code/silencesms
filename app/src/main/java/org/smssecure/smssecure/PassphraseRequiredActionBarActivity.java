package org.smssecure.smssecure;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import android.util.Log;
import android.view.WindowManager;

import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.crypto.MasterSecretUtil;
import org.smssecure.smssecure.domain.security.ApplicationAccessPolicy;
import org.smssecure.smssecure.domain.security.AuthenticationBootstrapController;
import org.smssecure.smssecure.domain.upgrade.DatabaseUpgradePolicy;
import org.smssecure.smssecure.service.KeyCachingService;
import org.smssecure.smssecure.util.SilencePreferences;
import org.smssecure.smssecure.util.Util;

import java.util.Locale;

public abstract class PassphraseRequiredActionBarActivity extends BaseActionBarActivity implements MasterSecretListener {
  private static final String TAG = PassphraseRequiredActionBarActivity.class.getSimpleName();

  public static final String LOCALE_EXTRA = "locale_extra";

  private final AuthenticationBootstrapController bootstrapController =
      new AuthenticationBootstrapController();

  private BroadcastReceiver clearKeyReceiver;
  private boolean           isVisible;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    Log.w(TAG, "onCreate(" + savedInstanceState + ")");
    onPreCreate();
    final MasterSecret masterSecret = KeyCachingService.getCachedMasterSecret();
    routeApplicationState(masterSecret);
    super.onCreate(savedInstanceState);
    if (!isFinishing()) {
      initializeClearKeyReceiver();
      onCreate(savedInstanceState, masterSecret);
    }
  }

  protected void onPreCreate() {}
  protected void onCreate(Bundle savedInstanceState, @NonNull MasterSecret masterSecret) {}

  @Override
  protected void onResume() {
    Log.w(TAG, "onResume()");
    super.onResume();
    KeyCachingService.registerPassphraseActivityStarted(this);
    isVisible = true;
  }

  @Override
  protected void onPause() {
    Log.w(TAG, "onPause()");
    super.onPause();
    KeyCachingService.registerPassphraseActivityStopped(this);
    isVisible = false;
  }

  @Override
  protected void onDestroy() {
    Log.w(TAG, "onDestroy()");
    super.onDestroy();
    removeClearKeyReceiver(this);
  }

  @Override
  public void onMasterSecretCleared() {
    Log.w(TAG, "onMasterSecretCleared()");
    if (isVisible) routeApplicationState(null);
    else           finish();
  }

  protected <T extends Fragment> T initFragment(@IdRes int target, @NonNull T fragment)
  {
    return initFragment(target, fragment, null, null);
  }

  protected <T extends Fragment> T initFragment(@IdRes int target,
                                                @NonNull T fragment,
                                                @Nullable Locale locale)
  {
    return initFragment(target, fragment, locale, null);
  }

  protected <T extends Fragment> T initFragment(@IdRes int target,
                                                @NonNull T fragment,
                                                @Nullable Locale locale,
                                                @Nullable Bundle extras)
  {
    Bundle args = new Bundle();
    args.putSerializable(LOCALE_EXTRA, locale);

    if (extras != null) {
      args.putAll(extras);
    }

    fragment.setArguments(args);
    getSupportFragmentManager().beginTransaction()
                               .replace(target, fragment)
                               .commit();
    return fragment;
  }

  private void routeApplicationState(MasterSecret masterSecret) {
    Intent intent = getIntentForState(masterSecret, getApplicationState(masterSecret));
    if (intent != null) {
      startActivity(intent);
      finish();
    }
  }

  private Intent getIntentForState(MasterSecret masterSecret, ApplicationAccessPolicy.State state) {
    Log.w(TAG, "routeApplicationState(), state: " + state);

    switch (state) {
    case CREATE_PASSPHRASE: return getCreatePassphraseIntent();
    case PROMPT_PASSPHRASE: return getPromptPassphraseIntent();
    case UPGRADE_DATABASE:  return getUpgradeDatabaseIntent();
    case WELCOME:           return getWelcomeIntent();
    case READY:             return null;
    default:                throw new AssertionError("Unknown access state: " + state);
    }
  }

  private ApplicationAccessPolicy.State getApplicationState(MasterSecret masterSecret) {
    return bootstrapController.evaluate(new AuthenticationBootstrapController.Snapshot(
        shouldDisplayWelcomeActivity(), MasterSecretUtil.isPassphraseInitialized(this),
        masterSecret != null, DatabaseUpgradePolicy.isUpdate(this)));
  }

  private boolean shouldDisplayWelcomeActivity() {
    return SilencePreferences.isFirstRun(this) || !Util.hasMandatoryPermissions(this);
  }

  private Intent getCreatePassphraseIntent() {
    return AuthenticationActivity.createCreatePassphraseIntent(this, getIntent());
  }

  private Intent getPromptPassphraseIntent() {
    return AuthenticationActivity.createPromptPassphraseIntent(this, getIntent());
  }

  private Intent getUpgradeDatabaseIntent() {
    return AuthenticationActivity.createUpgradeDatabaseIntent(this, getIntent());
  }

  private Intent getRoutedIntent(Class<?> destination, Intent target) {
    return BootstrapContinuationStore.getInstance()
        .createBootstrapIntent(this, destination, target);
  }

  private Intent getWelcomeIntent() {
    return AuthenticationActivity.createWelcomeIntent(this, getIntent());
  }

  private void initializeClearKeyReceiver() {
    Log.w(TAG, "initializeClearKeyReceiver()");
    this.clearKeyReceiver = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
        Log.w(TAG, "onReceive() for clear key event");
        onMasterSecretCleared();
      }
    };

    IntentFilter filter = new IntentFilter(KeyCachingService.CLEAR_KEY_EVENT);
    ContextCompat.registerReceiver(this, clearKeyReceiver, filter, KeyCachingService.KEY_PERMISSION, null, ContextCompat.RECEIVER_NOT_EXPORTED);
  }

  private void removeClearKeyReceiver(Context context) {
    if (clearKeyReceiver != null) {
      context.unregisterReceiver(clearKeyReceiver);
      clearKeyReceiver = null;
    }
  }
}
