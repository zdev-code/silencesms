package org.smssecure.smssecure;

import static org.assertj.core.api.Assertions.assertThat;

import android.app.Application;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

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
import org.smssecure.smssecure.service.KeyCachingService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.spec.SecretKeySpec;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 35)
public class AuthenticationActivityTest {
  private final Context context = ApplicationProvider.getApplicationContext();
    private final AtomicInteger createStarts = new AtomicInteger();
    private final AtomicInteger promptStarts = new AtomicInteger();
    private MasterSecret unlockedSecret;

    @Before
    public void setUp() {
        PassphraseCreateFragment.setOperationFactoryForTests(fragment ->
                new PassphraseCreateFragment.CreationOperation() {
                    @Override public void start(
                            org.smssecure.smssecure.ui.passphrasecreate.PassphraseCreateController.Callback callback) {
                        createStarts.incrementAndGet();
                    }

                    @Override public void close() {}
                });
            PassphrasePromptFragment.setOperationFactoryForTests(fragment ->
                new PassphrasePromptFragment.UnlockOperation() {
                    @Override public void submit(
                        org.smssecure.smssecure.domain.security.WipeablePassphrase passphrase,
                        org.smssecure.smssecure.ui.passphraseprompt.PassphrasePromptController.Callback callback) {
                    promptStarts.incrementAndGet();
                    passphrase.close();
                    }

                    @Override public void close() {}
                });
            org.smssecure.smssecure.util.SilencePreferences.setPasswordDisabled(context, false);
            unlockedSecret = secret();
            KeyCachingService.primeMasterSecret(unlockedSecret);
    }

  @After
  public void tearDown() {
        PassphraseCreateFragment.resetOperationFactoryForTests();
        PassphrasePromptFragment.resetOperationFactoryForTests();
        org.smssecure.smssecure.util.SilencePreferences.setPasswordDisabled(context, false);
    KeyCachingService.discardPrimedMasterSecret(unlockedSecret);
    unlockedSecret = null;
    BootstrapContinuationStore.getInstance().clear();
  }

  @Test
  public void welcomeIntentHasExactAllowedShape() {
    Intent intent = AuthenticationActivity.createWelcomeIntent(
        context, new Intent(context, ConversationListActivity.class));

    assertThat(intent.getComponent().getClassName())
        .isEqualTo(AuthenticationActivity.class.getName());
    assertThat(intent.getExtras().keySet()).containsExactlyInAnyOrderElementsOf(Set.of(
        AuthenticationActivity.EXTRA_SURFACE,
        BootstrapContinuationStore.EXTRA_DESTINATION,
        BootstrapContinuationStore.EXTRA_TOKEN));
    assertThat(intent.getStringExtra(AuthenticationActivity.EXTRA_SURFACE))
        .isEqualTo(AuthenticationActivity.Surface.WELCOME.name());
    assertThat(intent.getAction()).isNull();
    assertThat(intent.getData()).isNull();
    assertThat(intent.getClipData()).isNull();
    assertThat(AuthenticationActivity.isAllowedWelcomeIntent(intent)).isTrue();
    assertThat(AuthenticationActivity.isAllowedCreatePassphraseIntent(intent)).isFalse();
  }

  @Test
  public void createPassphraseIntentHasExactAllowedShape() {
    Intent intent = AuthenticationActivity.createCreatePassphraseIntent(
        context, new Intent(context, ConversationListActivity.class));

    assertThat(intent.getComponent().getClassName())
        .isEqualTo(AuthenticationActivity.class.getName());
    assertThat(intent.getExtras().keySet()).containsExactlyInAnyOrderElementsOf(Set.of(
        AuthenticationActivity.EXTRA_SURFACE,
        BootstrapContinuationStore.EXTRA_DESTINATION,
        BootstrapContinuationStore.EXTRA_TOKEN));
    assertThat(intent.getStringExtra(AuthenticationActivity.EXTRA_SURFACE))
        .isEqualTo(AuthenticationActivity.Surface.CREATE_PASSPHRASE.name());
    assertThat(intent.getAction()).isNull();
    assertThat(intent.getData()).isNull();
    assertThat(intent.getType()).isNull();
    assertThat(intent.getClipData()).isNull();
    assertThat(intent.getSelector()).isNull();
    assertThat(intent.getCategories()).isNull();
    assertThat(intent.getFlags()).isZero();
    assertThat(AuthenticationActivity.isAllowedCreatePassphraseIntent(intent)).isTrue();
    assertThat(AuthenticationActivity.isAllowedWelcomeIntent(intent)).isFalse();
  }

  @Test
  public void promptPassphraseIntentHasExactAllowedShapeAndRejectsOtherSurfaces() {
    Intent intent = AuthenticationActivity.createPromptPassphraseIntent(
        context, new Intent(context, ConversationListActivity.class));

    assertThat(intent.getComponent().getClassName())
        .isEqualTo(AuthenticationActivity.class.getName());
    assertThat(intent.getExtras().keySet()).containsExactlyInAnyOrderElementsOf(Set.of(
        AuthenticationActivity.EXTRA_SURFACE,
        BootstrapContinuationStore.EXTRA_DESTINATION,
        BootstrapContinuationStore.EXTRA_TOKEN));
    assertThat(intent.getStringExtra(AuthenticationActivity.EXTRA_SURFACE))
        .isEqualTo(AuthenticationActivity.Surface.PROMPT_PASSPHRASE.name());
    assertThat(intent.getAction()).isNull();
    assertThat(intent.getData()).isNull();
    assertThat(intent.getType()).isNull();
    assertThat(intent.getClipData()).isNull();
    assertThat(intent.getSelector()).isNull();
    assertThat(intent.getCategories()).isNull();
    assertThat(intent.getFlags()).isZero();
    assertThat(AuthenticationActivity.isAllowedPromptPassphraseIntent(intent)).isTrue();
    assertThat(AuthenticationActivity.isAllowedWelcomeIntent(intent)).isFalse();
    assertThat(AuthenticationActivity.isAllowedCreatePassphraseIntent(intent)).isFalse();

    assertThat(AuthenticationActivity.isAllowedPromptPassphraseIntent(
        new Intent(intent).putExtra(AuthenticationActivity.EXTRA_SURFACE,
                                    AuthenticationActivity.Surface.WELCOME.name()))).isFalse();
    assertThat(AuthenticationActivity.isAllowedPromptPassphraseIntent(
        new Intent(intent).putExtra("passphrase", "secret"))).isFalse();
    assertThat(AuthenticationActivity.isAllowedPromptPassphraseIntent(
        new Intent(intent).putExtra("master_secret", secret()))).isFalse();
    assertThat(AuthenticationActivity.isAllowedPromptPassphraseIntent(
        new Intent(intent).putExtra("next_intent", new Intent()))).isFalse();
  }

  @Test
  public void changePassphraseIntentHasExactAllowedShapeAndRejectsSensitiveFields() {
    Intent intent = AuthenticationActivity.createChangePassphraseIntent(context);

    assertThat(intent.getComponent().getClassName())
        .isEqualTo(AuthenticationActivity.class.getName());
    assertThat(intent.getExtras().keySet()).containsExactlyInAnyOrderElementsOf(Set.of(
        AuthenticationActivity.EXTRA_SURFACE,
        BootstrapContinuationStore.EXTRA_DESTINATION,
        BootstrapContinuationStore.EXTRA_TOKEN));
    assertThat(intent.getStringExtra(AuthenticationActivity.EXTRA_SURFACE))
        .isEqualTo(AuthenticationActivity.Surface.CHANGE_PASSPHRASE.name());
    assertThat(intent.getStringExtra(BootstrapContinuationStore.EXTRA_DESTINATION))
        .isEqualTo("CONVERSATION_LIST");
    assertThat(intent.getAction()).isNull();
    assertThat(intent.getData()).isNull();
    assertThat(intent.getType()).isNull();
    assertThat(intent.getClipData()).isNull();
    assertThat(intent.getSelector()).isNull();
    assertThat(intent.getCategories()).isNull();
    assertThat(intent.getFlags()).isZero();
    assertThat(AuthenticationActivity.isAllowedChangePassphraseIntent(intent)).isTrue();
    assertThat(AuthenticationActivity.isAllowedPromptPassphraseIntent(intent)).isFalse();
    assertThat(AuthenticationActivity.isAllowedChangePassphraseIntent(
        new Intent(intent).putExtra("passphrase", "secret"))).isFalse();
    assertThat(AuthenticationActivity.isAllowedChangePassphraseIntent(
        new Intent(intent).putExtra("master_secret", secret()))).isFalse();
    assertThat(AuthenticationActivity.isAllowedChangePassphraseIntent(
        new Intent(intent).putExtra("next_intent", new Intent()))).isFalse();
  }

  @Test
  public void upgradeDatabaseIntentHasExactAllowedShapeAndSurfaceBoundOwner() {
    Intent intent = AuthenticationActivity.createUpgradeDatabaseIntent(
        context, new Intent(context, ConversationListActivity.class));

    assertThat(intent.getComponent().getClassName())
        .isEqualTo(AuthenticationActivity.class.getName());
    assertThat(intent.getExtras().keySet()).containsExactlyInAnyOrderElementsOf(Set.of(
        AuthenticationActivity.EXTRA_SURFACE,
        BootstrapContinuationStore.EXTRA_DESTINATION,
        BootstrapContinuationStore.EXTRA_TOKEN));
    assertThat(intent.getStringExtra(AuthenticationActivity.EXTRA_SURFACE))
        .isEqualTo(AuthenticationActivity.Surface.UPGRADE_DATABASE.name());
    assertThat(intent.getAction()).isNull();
    assertThat(intent.getData()).isNull();
    assertThat(intent.getType()).isNull();
    assertThat(intent.getClipData()).isNull();
    assertThat(intent.getSelector()).isNull();
    assertThat(intent.getCategories()).isNull();
    assertThat(intent.getFlags()).isZero();
    assertThat(AuthenticationActivity.isAllowedUpgradeDatabaseIntent(intent)).isTrue();
    assertThat(AuthenticationActivity.isAllowedWelcomeIntent(intent)).isFalse();
    assertThat(AuthenticationActivity.isAllowedCreatePassphraseIntent(intent)).isFalse();
    assertThat(AuthenticationActivity.isAllowedPromptPassphraseIntent(intent)).isFalse();

    intent.putExtra(AuthenticationActivity.EXTRA_SURFACE,
                    AuthenticationActivity.Surface.PROMPT_PASSPHRASE.name());
    AuthenticationActivity activity = Robolectric.buildActivity(
        AuthenticationActivity.class, intent).create().get();
    activity.onPassphrasePromptCompleted();

    Intent launched = Shadows.shadowOf(activity).getNextStartedActivity();
    assertThat(launched.getComponent().getClassName())
        .isEqualTo(ConversationListActivity.class.getName());
    assertThat(HostNavigationCommand.consume(launched))
        .isEqualTo(HostNavigationCommand.Destination.INBOX);
  }

  @Test
  public void databaseMigrationIntentHasExactAllowedShapeAndSurfaceBoundOwner() {
    Intent intent = AuthenticationActivity.createDatabaseMigrationIntent(
        context, new Intent(context, ConversationListActivity.class));

    assertThat(intent.getComponent().getClassName())
        .isEqualTo(AuthenticationActivity.class.getName());
    assertThat(intent.getExtras().keySet()).containsExactlyInAnyOrderElementsOf(Set.of(
        AuthenticationActivity.EXTRA_SURFACE,
        BootstrapContinuationStore.EXTRA_DESTINATION,
        BootstrapContinuationStore.EXTRA_TOKEN));
    assertThat(intent.getStringExtra(AuthenticationActivity.EXTRA_SURFACE))
        .isEqualTo(AuthenticationActivity.Surface.DATABASE_MIGRATION.name());
    assertThat(intent.getAction()).isNull();
    assertThat(intent.getData()).isNull();
    assertThat(intent.getType()).isNull();
    assertThat(intent.getClipData()).isNull();
    assertThat(intent.getSelector()).isNull();
    assertThat(intent.getCategories()).isNull();
    assertThat(intent.getFlags()).isZero();
    assertThat(AuthenticationActivity.isAllowedDatabaseMigrationIntent(intent)).isTrue();
    assertThat(AuthenticationActivity.isAllowedWelcomeIntent(intent)).isFalse();
    assertThat(AuthenticationActivity.isAllowedCreatePassphraseIntent(intent)).isFalse();
    assertThat(AuthenticationActivity.isAllowedPromptPassphraseIntent(intent)).isFalse();
    assertThat(AuthenticationActivity.isAllowedUpgradeDatabaseIntent(intent)).isFalse();

    assertThat(AuthenticationActivity.isAllowedDatabaseMigrationIntent(
        new Intent(intent).putExtra(AuthenticationActivity.EXTRA_SURFACE,
                                    AuthenticationActivity.Surface.UPGRADE_DATABASE.name())))
        .isFalse();
    assertThat(AuthenticationActivity.isAllowedDatabaseMigrationIntent(
        new Intent(intent).putExtra("progress", new Bundle()))).isFalse();
    assertThat(AuthenticationActivity.isAllowedDatabaseMigrationIntent(
        new Intent(intent).putExtra("master_secret", secret()))).isFalse();
    assertThat(AuthenticationActivity.isAllowedDatabaseMigrationIntent(
        new Intent(intent).putExtra("next_intent", new Intent()))).isFalse();
    assertThat(AuthenticationActivity.isAllowedDatabaseMigrationIntent(
        new Intent(intent).setData(Uri.parse("content://sms")))).isFalse();
  }

  @Test
  public void crossSurfaceTokenReplayFailsClosed() {
    Intent welcome = AuthenticationActivity.createWelcomeIntent(
        context, HostNavigationCommand.createIntent(context, HostNavigationCommand.Destination.ARCHIVE));
    welcome.putExtra(AuthenticationActivity.EXTRA_SURFACE,
                     AuthenticationActivity.Surface.PROMPT_PASSPHRASE.name());
    AuthenticationActivity activity = Robolectric.buildActivity(
        AuthenticationActivity.class, welcome).create().get();

    activity.onPassphrasePromptCompleted();

    Intent launched = Shadows.shadowOf(activity).getNextStartedActivity();
    assertThat(launched.getComponent().getClassName())
        .isEqualTo(ConversationListActivity.class.getName());
    assertThat(HostNavigationCommand.consume(launched))
        .isEqualTo(HostNavigationCommand.Destination.INBOX);
  }

  @Test
  public void changeSurfaceRejectsTokenIssuedForAnotherSurface() {
    Intent prompt = AuthenticationActivity.createPromptPassphraseIntent(
        context, HostNavigationCommand.createIntent(
            context, HostNavigationCommand.Destination.APP_PROTECTION_PREFERENCES));
    prompt.putExtra(AuthenticationActivity.EXTRA_SURFACE,
                    AuthenticationActivity.Surface.CHANGE_PASSPHRASE.name());

    AuthenticationActivity activity = Robolectric.buildActivity(
        AuthenticationActivity.class, prompt).create().get();

    assertThat(activity.isFinishing()).isTrue();
    Intent launched = Shadows.shadowOf(activity).getNextStartedActivity();
    assertThat(launched.getComponent().getClassName())
        .isEqualTo(ConversationListActivity.class.getName());
    assertThat(HostNavigationCommand.consume(launched))
        .isEqualTo(HostNavigationCommand.Destination.INBOX);
  }

  @Test
  public void rejectsMissingUnknownOrSensitiveWelcomeFields() {
    Intent allowed = AuthenticationActivity.createWelcomeIntent(
        context, new Intent(context, ConversationListActivity.class));
        Intent missingSurface = new Intent(allowed);
        missingSurface.removeExtra(AuthenticationActivity.EXTRA_SURFACE);

        assertThat(AuthenticationActivity.isAllowedWelcomeIntent(missingSurface)).isFalse();
    assertThat(AuthenticationActivity.isAllowedWelcomeIntent(
        new Intent(allowed).putExtra(AuthenticationActivity.EXTRA_SURFACE, "READY"))).isFalse();
    assertThat(AuthenticationActivity.isAllowedWelcomeIntent(
        new Intent(allowed).putExtra("plaintext", "message"))).isFalse();
    assertThat(AuthenticationActivity.isAllowedWelcomeIntent(
        new Intent(allowed).putExtra("identity", new byte[] {1, 2, 3}))).isFalse();
    assertThat(AuthenticationActivity.isAllowedWelcomeIntent(
        new Intent(allowed).putExtra("master_secret", secret()))).isFalse();
    assertThat(AuthenticationActivity.isAllowedWelcomeIntent(
        new Intent(allowed).putExtra("next_intent", new Intent()))).isFalse();
    assertThat(AuthenticationActivity.isAllowedWelcomeIntent(
        new Intent(allowed).putExtra(AuthenticationActivity.EXTRA_SURFACE, 1))).isFalse();
    assertThat(AuthenticationActivity.isAllowedWelcomeIntent(
        new Intent(allowed).setData(Uri.parse("sms:+15551234567")))).isFalse();
    Intent clipData = new Intent(allowed);
    clipData.setClipData(ClipData.newPlainText("plaintext", "message"));
    assertThat(AuthenticationActivity.isAllowedWelcomeIntent(clipData)).isFalse();
  }

  @Test
  public void rejectsCreateSurfaceMismatchUnknownAndUnexpectedFields() {
    Intent allowed = AuthenticationActivity.createCreatePassphraseIntent(
        context, new Intent(context, ConversationListActivity.class));

    assertThat(AuthenticationActivity.isAllowedCreatePassphraseIntent(
        new Intent(allowed).putExtra(AuthenticationActivity.EXTRA_SURFACE,
                                     AuthenticationActivity.Surface.WELCOME.name()))).isFalse();
    assertThat(AuthenticationActivity.isAllowedCreatePassphraseIntent(
        new Intent(allowed).putExtra(AuthenticationActivity.EXTRA_SURFACE, "READY"))).isFalse();
    assertThat(AuthenticationActivity.isAllowedCreatePassphraseIntent(
        new Intent(allowed).putExtra(BootstrapContinuationStore.EXTRA_DESTINATION, "UNKNOWN")))
        .isFalse();
    assertThat(AuthenticationActivity.isAllowedCreatePassphraseIntent(
        new Intent(allowed).putExtra(BootstrapContinuationStore.EXTRA_TOKEN, ""))).isFalse();
    assertThat(AuthenticationActivity.isAllowedCreatePassphraseIntent(
        new Intent(allowed).putExtra(BootstrapContinuationStore.EXTRA_TOKEN, "x".repeat(129))))
        .isFalse();
    assertThat(AuthenticationActivity.isAllowedCreatePassphraseIntent(
        new Intent(allowed).putExtra("passphrase", "secret"))).isFalse();
    assertThat(AuthenticationActivity.isAllowedCreatePassphraseIntent(
        new Intent(allowed).putExtra("master_secret", secret()))).isFalse();
    assertThat(AuthenticationActivity.isAllowedCreatePassphraseIntent(
        new Intent(allowed).putExtra("next_intent", new Intent()))).isFalse();
    assertThat(AuthenticationActivity.isAllowedCreatePassphraseIntent(
        new Intent(allowed).setData(Uri.parse("content://private/key")))).isFalse();
  }

  @Test
  public void malformedAndColdContinuationsFailClosedToFreshApplicationPolicy() {
    AuthenticationActivity malformed = Robolectric.buildActivity(
        AuthenticationActivity.class,
        new Intent(context, AuthenticationActivity.class)
            .putExtra(AuthenticationActivity.EXTRA_SURFACE, "READY"))
        .create().get();
    assertThat(Shadows.shadowOf(malformed).getNextStartedActivity().getComponent().getClassName())
        .isEqualTo(ConversationListActivity.class.getName());
    assertThat(malformed.getIntent().getExtras()).isNull();
    assertThat(malformed.isFinishing()).isTrue();

    Intent cold = new Intent(context, AuthenticationActivity.class)
        .putExtra(AuthenticationActivity.EXTRA_SURFACE,
                  AuthenticationActivity.Surface.WELCOME.name())
        .putExtra(BootstrapContinuationStore.EXTRA_DESTINATION, "CONVERSATION_LIST")
        .putExtra(BootstrapContinuationStore.EXTRA_TOKEN, "missing-token");
    AuthenticationActivity activity = Robolectric.buildActivity(
        AuthenticationActivity.class, cold).create().get();
    activity.onWelcomeCompleted();
    assertThat(Shadows.shadowOf(activity).getNextStartedActivity().getComponent().getClassName())
        .isEqualTo(ConversationListActivity.class.getName());
  }

  @Test
  public void recreationRestoresOnlyWelcomeFragmentAndTokenRemainsOneShot() {
    Intent intent = AuthenticationActivity.createWelcomeIntent(
        context, new Intent(context, ConversationListActivity.class));
    ActivityController<AuthenticationActivity> controller = Robolectric.buildActivity(
        AuthenticationActivity.class, intent).create().start().resume().visible();

    controller.recreate();
    AuthenticationActivity recreated = controller.get();
    assertThat(recreated.getSupportFragmentManager().getFragments())
        .hasSize(1)
        .allMatch(fragment -> fragment instanceof WelcomeFragment);

    recreated.onWelcomeCompleted();
    recreated.onWelcomeCompleted();
    assertThat(Shadows.shadowOf(recreated).getNextStartedActivity().getComponent().getClassName())
        .isEqualTo(ConversationListActivity.class.getName());
    assertThat(Shadows.shadowOf(recreated).getNextStartedActivity()).isNull();
  }

  @Test
  public void recreationRestoresOnlyCreateFragmentAndStartsFreshSafeWork() {
    Intent intent = AuthenticationActivity.createCreatePassphraseIntent(
        context, new Intent(context, ConversationListActivity.class));
    ActivityController<AuthenticationActivity> controller = Robolectric.buildActivity(
        AuthenticationActivity.class, intent).create().start().resume().visible();
    controller.get().getSupportFragmentManager().executePendingTransactions();

    assertThat(createStarts.get()).isEqualTo(1);
    controller.recreate();
    AuthenticationActivity recreated = controller.get();
    recreated.getSupportFragmentManager().executePendingTransactions();

    assertThat(createStarts.get()).isEqualTo(2);
    assertThat(recreated.getSupportFragmentManager().getFragments())
        .hasSize(1)
        .allMatch(fragment -> fragment instanceof PassphraseCreateFragment);

    Bundle savedState = new Bundle();
    controller.saveInstanceState(savedState);
    assertContainsNoSensitiveState(savedState);
  }

    @Test
    public void recreationRestoresOnlyPromptFragmentAndCannotRestoreCompletion() {
        Intent intent = AuthenticationActivity.createPromptPassphraseIntent(
                context, new Intent(context, ConversationListActivity.class));
        ActivityController<AuthenticationActivity> controller = Robolectric.buildActivity(
                AuthenticationActivity.class, intent).create().start().resume().visible();
        controller.get().getSupportFragmentManager().executePendingTransactions();

        assertThat(promptStarts.get()).isZero();
        controller.recreate();
        AuthenticationActivity recreated = controller.get();
        recreated.getSupportFragmentManager().executePendingTransactions();

        assertThat(recreated.getSupportFragmentManager().getFragments())
                .hasSize(1)
                .allMatch(fragment -> fragment instanceof PassphrasePromptFragment);
        assertThat(Shadows.shadowOf(recreated).getNextStartedActivity()).isNull();

        Bundle savedState = new Bundle();
        controller.saveInstanceState(savedState);
        assertContainsNoSensitiveState(savedState);
    }

  @Test
  public void createCompletionConsumesOnceAndColdTokenFailsClosed() {
    Intent intent = AuthenticationActivity.createCreatePassphraseIntent(
        context, new Intent(context, ConversationListActivity.class));
    AuthenticationActivity activity = Robolectric.buildActivity(
        AuthenticationActivity.class, intent).create().get();

    activity.onPassphraseCreateCompleted();
    activity.onPassphraseCreateCompleted();

    assertThat(Shadows.shadowOf(activity).getNextStartedActivity().getComponent().getClassName())
        .isEqualTo(ConversationListActivity.class.getName());
    assertThat(Shadows.shadowOf(activity).getNextStartedActivity()).isNull();

    Intent cold = new Intent(context, AuthenticationActivity.class)
        .putExtra(AuthenticationActivity.EXTRA_SURFACE,
                  AuthenticationActivity.Surface.CREATE_PASSPHRASE.name())
        .putExtra(BootstrapContinuationStore.EXTRA_DESTINATION, "CONVERSATION_LIST")
        .putExtra(BootstrapContinuationStore.EXTRA_TOKEN, "missing-token");
    AuthenticationActivity coldActivity = Robolectric.buildActivity(
        AuthenticationActivity.class, cold).create().get();
    coldActivity.onPassphraseCreateCompleted();
    assertThat(Shadows.shadowOf(coldActivity).getNextStartedActivity()
        .getComponent().getClassName()).isEqualTo(ConversationListActivity.class.getName());
  }

  @Test
  public void authenticationHostHasNoApplicationGraphOrProtectedFragmentReferences() throws Exception {
    String source = source("AuthenticationActivity.java");
    assertThat(source)
        .doesNotContain("NavHost")
        .doesNotContain("R.navigation")
        .doesNotContain("ConversationListFragment")
        .doesNotContain("ConversationScreenFragment")
        .doesNotContain("MasterSecret");

    assertThat(source("DatabaseUpgradeFragment.java"))
        .contains("new ConversationUnlockCapability(unlockSession)")
        .contains("controller.clearCompletedRecord()")
        .contains("VersionTracker.updateLastSeenVersion(context)")
        .contains("setEncryptionKeys(new EncryptionKeys(ParcelUtil.serialize(secret)))")
        .doesNotContain("onSaveInstanceState")
        .doesNotContain("putExtra(\"master_secret\"")
        .doesNotContain("READY");

    assertThat(source("DatabaseMigrationFragment.java"))
        .contains("controller.startMigration(UnlockSession.capture())")
        .contains("ApplicationMigrationService.setDatabaseImported(context)")
        .contains("view.setSaveFromParentEnabled(false)")
        .doesNotContain("MasterSecret")
        .doesNotContain("onSaveInstanceState")
        .doesNotContain("READY");

    assertThat(source("PassphraseCreateFragment.java"))
        .contains("setNegativeButton(android.R.string.cancel")
        .contains("setPositiveButton(R.string.retry")
        .contains("startCreation()")
        .doesNotContain("putExtra(\"master_secret\"")
        .doesNotContain("onSaveInstanceState");
    assertThat(source("PassphrasePromptFragment.java"))
        .contains("WipeablePassphrase.copyOf(")
        .contains("editable.clear()")
        .contains("completionCoordinator.establish(masterSecret")
        .contains("R.menu.log_submit")
        .doesNotContain("putExtra(\"master_secret\"")
        .doesNotContain("onSaveInstanceState");
    assertThat(source("ui/authentication/AuthenticationCompletionCoordinator.java"))
        .contains("KeyCachingService.markAuthenticationActivationPending(masterSecret)")
        .contains("KeyCachingService.primeMasterSecret(masterSecret)")
        .contains("KeyCachingService.isAuthenticationActivationPending(pending)")
        .contains("KeyCachingService.discardPrimedMasterSecret(masterSecret)")
        .contains("getService().setMasterSecret(pending)");
  }

  private static String source(String fileName) throws Exception {
    Path project = Path.of(System.getProperty("user.dir"));
    if (project.endsWith("app")) project = project.getParent();
    return new String(Files.readAllBytes(project.resolve(
        "app/src/main/java/org/smssecure/smssecure/" + fileName)), StandardCharsets.UTF_8);
  }

    private static void assertContainsNoSensitiveState(Bundle bundle) {
        for (String key : bundle.keySet()) {
            Object value = bundle.get(key);
            assertThat(value).isNotInstanceOf(MasterSecret.class)
                    .isNotInstanceOf(Intent.class)
                    .isNotInstanceOf(Uri.class)
                    .isNotInstanceOf(char[].class)
                    .isNotInstanceOf(byte[].class);
            if (value instanceof Bundle) assertContainsNoSensitiveState((Bundle) value);
        }
    }

    private static MasterSecret secret() {
        byte[] key = new byte[16];
        return new MasterSecret(new SecretKeySpec(key, "AES"),
                                                        new SecretKeySpec(key, "HmacSHA1"));
    }
}