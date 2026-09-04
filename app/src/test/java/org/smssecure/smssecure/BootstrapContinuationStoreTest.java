package org.smssecure.smssecure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.domain.security.UnlockSession;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.crypto.spec.SecretKeySpec;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 35)
public class BootstrapContinuationStoreTest {
    private static final String UPGRADE_OWNER =
            AuthenticationActivity.class.getName() + ":" +
                    AuthenticationActivity.Surface.UPGRADE_DATABASE.name();
        private static final String MIGRATION_OWNER =
            AuthenticationActivity.class.getName() + ":" +
                AuthenticationActivity.Surface.DATABASE_MIGRATION.name();
        private static final String CHANGE_OWNER =
            AuthenticationActivity.class.getName() + ":" +
                AuthenticationActivity.Surface.CHANGE_PASSPHRASE.name();

  private final Context context = ApplicationProvider.getApplicationContext();
  private final AtomicLong now = new AtomicLong(100L);
  private final AtomicReference<UnlockSession.Snapshot> snapshot =
      new AtomicReference<>(new UnlockSession.Snapshot(7L, secret()));
  private BootstrapContinuationStore store;

  @Before public void setUp() {
    store = new BootstrapContinuationStore(now::get, snapshot::get, 50L);
  }

  @Test public void allowlistsExplicitTargetsAndRejectsImplicitOrArbitraryComponents() {
    Intent allowed = new Intent(context, ConversationListActivity.class);
    assertThat(store.createBootstrapIntent(
        context, AuthenticationActivity.class, allowed, UPGRADE_OWNER)
        .getComponent().getClassName()).isEqualTo(AuthenticationActivity.class.getName());

    assertThatThrownBy(() -> store.createBootstrapIntent(
        context, AuthenticationActivity.class, new Intent(Intent.ACTION_VIEW), UPGRADE_OWNER))
        .isInstanceOf(SecurityException.class);
    assertThatThrownBy(() -> store.createBootstrapIntent(
        context, AuthenticationActivity.class,
        new Intent(context, AuthenticationActivity.class), UPGRADE_OWNER))
        .isInstanceOf(SecurityException.class);
  }

  @Test public void preservesTokenOnlyVerificationHostCommand() throws Exception {
    Intent target = HostNavigationCommand.createConflictVerifyIdentityIntent(
        context, "opaque-token");
    Intent gate = store.createBootstrapIntent(
        context, AuthenticationActivity.class, target, UPGRADE_OWNER);

    Intent resumed = store.consume(gate, UPGRADE_OWNER);
    assertThat(resumed.getComponent().getClassName())
        .isEqualTo(ConversationListActivity.class.getName());
    assertThat(HostNavigationCommand.consume(resumed))
        .isEqualTo(HostNavigationCommand.Destination.VERIFY_IDENTITY);
    android.os.Bundle arguments = HostNavigationCommand.consumeVerifyIdentityArguments(resumed);
    assertThat(arguments.keySet()).containsExactly(VerifyIdentityFragment.CONFLICT_TOKEN_ARGUMENT);
    assertThat(arguments.getString(VerifyIdentityFragment.CONFLICT_TOKEN_ARGUMENT))
        .isEqualTo("opaque-token");
  }

  @Test public void consumesOnceAndRejectsDuplicateUse() throws Exception {
    Intent gate = store.createBootstrapIntent(
        context, AuthenticationActivity.class,
        new Intent(context, ConversationListActivity.class));

    assertThat(store.consume(new Intent(gate), AuthenticationActivity.class)
        .getComponent().getClassName()).isEqualTo(ConversationListActivity.class.getName());
    assertThatThrownBy(() -> store.consume(gate, AuthenticationActivity.class))
        .isInstanceOf(BootstrapContinuationStore.InvalidContinuationException.class);
  }

  @Test public void rejectsWrongOwnerOrMissingColdToken() {
    Intent gate = store.createBootstrapIntent(
        context, AuthenticationActivity.class,
        new Intent(context, ConversationListActivity.class), UPGRADE_OWNER);

    assertThatThrownBy(() -> store.consume(
        gate, AuthenticationActivity.class.getName() + ":" +
            AuthenticationActivity.Surface.WELCOME.name()))
        .isInstanceOf(BootstrapContinuationStore.InvalidContinuationException.class);
    assertThatThrownBy(() -> store.consume(
        new Intent(context, AuthenticationActivity.class), UPGRADE_OWNER))
        .isInstanceOf(BootstrapContinuationStore.InvalidContinuationException.class);
  }

    @Test public void validatesOnlyKnownDestinationAndBoundedOpaqueTokenFields() {
        assertThat(BootstrapContinuationStore.hasValidRouteFields(
                "CONVERSATION_LIST", "opaque-token")).isTrue();
        assertThat(BootstrapContinuationStore.hasValidRouteFields("UNKNOWN", "opaque-token")).isFalse();
        assertThat(BootstrapContinuationStore.hasValidRouteFields("CONVERSATION_LIST", "")).isFalse();
        assertThat(BootstrapContinuationStore.hasValidRouteFields(
                "CONVERSATION_LIST", "x".repeat(129))).isFalse();
    }

  @Test public void expiresAndRejectsStaleUnlockGeneration() {
    Intent expired = store.createBootstrapIntent(
        context, AuthenticationActivity.class,
        new Intent(context, ConversationListActivity.class), UPGRADE_OWNER);
    now.set(151L);
    assertThatThrownBy(() -> store.consume(expired, UPGRADE_OWNER))
        .isInstanceOf(BootstrapContinuationStore.InvalidContinuationException.class);

    now.set(200L);
    Intent stale = store.createBootstrapIntent(
        context, AuthenticationActivity.class,
        new Intent(context, ConversationListActivity.class), UPGRADE_OWNER);
    snapshot.set(new UnlockSession.Snapshot(8L, secret()));
    assertThatThrownBy(() -> store.consume(stale, UPGRADE_OWNER))
        .isInstanceOf(BootstrapContinuationStore.InvalidContinuationException.class);
  }

  @Test public void unlockedRouteRejectsLockedWrongOwnerExpiredStaleAndProcessLoss() throws Exception {
    snapshot.set(new UnlockSession.Snapshot(8L, null));
    assertThatThrownBy(() -> store.createUnlockedIntent(
        context, AuthenticationActivity.class,
        HostNavigationCommand.createIntent(
            context, HostNavigationCommand.Destination.APP_PROTECTION_PREFERENCES), CHANGE_OWNER))
        .isInstanceOf(SecurityException.class);

    snapshot.set(new UnlockSession.Snapshot(9L, secret()));
    Intent wrongOwner = store.createUnlockedIntent(
        context, AuthenticationActivity.class,
        HostNavigationCommand.createIntent(
            context, HostNavigationCommand.Destination.APP_PROTECTION_PREFERENCES), CHANGE_OWNER);
    assertThatThrownBy(() -> store.requireLive(wrongOwner, MIGRATION_OWNER, true))
        .isInstanceOf(BootstrapContinuationStore.InvalidContinuationException.class);

    Intent expired = store.createUnlockedIntent(
        context, AuthenticationActivity.class,
        HostNavigationCommand.createIntent(
            context, HostNavigationCommand.Destination.APP_PROTECTION_PREFERENCES), CHANGE_OWNER);
    now.set(151L);
    assertThatThrownBy(() -> store.requireLive(expired, CHANGE_OWNER, true))
        .isInstanceOf(BootstrapContinuationStore.InvalidContinuationException.class);

    now.set(200L);
    Intent stale = store.createUnlockedIntent(
        context, AuthenticationActivity.class,
        HostNavigationCommand.createIntent(
            context, HostNavigationCommand.Destination.APP_PROTECTION_PREFERENCES), CHANGE_OWNER);
    snapshot.set(new UnlockSession.Snapshot(10L, secret()));
    assertThatThrownBy(() -> store.requireLive(stale, CHANGE_OWNER, true))
        .isInstanceOf(BootstrapContinuationStore.InvalidContinuationException.class);

    Intent lost = store.createUnlockedIntent(
        context, AuthenticationActivity.class,
        HostNavigationCommand.createIntent(
            context, HostNavigationCommand.Destination.APP_PROTECTION_PREFERENCES), CHANGE_OWNER);
    store.clear();
    assertThatThrownBy(() -> store.requireLive(lost, CHANGE_OWNER, true))
        .isInstanceOf(BootstrapContinuationStore.InvalidContinuationException.class);
  }

  @Test public void successfulCacheGenerationTransitionRetainsOneShotPrivateReturn() throws Exception {
    Intent route = store.createUnlockedIntent(
        context, AuthenticationActivity.class,
        HostNavigationCommand.createIntent(
            context, HostNavigationCommand.Destination.APP_PROTECTION_PREFERENCES), CHANGE_OWNER);

    snapshot.set(new UnlockSession.Snapshot(8L, secret()));
    store.rebindToCurrentGeneration(route, CHANGE_OWNER);
    Intent target = store.consume(new Intent(route), CHANGE_OWNER);

    assertThat(HostNavigationCommand.consume(target))
        .isEqualTo(HostNavigationCommand.Destination.APP_PROTECTION_PREFERENCES);
    assertThatThrownBy(() -> store.consume(route, CHANGE_OWNER))
        .isInstanceOf(BootstrapContinuationStore.InvalidContinuationException.class);
  }

  @Test public void rejectsObsoleteShareBootstrapContinuation() {
    Intent target = new Intent(context, ShareActivity.class)
        .setAction(Intent.ACTION_SEND)
        .setType("image/png")
        .putExtra(Intent.EXTRA_TEXT, "private text");

    assertThatThrownBy(() -> store.createBootstrapIntent(
        context, AuthenticationActivity.class, target, UPGRADE_OWNER))
        .isInstanceOf(SecurityException.class);
  }

  @Test public void rejectsSecretAndNestedIntentExtras() {
    Intent nested = new Intent(context, ConversationListActivity.class)
        .putExtra("next_intent", new Intent(context, ConversationActivity.class));
    Intent secret = new Intent(context, ConversationListActivity.class)
        .putExtra("master_secret", secret());

    assertThatThrownBy(() -> store.createBootstrapIntent(
        context, AuthenticationActivity.class, nested, UPGRADE_OWNER))
        .isInstanceOf(SecurityException.class);
    assertThatThrownBy(() -> store.createBootstrapIntent(
        context, AuthenticationActivity.class, secret, UPGRADE_OWNER))
        .isInstanceOf(SecurityException.class);
  }

  @Test public void databaseMigrationDestinationRequiresLiveExactSurfaceToken() throws Exception {
    Intent migration = store.createBootstrapIntent(
        context, AuthenticationActivity.class,
        HostNavigationCommand.createIntent(context, HostNavigationCommand.Destination.ARCHIVE),
        MIGRATION_OWNER)
        .putExtra(AuthenticationActivity.EXTRA_SURFACE,
                  AuthenticationActivity.Surface.DATABASE_MIGRATION.name());
    Intent upgrade = store.createBootstrapIntent(
        context, AuthenticationActivity.class, migration, UPGRADE_OWNER);

    Intent resumedMigration = store.consume(upgrade, UPGRADE_OWNER);
    assertThat(AuthenticationActivity.isAllowedDatabaseMigrationIntent(resumedMigration)).isTrue();
    Intent resumedTarget = store.consume(resumedMigration, MIGRATION_OWNER);
    assertThat(HostNavigationCommand.consume(resumedTarget))
        .isEqualTo(HostNavigationCommand.Destination.ARCHIVE);

    Intent arbitrary = new Intent(context, AuthenticationActivity.class)
        .putExtra(AuthenticationActivity.EXTRA_SURFACE,
                  AuthenticationActivity.Surface.DATABASE_MIGRATION.name())
        .putExtra(BootstrapContinuationStore.EXTRA_DESTINATION, "CONVERSATION_LIST")
        .putExtra(BootstrapContinuationStore.EXTRA_TOKEN, "arbitrary-token");
    assertThatThrownBy(() -> store.createBootstrapIntent(
        context, AuthenticationActivity.class, arbitrary, UPGRADE_OWNER))
        .isInstanceOf(SecurityException.class);
  }

  @Test public void databaseMigrationDestinationRejectsCrossSurfaceSubstitution() {
    Intent welcome = store.createBootstrapIntent(
        context, AuthenticationActivity.class,
        new Intent(context, ConversationListActivity.class),
        AuthenticationActivity.owner(AuthenticationActivity.Surface.WELCOME))
        .putExtra(AuthenticationActivity.EXTRA_SURFACE,
                  AuthenticationActivity.Surface.DATABASE_MIGRATION.name());

    assertThatThrownBy(() -> store.createBootstrapIntent(
        context, AuthenticationActivity.class, welcome, UPGRADE_OWNER))
        .isInstanceOf(SecurityException.class);
  }

  @Test public void databaseMigrationDestinationRejectsExpiredAndStaleNestedToken() {
    Intent expired = store.createBootstrapIntent(
        context, AuthenticationActivity.class,
        new Intent(context, ConversationListActivity.class), MIGRATION_OWNER)
        .putExtra(AuthenticationActivity.EXTRA_SURFACE,
                  AuthenticationActivity.Surface.DATABASE_MIGRATION.name());
    now.set(151L);
    assertThatThrownBy(() -> store.createBootstrapIntent(
        context, AuthenticationActivity.class, expired, UPGRADE_OWNER))
        .isInstanceOf(SecurityException.class);

    now.set(200L);
    Intent stale = store.createBootstrapIntent(
        context, AuthenticationActivity.class,
        new Intent(context, ConversationListActivity.class), MIGRATION_OWNER)
        .putExtra(AuthenticationActivity.EXTRA_SURFACE,
                  AuthenticationActivity.Surface.DATABASE_MIGRATION.name());
    snapshot.set(new UnlockSession.Snapshot(8L, secret()));
    assertThatThrownBy(() -> store.createBootstrapIntent(
        context, AuthenticationActivity.class, stale, UPGRADE_OWNER))
        .isInstanceOf(SecurityException.class);
  }

    @Test public void bootstrapAndImportSourcesDoNotTransportSecretsOrNestedIntents() throws Exception {
        Path sourceRoot = sourceRoot();
        for (String source : new String[] {
                "PassphraseRequiredActionBarActivity.java",
                "AuthenticationActivity.java",
                "PassphraseChangeFragment.java",
                "WelcomeFragment.java",
                "PassphraseCreateFragment.java",
                "PassphrasePromptFragment.java",
                "ui/passphrasecreate/PassphraseCreateController.java",
                "ui/passphraseprompt/PassphrasePromptController.java",
                "ui/authentication/AuthenticationCompletionCoordinator.java",
                "DatabaseUpgradeFragment.java",
                "ui/databaseupgrade/DatabaseUpgradeController.java",
                "domain/upgrade/DatabaseUpgradePolicy.java",
                "DatabaseMigrationFragment.java",
                "ui/databasemigration/DatabaseMigrationController.java",
                "ImportExportFragment.java",
                "components/reminder/SystemSmsImportReminder.java",
                "service/ApplicationMigrationService.java"
        }) {
            String contents = new String(Files.readAllBytes(sourceRoot.resolve(source)),
                                 StandardCharsets.UTF_8);
            assertThat(contents)
                    .as(source)
                    .doesNotContain("putExtra(\"master_secret\"")
                    .doesNotContain("getParcelableExtra(getIntent(), \"master_secret\"")
                    .doesNotContain("putExtra(\"next_intent\"")
                    .doesNotContain("getParcelableExtra(getIntent(), \"next_intent\"");
        }
    }

    private static Path sourceRoot() {
        Path project = Path.of(System.getProperty("user.dir"));
        if (project.endsWith("app")) project = project.getParent();
        return project.resolve("app/src/main/java/org/smssecure/smssecure");
    }

  private static MasterSecret secret() {
    byte[] key = new byte[16];
    return new MasterSecret(new SecretKeySpec(key, "AES"),
                            new SecretKeySpec(key, "HmacSHA1"));
  }
}