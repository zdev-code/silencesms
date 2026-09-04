package org.smssecure.smssecure;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.WindowManager;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import org.smssecure.smssecure.permissions.Permissions;
import org.smssecure.smssecure.service.KeyCachingService;
import org.smssecure.smssecure.util.ActivityTransitionCompat;
import org.smssecure.smssecure.util.DynamicIntroTheme;
import org.smssecure.smssecure.util.DynamicLanguage;
import org.smssecure.smssecure.util.DynamicTheme;

import java.util.Set;

public final class AuthenticationActivity extends BaseActionBarActivity
  implements WelcomeFragment.Callback, PassphraseCreateFragment.Callback,
             PassphrasePromptFragment.Callback, DatabaseUpgradeFragment.Callback,
             DatabaseMigrationFragment.Callback, PassphraseChangeFragment.Callback {

  static final String EXTRA_SURFACE =
      "org.smssecure.smssecure.authentication.SURFACE";

  enum Surface {
    WELCOME,
    CREATE_PASSPHRASE,
    PROMPT_PASSPHRASE,
    UPGRADE_DATABASE,
    DATABASE_MIGRATION,
    CHANGE_PASSPHRASE
  }

  private boolean completionDispatched;
  private Surface surface;
  private BroadcastReceiver clearKeyReceiver;
  private final DynamicIntroTheme dynamicIntroTheme = new DynamicIntroTheme();
  private final DynamicTheme dynamicTheme = new DynamicTheme();
  private final DynamicLanguage dynamicLanguage = new DynamicLanguage();

  static Intent createWelcomeIntent(@NonNull Context context, @NonNull Intent target) {
    return createIntent(context, target, Surface.WELCOME);
  }

  static Intent createCreatePassphraseIntent(@NonNull Context context, @NonNull Intent target) {
    return createIntent(context, target, Surface.CREATE_PASSPHRASE);
  }

  static Intent createPromptPassphraseIntent(@NonNull Context context, @NonNull Intent target) {
    return createIntent(context, target, Surface.PROMPT_PASSPHRASE);
  }

  static Intent createUpgradeDatabaseIntent(@NonNull Context context, @NonNull Intent target) {
    return createIntent(context, target, Surface.UPGRADE_DATABASE);
  }

  public static Intent createDatabaseMigrationIntent(@NonNull Context context,
                                                      @NonNull Intent target) {
    return createIntent(context, target, Surface.DATABASE_MIGRATION);
  }

  public static Intent createChangePassphraseIntent(@NonNull Context context) {
    Intent target = HostNavigationCommand.createIntent(
        context, HostNavigationCommand.Destination.APP_PROTECTION_PREFERENCES);
    return BootstrapContinuationStore.getInstance()
        .createUnlockedIntent(context, AuthenticationActivity.class, target,
                              owner(Surface.CHANGE_PASSPHRASE))
        .putExtra(EXTRA_SURFACE, Surface.CHANGE_PASSPHRASE.name());
  }

  private static Intent createIntent(Context context, Intent target, Surface surface) {
    return BootstrapContinuationStore.getInstance()
      .createBootstrapIntent(context, AuthenticationActivity.class, target, owner(surface))
        .putExtra(EXTRA_SURFACE, surface.name());
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    Surface requestedSurface = validatedSurface(getIntent());
    if (requestedSurface == Surface.CREATE_PASSPHRASE) {
      setTheme(R.style.Silence_LightIntroTheme);
    } else if (requestedSurface == Surface.PROMPT_PASSPHRASE) {
      dynamicIntroTheme.onCreate(this);
      dynamicLanguage.onCreate(this);
    } else if (requestedSurface == Surface.UPGRADE_DATABASE ||
               requestedSurface == Surface.DATABASE_MIGRATION) {
      setTheme(R.style.NoAnimation_Theme_AppCompat_Light_DarkActionBar);
    } else if (requestedSurface == Surface.CHANGE_PASSPHRASE) {
      dynamicTheme.onCreate(this);
      dynamicLanguage.onCreate(this);
    }
    super.onCreate(savedInstanceState);

    if (requestedSurface == null) {
      failClosed();
      return;
    }
    surface = requestedSurface;
    if (surface == Surface.CHANGE_PASSPHRASE && !hasLiveChangeAuthority()) {
      failClosed();
      return;
    }

    getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(
        surface == Surface.DATABASE_MIGRATION || surface == Surface.CHANGE_PASSPHRASE) {
      @Override
      public void handleOnBackPressed() {
        if (surface == Surface.DATABASE_MIGRATION) return;
        Fragment fragment = getSupportFragmentManager().findFragmentByTag(surface.name());
        if (fragment instanceof PassphraseChangeFragment) {
          ((PassphraseChangeFragment) fragment).clearSensitiveState();
        }
        completionDispatched = true;
        BootstrapContinuationStore.getInstance().discard(getIntent());
        finish();
      }
    });

    if (surface == Surface.WELCOME) setStatusBarColorCompat(0xFF7568AE);
    if (surface == Surface.PROMPT_PASSPHRASE) {
      getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
    }
    if (surface == Surface.CHANGE_PASSPHRASE) {
      setTitle(R.string.AndroidManifest__change_passphrase);
      initializeClearKeyReceiver();
    }
    if (savedInstanceState == null) {
      getSupportFragmentManager().beginTransaction()
          .replace(android.R.id.content, createFragment(surface), surface.name())
          .commit();
    } else {
      Fragment restored = getSupportFragmentManager().findFragmentByTag(surface.name());
      if (getSupportFragmentManager().getFragments().size() != 1 ||
          !isExpectedFragment(surface, restored)) {
        failClosed();
      }
    }
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (surface == Surface.PROMPT_PASSPHRASE) {
      dynamicIntroTheme.onResume(this);
      dynamicLanguage.onResume(this);
    } else if (surface == Surface.CHANGE_PASSPHRASE) {
      dynamicTheme.onResume(this);
      dynamicLanguage.onResume(this);
    }
  }

  @Override
  public void onRequestPermissionsResult(int requestCode,
                                         @NonNull String[] permissions,
                                         @NonNull int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    Permissions.onRequestPermissionsResult(this, requestCode, permissions, grantResults);
  }

  @Override
  public void onWelcomeCompleted() {
    completeAuthentication();
  }

  @Override
  public void onPassphraseCreateCompleted() {
    completeAuthentication();
  }

  @Override
  public void onPassphraseCreateCancelled() {
    if (!isFinishing()) finish();
  }

  @Override
  public void onPassphrasePromptCompleted() {
    completeAuthentication();
  }

  @Override
  public void onDatabaseUpgradeCompleted(@NonNull DatabaseUpgradeFragment fragment) {
    if (isCurrentSurfaceFragment(Surface.UPGRADE_DATABASE, fragment)) completeAuthentication();
  }

  @Override
  public void onDatabaseUpgradeInvalidated(@NonNull DatabaseUpgradeFragment fragment) {
    if (isCurrentSurfaceFragment(Surface.UPGRADE_DATABASE, fragment)) failClosed();
  }

  @Override
  public void onDatabaseMigrationCompleted(@NonNull DatabaseMigrationFragment fragment) {
    if (isCurrentSurfaceFragment(Surface.DATABASE_MIGRATION, fragment)) completeAuthentication();
  }

  @Override
  public void onPassphraseChangeCompleted(@NonNull PassphraseChangeFragment fragment) {
    if (!isCurrentSurfaceFragment(Surface.CHANGE_PASSPHRASE, fragment)) return;
    try {
      BootstrapContinuationStore.getInstance().rebindToCurrentGeneration(
          getIntent(), owner(Surface.CHANGE_PASSPHRASE));
      completeAuthentication();
    } catch (BootstrapContinuationStore.InvalidContinuationException exception) {
      failClosed();
    }
  }

  @Override
  public void onPassphraseChangeCancelled(@NonNull PassphraseChangeFragment fragment) {
    if (!isCurrentSurfaceFragment(Surface.CHANGE_PASSPHRASE, fragment)) return;
    completionDispatched = true;
    BootstrapContinuationStore.getInstance().discard(getIntent());
    finish();
  }

  @Override
  public boolean onPrepareOptionsMenu(Menu menu) {
    PassphrasePromptFragment prompt = promptFragment();
    if (prompt != null) prompt.populateOptionsMenu(menu, getMenuInflater());
    return super.onPrepareOptionsMenu(menu);
  }

  @Override
  public boolean onOptionsItemSelected(@NonNull MenuItem item) {
    PassphrasePromptFragment prompt = promptFragment();
    if (prompt != null && prompt.handleOptionsItem(item)) return true;
    return super.onOptionsItemSelected(item);
  }

  private void completeAuthentication() {
    if (completionDispatched || isFinishing()) return;
    completionDispatched = true;
    Intent target;
    try {
      target = BootstrapContinuationStore.getInstance()
          .consume(getIntent(), owner(surface));
    } catch (BootstrapContinuationStore.InvalidContinuationException exception) {
      target = new Intent(this, ConversationListActivity.class);
    }
    startActivity(target);
    if (surface == Surface.WELCOME) {
      ActivityTransitionCompat.overrideOpen(this, R.anim.slide_from_right, R.anim.fade_scale_out);
    }
    finish();
  }

  static boolean isAllowedWelcomeIntent(Intent intent) {
    return validatedSurface(intent) == Surface.WELCOME;
  }

  static boolean isAllowedCreatePassphraseIntent(Intent intent) {
    return validatedSurface(intent) == Surface.CREATE_PASSPHRASE;
  }

  static boolean isAllowedPromptPassphraseIntent(Intent intent) {
    return validatedSurface(intent) == Surface.PROMPT_PASSPHRASE;
  }

  static boolean isAllowedUpgradeDatabaseIntent(Intent intent) {
    return validatedSurface(intent) == Surface.UPGRADE_DATABASE;
  }

  static boolean isAllowedDatabaseMigrationIntent(Intent intent) {
    return validatedSurface(intent) == Surface.DATABASE_MIGRATION;
  }

  static boolean isAllowedChangePassphraseIntent(Intent intent) {
    return validatedSurface(intent) == Surface.CHANGE_PASSPHRASE;
  }

  private static Surface validatedSurface(Intent intent) {
    if (intent == null || intent.getComponent() == null ||
        !AuthenticationActivity.class.getName().equals(intent.getComponent().getClassName()) ||
        intent.getAction() != null || intent.getData() != null || intent.getType() != null ||
        intent.getClipData() != null || intent.getSelector() != null ||
        intent.getCategories() != null ||
        (intent.getFlags() & ~Intent.FLAG_ACTIVITY_NEW_TASK) != 0) {
      return null;
    }

    Bundle extras = intent.getExtras();
    if (extras == null || !extras.keySet().equals(Set.of(
        EXTRA_SURFACE,
        BootstrapContinuationStore.EXTRA_DESTINATION,
        BootstrapContinuationStore.EXTRA_TOKEN))) {
      return null;
    }

    try {
      String encodedSurface = extras.getString(EXTRA_SURFACE);
      String destination = extras.getString(BootstrapContinuationStore.EXTRA_DESTINATION);
      String token = extras.getString(BootstrapContinuationStore.EXTRA_TOKEN);
      if (encodedSurface == null ||
          !BootstrapContinuationStore.hasValidRouteFields(destination, token)) return null;
      return Surface.valueOf(encodedSurface);
    } catch (RuntimeException exception) {
      return null;
    }
  }

  static String owner(Surface surface) {
    return AuthenticationActivity.class.getName() + ":" + surface.name();
  }

  private static Fragment createFragment(Surface surface) {
    switch (surface) {
      case WELCOME: return new WelcomeFragment();
      case CREATE_PASSPHRASE: return new PassphraseCreateFragment();
      case PROMPT_PASSPHRASE: return new PassphrasePromptFragment();
      case UPGRADE_DATABASE: return new DatabaseUpgradeFragment();
      case DATABASE_MIGRATION: return new DatabaseMigrationFragment();
      case CHANGE_PASSPHRASE: return new PassphraseChangeFragment();
      default: throw new AssertionError("Unknown authentication surface: " + surface);
    }
  }

  private static boolean isExpectedFragment(Surface surface, Fragment fragment) {
    return surface == Surface.WELCOME && fragment instanceof WelcomeFragment ||
        surface == Surface.CREATE_PASSPHRASE && fragment instanceof PassphraseCreateFragment ||
          surface == Surface.PROMPT_PASSPHRASE && fragment instanceof PassphrasePromptFragment ||
          surface == Surface.UPGRADE_DATABASE && fragment instanceof DatabaseUpgradeFragment ||
          surface == Surface.DATABASE_MIGRATION && fragment instanceof DatabaseMigrationFragment ||
          surface == Surface.CHANGE_PASSPHRASE && fragment instanceof PassphraseChangeFragment;
  }

        private boolean isCurrentSurfaceFragment(Surface expected, Fragment fragment) {
          return !completionDispatched && !isFinishing() && surface == expected &&
          fragment == getSupportFragmentManager().findFragmentByTag(expected.name()) &&
          fragment.getLifecycle().getCurrentState().isAtLeast(
          androidx.lifecycle.Lifecycle.State.STARTED);
        }

  private PassphrasePromptFragment promptFragment() {
    if (surface != Surface.PROMPT_PASSPHRASE) return null;
    Fragment fragment = getSupportFragmentManager().findFragmentByTag(surface.name());
    return fragment instanceof PassphrasePromptFragment
        ? (PassphrasePromptFragment) fragment : null;
  }

  private boolean hasLiveChangeAuthority() {
    try {
      BootstrapContinuationStore.getInstance().requireLive(
          getIntent(), owner(Surface.CHANGE_PASSPHRASE), true);
      return true;
    } catch (BootstrapContinuationStore.InvalidContinuationException exception) {
      return false;
    }
  }

  private void initializeClearKeyReceiver() {
    clearKeyReceiver = new BroadcastReceiver() {
      @Override public void onReceive(Context context, Intent intent) {
        completionDispatched = true;
        Fragment fragment = getSupportFragmentManager().findFragmentByTag(
            Surface.CHANGE_PASSPHRASE.name());
        if (fragment instanceof PassphraseChangeFragment) {
          ((PassphraseChangeFragment) fragment).clearSensitiveState();
        }
        BootstrapContinuationStore.getInstance().discard(getIntent());
        finish();
      }
    };
    ContextCompat.registerReceiver(this, clearKeyReceiver,
        new IntentFilter(KeyCachingService.CLEAR_KEY_EVENT), KeyCachingService.KEY_PERMISSION,
        null, ContextCompat.RECEIVER_NOT_EXPORTED);
  }

  @Override
  protected void onDestroy() {
    if (clearKeyReceiver != null) {
      unregisterReceiver(clearKeyReceiver);
      clearKeyReceiver = null;
    }
    super.onDestroy();
  }

  private void failClosed() {
    BootstrapContinuationStore.getInstance().discard(getIntent());
    setIntent(new Intent(this, AuthenticationActivity.class));
    finish();
  }
}