package org.smssecure.smssecure;

import static org.assertj.core.api.Assertions.assertThat;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.widget.ProgressBar;

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
import org.robolectric.annotation.Config;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.domain.conversation.ConversationUnlockCapability;
import org.smssecure.smssecure.domain.security.UnlockSession;
import org.smssecure.smssecure.service.KeyCachingService;
import org.smssecure.smssecure.ui.databaseupgrade.DatabaseUpgradeController;
import org.smssecure.smssecure.util.SilencePreferences;
import org.smssecure.smssecure.util.Util;

import java.util.ArrayList;
import java.util.List;

import javax.crypto.spec.SecretKeySpec;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 35)
public class DatabaseUpgradeFragmentTest {
  private final Context context = ApplicationProvider.getApplicationContext();
  private final FakeOperation operation = new FakeOperation();
  private MasterSecret originalSecret;

  @Before
  public void setUp() throws Exception {
    originalSecret = secret((byte) 1);
    KeyCachingService.primeMasterSecret(originalSecret);
    SilencePreferences.setLastVersionCode(context, 0);
    DatabaseUpgradeFragment.setOperationFactoryForTests(fragment -> operation);
    BootstrapContinuationStore.getInstance().clear();
  }

  @After
  public void tearDown() {
    DatabaseUpgradeFragment.resetOperationFactoryForTests();
    BootstrapContinuationStore.getInstance().clear();
    KeyCachingService.discardPrimedMasterSecret(KeyCachingService.getSecretSnapshot().getSecret());
  }

  @Test
  public void startsWithCurrentVersionsAndRendersDeterminateProgress() throws Exception {
    AuthenticationActivity activity = launchController().get();

    assertThat(operation.startCount).isEqualTo(1);
    assertThat(operation.fromVersion).isZero();
    assertThat(operation.targetVersion).isEqualTo(Util.getCurrentApkReleaseVersion(context));
    assertThat(operation.capability).isNotNull();
    assertThat(activity.<ProgressBar>findViewById(R.id.indeterminate_progress).getVisibility())
        .isEqualTo(View.VISIBLE);

    operation.emitProgress(1, 4);
    Shadows.shadowOf(Looper.getMainLooper()).idle();

    assertThat(activity.<ProgressBar>findViewById(R.id.indeterminate_progress).getVisibility())
        .isEqualTo(View.GONE);
    ProgressBar determinate = activity.findViewById(R.id.determinate_progress);
    assertThat(determinate.getVisibility()).isEqualTo(View.VISIBLE);
    assertThat(determinate.getProgress()).isEqualTo(determinate.getMax() / 4);
  }

  @Test
  public void recreationRestoresOnlyUpgradeFragmentReattachesAndSavesNoSensitiveState()
      throws Exception {
    ActivityController<AuthenticationActivity> controller = launchController();
    operation.emitProgress(1, 2);
    Shadows.shadowOf(Looper.getMainLooper()).idle();

    controller.recreate();
    AuthenticationActivity recreated = controller.get();
    recreated.getSupportFragmentManager().executePendingTransactions();

    assertThat(recreated.getSupportFragmentManager().getFragments())
        .hasSize(1)
        .allMatch(fragment -> fragment instanceof DatabaseUpgradeFragment);
    assertThat(operation.activeObservers()).isEqualTo(1);
    assertThat(recreated.<ProgressBar>findViewById(R.id.determinate_progress).getVisibility())
        .isEqualTo(View.VISIBLE);

    Bundle savedState = new Bundle();
    controller.saveInstanceState(savedState);
    assertContainsNoSensitiveState(savedState);
  }

  @Test
  public void completionConsumesContinuationAndNavigatesOnlyOnce() {
    AuthenticationActivity activity = launchController().get();

    operation.emitComplete();
    operation.emitComplete();
    Shadows.shadowOf(Looper.getMainLooper()).idle();

    Intent launched = Shadows.shadowOf(activity).getNextStartedActivity();
    assertThat(launched.getComponent().getClassName())
        .isEqualTo(ConversationListActivity.class.getName());
    assertThat(HostNavigationCommand.consume(launched))
        .isEqualTo(HostNavigationCommand.Destination.ARCHIVE);
    assertThat(Shadows.shadowOf(activity).getNextStartedActivity()).isNull();
    assertThat(operation.clearCount).isEqualTo(1);
  }

  @Test
  public void noUpgradeFinalizesOnceThenConsumesContinuation() throws Exception {
    SilencePreferences.setLastVersionCode(
        context, Util.getCurrentApkReleaseVersion(context));
    Intent migrationIntent = AuthenticationActivity.createDatabaseMigrationIntent(
        context, new Intent(context, ConversationListActivity.class));
    Intent intent = AuthenticationActivity.createUpgradeDatabaseIntent(
      context, migrationIntent);
    ActivityController<AuthenticationActivity> controller = Robolectric.buildActivity(
      AuthenticationActivity.class, intent).create().start().resume().visible();
    controller.get().getSupportFragmentManager().executePendingTransactions();
    AuthenticationActivity activity = controller.get();

    assertThat(operation.startCount).isZero();
    assertThat(operation.finalizeCount).isEqualTo(1);
    Intent launched = Shadows.shadowOf(activity).getNextStartedActivity();
    assertThat(launched.getComponent().getClassName())
      .isEqualTo(AuthenticationActivity.class.getName());
    assertThat(AuthenticationActivity.isAllowedDatabaseMigrationIntent(launched)).isTrue();
    assertThat(Shadows.shadowOf(activity).getNextStartedActivity()).isNull();
  }

  @Test
  public void generationChangeDuringWorkFailsClosedToFreshPolicyEvaluation() {
    AuthenticationActivity activity = launchController().get();

    operation.emitFailure(new ConversationUnlockCapability.LockedException());
    Shadows.shadowOf(Looper.getMainLooper()).idle();

    assertTerminatedWithoutRelaunch(activity);
    assertThat(operation.clearCount).isZero();
  }

  @Test
  public void generationChangeBeforeCompletionFailsClosedWithoutConsumingContinuation() {
    AuthenticationActivity activity = launchController().get();
    KeyCachingService.primeMasterSecret(secret((byte) 2));

    operation.emitComplete();
    Shadows.shadowOf(Looper.getMainLooper()).idle();

    assertTerminatedWithoutRelaunch(activity);
    assertThat(operation.clearCount).isZero();
  }

  @Test
  public void coldContinuationFailsClosedAfterValidUpgradeCompletion() {
    Intent cold = new Intent(context, AuthenticationActivity.class)
        .putExtra(AuthenticationActivity.EXTRA_SURFACE,
                  AuthenticationActivity.Surface.UPGRADE_DATABASE.name())
        .putExtra(BootstrapContinuationStore.EXTRA_DESTINATION, "CONVERSATION_LIST")
        .putExtra(BootstrapContinuationStore.EXTRA_TOKEN, "missing-token");
    AuthenticationActivity activity = Robolectric.buildActivity(
        AuthenticationActivity.class, cold).create().start().resume().visible().get();
    activity.getSupportFragmentManager().executePendingTransactions();

    operation.emitComplete();
    Shadows.shadowOf(Looper.getMainLooper()).idle();

    assertFreshInbox(activity);
  }

  private ActivityController<AuthenticationActivity> launchController() {
    Intent intent = AuthenticationActivity.createUpgradeDatabaseIntent(
        context, HostNavigationCommand.createIntent(
            context, HostNavigationCommand.Destination.ARCHIVE));
    ActivityController<AuthenticationActivity> controller = Robolectric.buildActivity(
        AuthenticationActivity.class, intent).create().start().resume().visible();
    controller.get().getSupportFragmentManager().executePendingTransactions();
    return controller;
  }

  private static void assertFreshInbox(AuthenticationActivity activity) {
    Intent launched = Shadows.shadowOf(activity).getNextStartedActivity();
    assertThat(launched.getComponent().getClassName())
        .isEqualTo(ConversationListActivity.class.getName());
    assertThat(HostNavigationCommand.consume(launched))
        .isEqualTo(HostNavigationCommand.Destination.INBOX);
  }

  private static void assertTerminatedWithoutRelaunch(AuthenticationActivity activity) {
    assertThat(activity.isFinishing()).isTrue();
    assertThat(Shadows.shadowOf(activity).getNextStartedActivity()).isNull();
  }

  private static void assertContainsNoSensitiveState(Bundle bundle) {
    for (String key : bundle.keySet()) {
      Object value = bundle.get(key);
      assertThat(key).doesNotContain("READY");
      assertThat(key.toLowerCase(java.util.Locale.ROOT)).doesNotContain("progress");
      assertThat(value).isNotInstanceOf(MasterSecret.class)
          .isNotInstanceOf(Intent.class)
          .isNotInstanceOf(Uri.class)
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

  private static final class FakeOperation implements DatabaseUpgradeController.Operation {
    private final List<DatabaseUpgradeController.Observer> observers = new ArrayList<>();
    private int startCount;
    private int fromVersion;
    private int targetVersion;
    private int clearCount;
    private int finalizeCount;
    private int progress;
    private int total;
    private ConversationUnlockCapability capability;

    @Override public void start(int fromVersion, int targetVersion,
                                @NonNull ConversationUnlockCapability capability) {
      startCount++;
      this.fromVersion = fromVersion;
      this.targetVersion = targetVersion;
      this.capability = capability;
    }

    @NonNull
    @Override public DatabaseUpgradeController.Subscription observe(
        @NonNull DatabaseUpgradeController.Observer observer) {
      observers.add(observer);
      if (total > 0) observer.onProgress(progress, total);
      return () -> observers.remove(observer);
    }

    @Override public void clearCompletedRecord() {
      clearCount++;
    }

    @Override public void finalizeWithoutUpgrade(@NonNull Context context,
                                                 @NonNull UnlockSession unlockSession)
        throws Exception {
      unlockSession.requireCurrent();
      finalizeCount++;
      clearCount++;
    }

    private int activeObservers() {
      return observers.size();
    }

    private void emitProgress(int progress, int total) {
      this.progress = progress;
      this.total = total;
      for (DatabaseUpgradeController.Observer observer : new ArrayList<>(observers)) {
        observer.onProgress(progress, total);
      }
    }

    private void emitComplete() {
      for (DatabaseUpgradeController.Observer observer : new ArrayList<>(observers)) {
        observer.onComplete();
      }
    }

    private void emitFailure(Exception exception) {
      for (DatabaseUpgradeController.Observer observer : new ArrayList<>(observers)) {
        observer.onFailure(exception);
      }
    }
  }
}