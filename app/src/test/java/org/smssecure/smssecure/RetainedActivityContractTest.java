package org.smssecure.smssecure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class RetainedActivityContractTest {
  private final Context context = ApplicationProvider.getApplicationContext();

  @Test
  public void applicationHostIsPrivateAndLauncherRouterIsExported() throws Exception {
    assertThat(activityInfo(ConversationListActivity.class).exported).isFalse();
    assertThat(activityInfo(RoutingActivity.class).exported).isTrue();

    Intent launcher = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
    assertThat(launcher).isNotNull();
    assertThat(launcher.getComponent().getClassName())
        .isEqualTo(RoutingActivity.class.getName());
    assertThat(launcher.getAction()).isEqualTo(Intent.ACTION_MAIN);
    assertThat(launcher.getCategories()).containsExactly(Intent.CATEGORY_LAUNCHER);
    assertThat(launcher.getData()).isNull();
    assertThat(launcher.getExtras()).isNull();
  }

  @Test
  public void authenticationSurfacesArePrivateAndFragmentOwned() throws Exception {
    assertThat(activityInfo(AuthenticationActivity.class).exported).isFalse();
    assertActivityIsNotRegistered("org.smssecure.smssecure.WelcomeActivity");
    assertActivityIsNotRegistered("org.smssecure.smssecure.PassphraseCreateActivity");
    assertActivityIsNotRegistered("org.smssecure.smssecure.PassphrasePromptActivity");
    assertActivityIsNotRegistered("org.smssecure.smssecure.DatabaseUpgradeActivity");
    assertActivityIsNotRegistered("org.smssecure.smssecure.DatabaseMigrationActivity");
    assertActivityIsNotRegistered("org.smssecure.smssecure.PassphraseChangeActivity");
    assertThat(AuthenticationActivity.class.getSuperclass()).isEqualTo(BaseActionBarActivity.class);
    assertThat(androidx.fragment.app.Fragment.class.isAssignableFrom(WelcomeFragment.class)).isTrue();
    assertThat(androidx.fragment.app.Fragment.class.isAssignableFrom(PassphraseCreateFragment.class))
        .isTrue();
    assertThat(androidx.fragment.app.Fragment.class.isAssignableFrom(PassphrasePromptFragment.class))
      .isTrue();
    assertThat(androidx.fragment.app.Fragment.class.isAssignableFrom(DatabaseUpgradeFragment.class))
      .isTrue();
    assertThat(androidx.fragment.app.Fragment.class.isAssignableFrom(DatabaseMigrationFragment.class))
      .isTrue();
    assertThat(androidx.fragment.app.Fragment.class.isAssignableFrom(PassphraseChangeFragment.class))
      .isTrue();

    String authenticationHost = source("AuthenticationActivity.java");
    assertThat(authenticationHost)
        .doesNotContain("PassphraseRequiredActionBarActivity")
        .doesNotContain("NavHost")
        .doesNotContain("R.navigation")
        .doesNotContain("ConversationListFragment")
        .doesNotContain("ConversationScreenFragment")
        .doesNotContain("MasterSecret");
      assertThat(Files.exists(sourceRoot().resolve("PassphrasePromptActivity.java"))).isFalse();
      assertThat(Files.exists(sourceRoot().resolve("DatabaseUpgradeActivity.java"))).isFalse();
      assertThat(Files.exists(sourceRoot().resolve("DatabaseMigrationActivity.java"))).isFalse();
      assertThat(Files.exists(sourceRoot().resolve("PassphraseChangeActivity.java"))).isFalse();
      assertThat(Files.exists(sourceRoot().resolve("PassphraseActivity.java"))).isFalse();
  }

  @Test
  public void shareEntryAcceptsOnlyDeclaredSendMediaFamilies() throws Exception {
    assertThat(activityInfo(ShareActivity.class).exported).isTrue();
    for (String mimeType : new String[] {"text/plain", "image/png", "audio/ogg", "video/mp4"}) {
      Intent intent = new Intent(Intent.ACTION_SEND).setType(mimeType).setPackage(context.getPackageName());
      assertThat(context.getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                        .activityInfo.name)
          .isEqualTo(ShareActivity.class.getName());
    }
  }

  @Test
  public void sendToEntryOwnsEverySmsAndMmsScheme() throws Exception {
    assertThat(activityInfo(SmsSendtoActivity.class).exported).isTrue();
    for (String action : new String[] {Intent.ACTION_SENDTO, Intent.ACTION_VIEW}) {
      for (String scheme : new String[] {"sms", "smsto", "mms", "mmsto"}) {
        Intent intent = new Intent(action, Uri.parse(scheme + ":+15551234567"))
            .setPackage(context.getPackageName());
        assertThat(context.getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                          .activityInfo.name)
            .isEqualTo(SmsSendtoActivity.class.getName());
      }
    }
  }

  @Test
  public void externalPayloadBridgesTerminateAtPrivateGenerationGatedHost() {
    assertThat(PassphraseRequiredActionBarActivity.class.isAssignableFrom(ShareActivity.class)).isFalse();
    assertThat(androidx.activity.ComponentActivity.class.isAssignableFrom(ShareActivity.class)).isTrue();
    assertThat(PassphraseRequiredActionBarActivity.class.isAssignableFrom(ConversationListActivity.class))
        .isTrue();
    assertActivityIsNotRegistered("org.smssecure.smssecure.NewConversationActivity");
  }

  @Test
  public void popupWindowRetainsConversationActivityControllerContract() {
    assertThat(ConversationPopupActivity.class.getSuperclass()).isEqualTo(ConversationActivity.class);
    assertThat(androidx.fragment.app.Fragment.class.isAssignableFrom(ConversationScreenFragment.class))
        .isTrue();
  }

  @Test
  public void messageDetailsIsOwnedByFragmentDestinationOnly() {
    ComponentName legacyActivity = new ComponentName(
        context, "org.smssecure.smssecure.MessageDetailsActivity");

    assertThatThrownBy(() ->
        context.getPackageManager().getActivityInfo(legacyActivity, 0))
        .isInstanceOf(PackageManager.NameNotFoundException.class);
    assertThat(androidx.fragment.app.Fragment.class.isAssignableFrom(MessageDetailsFragment.class))
        .isTrue();
    assertThat(ConversationListDestination.MESSAGE_DETAILS.getId()).isEqualTo(R.id.message_details);
  }

  @Test
  public void mmsPromptAndImportExportAreOwnedByHostSurfacesOnly() {
    assertActivityIsNotRegistered("org.smssecure.smssecure.PromptMmsActivity");
    assertActivityIsNotRegistered("org.smssecure.smssecure.ImportExportActivity");
    assertThat(androidx.fragment.app.DialogFragment.class.isAssignableFrom(PromptMmsDialogFragment.class))
        .isTrue();
    assertThat(androidx.fragment.app.Fragment.class.isAssignableFrom(ImportExportFragment.class))
        .isTrue();
    assertThat(ConversationListDestination.IMPORT_EXPORT.getId()).isEqualTo(R.id.import_export);
  }

  @Test
  public void identityAndRecipientSettingsUseHostDestinations() {
    assertActivityIsNotRegistered("org.smssecure.smssecure.ViewIdentityActivity");
    assertActivityIsNotRegistered("org.smssecure.smssecure.RecipientPreferenceActivity");
    assertThat(androidx.fragment.app.Fragment.class.isAssignableFrom(ViewIdentityFragment.class)).isTrue();
    assertThat(androidx.fragment.app.Fragment.class.isAssignableFrom(VerifyIdentityFragment.class)).isTrue();
    assertThat(androidx.fragment.app.Fragment.class.isAssignableFrom(RecipientPreferenceFragment.class)).isTrue();
    assertThat(ConversationListDestination.VIEW_IDENTITY.getId()).isEqualTo(R.id.view_identity);
    assertThat(ConversationListDestination.VERIFY_IDENTITY.getId()).isEqualTo(R.id.verify_identity);
    assertThat(ConversationListDestination.RECIPIENT_PREFERENCES.getId())
        .isEqualTo(R.id.recipient_preferences);
  }

  @Test
  public void mediaDestinationsAreFragmentOwnedInsideRetainedPopupHost() {
    assertActivityIsNotRegistered("org.smssecure.smssecure.MediaOverviewActivity");
    assertActivityIsNotRegistered("org.smssecure.smssecure.MediaPreviewActivity");
    assertThat(androidx.fragment.app.Fragment.class.isAssignableFrom(MediaOverviewFragment.class)).isTrue();
    assertThat(androidx.fragment.app.Fragment.class.isAssignableFrom(MediaPreviewFragment.class)).isTrue();
    assertThat(ConversationListDestination.MEDIA_OVERVIEW.getId()).isEqualTo(R.id.media_overview);
    assertThat(ConversationListDestination.MEDIA_PREVIEW.getId()).isEqualTo(R.id.media_preview);
    assertThat(ConversationPopupActivity.class.getSuperclass()).isEqualTo(ConversationActivity.class);
  }

  @Test
  public void popupMediaPreviewUsesLocalBackStackMenuAndRelockOwnership() throws Exception {
    String conversation = source("ConversationActivity.java");
    assertThat(conversation)
        .contains(".replace(android.R.id.content, preview, PREVIEW_TAG)")
        .contains("transaction.addToBackStack(PREVIEW_BACK_STACK)")
        .contains("preview.populateOptionsMenu(menu)")
        .contains("preview.handleOptionsItem(item)")
        .contains("mediaPreviewDraftStore.clear()")
        .contains("preview.clearSensitiveState()")
        .contains("screen.clearSensitiveState()")
        .contains("if (!getSupportFragmentManager().popBackStackImmediate()) finish()")
        .doesNotContain("MediaPreviewActivity")
        .doesNotContain("putExtra(Intent.EXTRA_STREAM");

      assertThat(source("MediaPreviewFragment.java"))
        .contains("actionBar.setDisplayShowCustomEnabled(false)")
        .contains("actionBar.setDisplayHomeAsUpEnabled(true)")
        .contains("if (item.getItemId() == android.R.id.home)")
        .contains("((MediaNavigationHost) requireActivity()).closeMediaPreview()")
        .contains("if (viewModel != null) viewModel.clearSensitiveState()");

    String popup = source("ConversationPopupActivity.java");
    assertThat(popup)
        .contains("if (!isConversationScreenVisible()) return super.onPrepareOptionsMenu(menu)")
        .contains("if (!isConversationScreenVisible()) return super.onOptionsItemSelected(item)")
        .contains("if (isConversationScreenVisible()) focusCompose()")
        .contains("public void openMediaOverview(long threadId, long recipientId)");

    assertThat(source("BootstrapContinuationStore.java"))
        .doesNotContain("MEDIA_PREVIEW")
        .doesNotContain("MediaPreviewActivity");
  }

  @Test
  public void conflictIdentityVerificationUsesHostDestinationOnly() {
    assertActivityIsNotRegistered("org.smssecure.smssecure.VerifyIdentityActivity");
    assertThat(androidx.fragment.app.Fragment.class.isAssignableFrom(VerifyIdentityFragment.class))
        .isTrue();
    assertThat(ConversationListDestination.VERIFY_IDENTITY.getId()).isEqualTo(R.id.verify_identity);
  }

  @Test
  public void externalPayloadBridgesStillTargetGenerationGatedConversationOwner() {
    assertThat(PassphraseRequiredActionBarActivity.class.isAssignableFrom(SmsSendtoActivity.class))
        .isFalse();
    assertThat(PassphraseRequiredActionBarActivity.class.isAssignableFrom(ShareActivity.class))
      .isFalse();
    assertThat(androidx.activity.ComponentActivity.class.isAssignableFrom(SmsSendtoActivity.class))
      .isTrue();
    assertThat(androidx.activity.ComponentActivity.class.isAssignableFrom(ShareActivity.class))
      .isTrue();
    assertThat(PassphraseRequiredActionBarActivity.class.isAssignableFrom(ConversationActivity.class))
        .isTrue();
  }

    @Test
    public void normalSelectorsAreHostOwnedAndDeadCountryFlowIsRemoved() {
    assertActivityIsNotRegistered("org.smssecure.smssecure.PushContactSelectionActivity");
    assertActivityIsNotRegistered("org.smssecure.smssecure.CountrySelectionActivity");
    assertThat(androidx.fragment.app.Fragment.class.isAssignableFrom(NewConversationFragment.class))
      .isTrue();
    assertThat(androidx.fragment.app.Fragment.class
      .isAssignableFrom(PushContactSelectionFragment.class)).isTrue();
    assertThat(ConversationListDestination.NEW_CONVERSATION.getId()).isEqualTo(R.id.new_conversation);
    assertThat(ConversationListDestination.PUSH_CONTACT_SELECTION.getId())
      .isEqualTo(R.id.push_contact_selection);
    }

  private void assertActivityIsNotRegistered(String className) {
    ComponentName legacyActivity = new ComponentName(context, className);
    assertThatThrownBy(() -> context.getPackageManager().getActivityInfo(legacyActivity, 0))
        .isInstanceOf(PackageManager.NameNotFoundException.class);
  }

  private static String source(String fileName) throws Exception {
    return new String(Files.readAllBytes(sourceRoot().resolve(fileName)), StandardCharsets.UTF_8);
  }

  private static Path sourceRoot() {
    String projectDirectory = System.getProperty("user.dir");
    if (projectDirectory.endsWith("app")) {
      projectDirectory = Path.of(projectDirectory).getParent().toString();
    }
    return Path.of(projectDirectory, "app", "src", "main", "java",
                   "org", "smssecure", "smssecure");
  }

  @Test
  public void externalBridgesUseTypedNewConversationHostCommand() throws Exception {
    String projectDirectory = System.getProperty("user.dir");
    if (projectDirectory.endsWith("app")) projectDirectory = Path.of(projectDirectory).getParent().toString();
    Path sourceRoot = Path.of(projectDirectory, "app", "src", "main", "java", "org", "smssecure", "smssecure");

    assertThat(new String(Files.readAllBytes(sourceRoot.resolve("ShareActivity.java")),
                StandardCharsets.UTF_8))
        .contains("HostNavigationCommand.createNewConversationIntent(this, token)")
      .doesNotContain("MasterSecret")
      .doesNotContain("setContentView")
      .doesNotContain("ShareFragment")
        .doesNotContain("NewConversationActivity.class");
    assertThat(new String(Files.readAllBytes(sourceRoot.resolve("SmsSendtoActivity.java")),
                StandardCharsets.UTF_8))
        .contains("HostNavigationCommand.createNewConversationIntent(this, token)")
        .doesNotContain("ViewModel")
        .doesNotContain("setContentView")
        .doesNotContain("NewConversationActivity.class");
  }

  @SuppressWarnings("deprecation")
  private ActivityInfo activityInfo(Class<?> activityClass) throws Exception {
    return context.getPackageManager().getActivityInfo(
        new ComponentName(context, activityClass), 0);
  }
}