package org.smssecure.smssecure;

import static org.assertj.core.api.Assertions.assertThat;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.database.SmsMigrator.ProgressDescription;
import org.smssecure.smssecure.domain.security.UnlockSession;
import org.smssecure.smssecure.service.ApplicationMigrationService;
import org.smssecure.smssecure.service.KeyCachingService;
import org.smssecure.smssecure.ui.databasemigration.DatabaseMigrationController;

import java.util.Locale;

import javax.crypto.spec.SecretKeySpec;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 35)
public class DatabaseMigrationFragmentTest {
  private final Context context = ApplicationProvider.getApplicationContext();
  private final FakeEnvironment environment = new FakeEnvironment();
  private ServiceController<ApplicationMigrationService> serviceController;
  private ApplicationMigrationService service;

  @Before
  public void setUp() {
    setImported(false);
    KeyCachingService.primeMasterSecret(secret((byte) 1));
    serviceController = Robolectric.buildService(ApplicationMigrationService.class).create();
    service = serviceController.get();
    DatabaseMigrationFragment.setControllerFactoryForTests(
        fragment -> new DatabaseMigrationController(environment));
    BootstrapContinuationStore.getInstance().clear();
  }

  @After
  public void tearDown() {
    DatabaseMigrationFragment.resetControllerFactoryForTests();
    BootstrapContinuationStore.getInstance().clear();
    setImported(false);
    MasterSecret current = KeyCachingService.getSecretSnapshot().getSecret();
    if (current != null) KeyCachingService.discardPrimedMasterSecret(current);
    serviceController.destroy();
  }

  @Test
  public void rendersIdleProgressAndMalformedProgressSafelyAndStartsCurrentGeneration() {
    AuthenticationActivity activity = launchController().get();
    environment.connect(service);
    idleMainLooper();

    assertThat(activity.findViewById(R.id.prompt_layout).getVisibility()).isEqualTo(View.VISIBLE);
    assertThat(activity.findViewById(R.id.progress_layout).getVisibility()).isEqualTo(View.GONE);

    service.progressUpdate(new ProgressDescription(10, 5, 4, 2));
    idleMainLooper();
    ProgressBar progress = activity.findViewById(R.id.import_progress);
    assertThat(activity.<TextView>findViewById(R.id.import_status).getText().toString())
        .isEqualTo("5/10");
    assertThat(progress.getProgress()).isEqualTo(progress.getMax() / 2);
    assertThat(progress.getSecondaryProgress()).isEqualTo(progress.getMax() / 2);

    service.progressUpdate(new ProgressDescription(0, -5, 0, 20));
    idleMainLooper();
    assertThat(activity.<TextView>findViewById(R.id.import_status).getText().toString())
        .isEqualTo("0/0");
    assertThat(progress.getProgress()).isZero();
    assertThat(progress.getSecondaryProgress()).isZero();

    activity.findViewById(R.id.import_button).performClick();
    assertThat(environment.startedSession).isNotNull();
    assertThat(environment.startedSession.getGeneration())
        .isEqualTo(KeyCachingService.getSecretSnapshot().getGeneration());
  }

  @Test
  public void migrationServiceIntentContainsOnlyPrimitiveGeneration() {
    UnlockSession session = UnlockSession.capture();
    Intent intent = ApplicationMigrationService.createMigrationIntent(context, session);

    assertThat(intent.getComponent().getClassName())
        .isEqualTo(ApplicationMigrationService.class.getName());
    assertThat(intent.getAction()).isEqualTo(ApplicationMigrationService.MIGRATE_DATABASE);
    assertThat(intent.getExtras()).isNotNull();
    assertThat(intent.getExtras().keySet()).hasSize(1);
    String key = intent.getExtras().keySet().iterator().next();
    assertThat(intent.getLongExtra(key, -1L)).isEqualTo(session.getGeneration());
    assertContainsNoSensitiveState(intent.getExtras());
  }

  @Test
  public void skipMarksImportedAndConsumesContinuationOnce() {
    AuthenticationActivity activity = launchController().get();

    activity.findViewById(R.id.skip_button).performClick();

    assertThat(environment.skipCount).isEqualTo(1);
    assertThat(ApplicationMigrationService.isDatabaseImported(context)).isTrue();
    assertArchive(activity);
    assertThat(Shadows.shadowOf(activity).getNextStartedActivity()).isNull();
  }

  @Test
  public void duplicateAndStaleGenerationCompletionFailSafely() {
    AuthenticationActivity duplicate = launchController().get();
    environment.complete();
    environment.complete();
    idleMainLooper();
    assertArchive(duplicate);
    assertThat(Shadows.shadowOf(duplicate).getNextStartedActivity()).isNull();

    environment.resetCallbacks();
    AuthenticationActivity stale = launchController().get();
    KeyCachingService.primeMasterSecret(secret((byte) 2));
    environment.complete();
    idleMainLooper();
    assertFreshInbox(stale);
  }

  @Test
  public void stoppedCallbackCannotNavigateAndDurableCompletionReplaysOnRestart() {
    ActivityController<AuthenticationActivity> controller = launchController();
    AuthenticationActivity activity = controller.get();
    BroadcastReceiver stale = environment.receiver;

    controller.pause().stop();
    stale.onReceive(context, new Intent());
    idleMainLooper();
    assertThat(Shadows.shadowOf(activity).getNextStartedActivity()).isNull();

    setImported(true);
    controller.start().resume().visible();
    idleMainLooper();
    assertArchive(activity);
  }

  @Test
  public void recreationRebindsToServiceStateWithoutSavingProgress() {
    ActivityController<AuthenticationActivity> controller = launchController();
    environment.connect(service);
    service.progressUpdate(new ProgressDescription(8, 3, 2, 1));
    idleMainLooper();

    controller.recreate();
    AuthenticationActivity recreated = controller.get();
    recreated.getSupportFragmentManager().executePendingTransactions();
    environment.connect(service);
    idleMainLooper();

    assertThat(environment.bindCount).isEqualTo(2);
    assertThat(environment.unbindCount).isEqualTo(1);
    assertThat(environment.registerCount).isEqualTo(2);
    assertThat(environment.unregisterCount).isEqualTo(1);
    assertThat(recreated.<TextView>findViewById(R.id.import_status).getText().toString())
        .isEqualTo("3/8");

    Bundle savedState = new Bundle();
    controller.saveInstanceState(savedState);
    assertContainsNoSensitiveState(savedState);
  }

  @Test
  public void onlyMigrationSurfaceBlocksBack() {
    AuthenticationActivity migration = launchController().get();
    migration.getOnBackPressedDispatcher().onBackPressed();
    assertThat(migration.isFinishing()).isFalse();

    Intent welcomeIntent = AuthenticationActivity.createWelcomeIntent(
        context, new Intent(context, ConversationListActivity.class));
    AuthenticationActivity welcome = Robolectric.buildActivity(
        AuthenticationActivity.class, welcomeIntent).create().start().resume().visible().get();
    welcome.getSupportFragmentManager().executePendingTransactions();
    welcome.getOnBackPressedDispatcher().onBackPressed();
    assertThat(welcome.isFinishing()).isTrue();
  }

  @Test
  public void coldMigrationContinuationFailsClosedOnCompletion() {
    Intent cold = new Intent(context, AuthenticationActivity.class)
        .putExtra(AuthenticationActivity.EXTRA_SURFACE,
                  AuthenticationActivity.Surface.DATABASE_MIGRATION.name())
        .putExtra(BootstrapContinuationStore.EXTRA_DESTINATION, "CONVERSATION_LIST")
        .putExtra(BootstrapContinuationStore.EXTRA_TOKEN, "missing-token");
    AuthenticationActivity activity = Robolectric.buildActivity(
        AuthenticationActivity.class, cold).create().start().resume().visible().get();
    activity.getSupportFragmentManager().executePendingTransactions();
    DatabaseMigrationFragment fragment = (DatabaseMigrationFragment)
        activity.getSupportFragmentManager().findFragmentByTag(
            AuthenticationActivity.Surface.DATABASE_MIGRATION.name());

    activity.onDatabaseMigrationCompleted(fragment);
    assertFreshInbox(activity);
  }

  private ActivityController<AuthenticationActivity> launchController() {
    Intent intent = AuthenticationActivity.createDatabaseMigrationIntent(
        context, HostNavigationCommand.createIntent(
            context, HostNavigationCommand.Destination.ARCHIVE));
    ActivityController<AuthenticationActivity> controller = Robolectric.buildActivity(
        AuthenticationActivity.class, intent).create().start().resume().visible();
    controller.get().getSupportFragmentManager().executePendingTransactions();
    return controller;
  }

  private static void idleMainLooper() {
    Shadows.shadowOf(Looper.getMainLooper()).idle();
  }

  private void setImported(boolean imported) {
    context.getSharedPreferences("SecureSMS", Context.MODE_PRIVATE)
        .edit().putBoolean("migrated", imported).commit();
  }

  private static void assertArchive(AuthenticationActivity activity) {
    Intent launched = Shadows.shadowOf(activity).getNextStartedActivity();
    assertThat(launched.getComponent().getClassName())
        .isEqualTo(ConversationListActivity.class.getName());
    assertThat(HostNavigationCommand.consume(launched))
        .isEqualTo(HostNavigationCommand.Destination.ARCHIVE);
  }

  private static void assertFreshInbox(AuthenticationActivity activity) {
    Intent launched = Shadows.shadowOf(activity).getNextStartedActivity();
    assertThat(launched.getComponent().getClassName())
        .isEqualTo(ConversationListActivity.class.getName());
    assertThat(HostNavigationCommand.consume(launched))
        .isEqualTo(HostNavigationCommand.Destination.INBOX);
  }

  private static void assertContainsNoSensitiveState(Bundle bundle) {
    for (String key : bundle.keySet()) {
      Object value = bundle.get(key);
      assertThat(key).doesNotContain("READY");
      assertThat(key.toLowerCase(Locale.ROOT)).doesNotContain("progress");
      assertThat(value).isNotInstanceOf(MasterSecret.class)
          .isNotInstanceOf(Intent.class)
          .isNotInstanceOf(Uri.class)
          .isNotInstanceOf(ProgressDescription.class)
          .isNotInstanceOf(char[].class)
          .isNotInstanceOf(byte[].class);
      if (value instanceof String) assertThat(value).isNotEqualTo("READY");
      if (value instanceof Bundle) assertContainsNoSensitiveState((Bundle) value);
    }
  }

  private static MasterSecret secret(byte value) {
    byte[] key = new byte[16];
    java.util.Arrays.fill(key, value);
    return new MasterSecret(new SecretKeySpec(key, "AES"),
                            new SecretKeySpec(key, "HmacSHA1"));
  }

  private final class FakeEnvironment implements DatabaseMigrationController.Environment {
    private ServiceConnection connection;
    private BroadcastReceiver receiver;
    private UnlockSession startedSession;
    private int bindCount;
    private int unbindCount;
    private int registerCount;
    private int unregisterCount;
    private int skipCount;

    @Override public boolean bind(@NonNull ServiceConnection connection) {
      bindCount++;
      this.connection = connection;
      return true;
    }

    @Override public void unbind(@NonNull ServiceConnection connection) { unbindCount++; }

    @Override public void register(@NonNull BroadcastReceiver receiver) {
      registerCount++;
      this.receiver = receiver;
    }

    @Override public void unregister(@NonNull BroadcastReceiver receiver) {
      unregisterCount++;
    }

    @Override public void startMigration(@NonNull UnlockSession unlockSession) {
      startedSession = unlockSession;
    }

    @Override public void skip() {
      skipCount++;
      ApplicationMigrationService.setDatabaseImported(context);
    }

    @Override public boolean isDatabaseImported() {
      return ApplicationMigrationService.isDatabaseImported(context);
    }

    private void connect(ApplicationMigrationService service) {
      connection.onServiceConnected(
          new ComponentName(context, ApplicationMigrationService.class),
          service.onBind(new Intent(context, ApplicationMigrationService.class)));
    }

    private void complete() {
      receiver.onReceive(context, new Intent(ApplicationMigrationService.COMPLETED_ACTION));
    }

    private void resetCallbacks() {
      connection = null;
      receiver = null;
    }
  }
}