package org.smssecure.smssecure;

import static org.assertj.core.api.Assertions.assertThat;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.PopupMenu;
import android.widget.ProgressBar;

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
import org.smssecure.smssecure.crypto.InvalidPassphraseException;
import org.smssecure.smssecure.domain.security.WipeablePassphrase;
import org.smssecure.smssecure.ui.passphraseprompt.PassphrasePromptController;
import org.smssecure.smssecure.util.SilencePreferences;

import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 35)
public class PassphrasePromptFragmentTest {
  private final Context context = ApplicationProvider.getApplicationContext();
  private FakeUnlockOperation operation;

  @Before
  public void setUp() {
    operation = new FakeUnlockOperation();
    PassphrasePromptFragment.setOperationFactoryForTests(fragment -> operation);
    SilencePreferences.setPasswordDisabled(context, false);
    BootstrapContinuationStore.getInstance().clear();
  }

  @After
  public void tearDown() {
    PassphrasePromptFragment.resetOperationFactoryForTests();
    SilencePreferences.setPasswordDisabled(context, false);
    BootstrapContinuationStore.getInstance().clear();
  }

  @Test
  public void submitCopiesEditableThenClearsItAndShowsInvalidPassphraseError() {
    AuthenticationActivity activity = launch();
    EditText editText = activity.findViewById(R.id.passphrase_edit);
    editText.setText("secret");

    activity.findViewById(R.id.ok_button).performClick();

    assertThat(editText.getText().toString()).isEmpty();
    assertThat(operation.passphrase).containsExactly("secret".toCharArray());
    operation.fail(new InvalidPassphraseException("wrong"));
    assertThat(editText.getText().toString()).isEmpty();
    assertThat(editText.getError().toString())
        .isEqualTo(activity.getString(
            R.string.PassphrasePromptActivity_invalid_passphrase_exclamation));
    assertThat(editText.isEnabled()).isTrue();
  }

  @Test
  public void progressAppearsOnlyAfterDelayAndPendingCallbackIsRemovedOnDestroy() {
    ActivityController<AuthenticationActivity> controller = launchController();
    AuthenticationActivity activity = controller.get();
    ProgressBar progress = activity.findViewById(R.id.unlock_progress);
    activity.<EditText>findViewById(R.id.passphrase_edit).setText("secret");
    activity.findViewById(R.id.ok_button).performClick();

    assertThat(progress.getVisibility()).isEqualTo(View.GONE);
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(
        PassphrasePromptFragment.UNLOCK_PROGRESS_DELAY_MILLIS - 1, TimeUnit.MILLISECONDS);
    assertThat(progress.getVisibility()).isEqualTo(View.GONE);
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.MILLISECONDS);
    assertThat(progress.getVisibility()).isEqualTo(View.VISIBLE);

    controller.destroy();
    assertThat(operation.closed).isTrue();
  }

  @Test
  public void destructionBeforeDelayPreventsLateProgressMutation() {
    ActivityController<AuthenticationActivity> controller = launchController();
    AuthenticationActivity activity = controller.get();
    ProgressBar progress = activity.findViewById(R.id.unlock_progress);
    activity.<EditText>findViewById(R.id.passphrase_edit).setText("secret");
    activity.findViewById(R.id.ok_button).performClick();

    controller.destroy();
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(
        PassphrasePromptFragment.UNLOCK_PROGRESS_DELAY_MILLIS, TimeUnit.MILLISECONDS);

    assertThat(progress.getVisibility()).isEqualTo(View.GONE);
    assertThat(operation.closed).isTrue();
  }

  @Test
  public void passwordDisabledInstallationUnlocksAutomaticallyWithScopedCompatibilityValue() {
    SilencePreferences.setPasswordDisabled(context, true);
    AuthenticationActivity activity = launch();
    Shadows.shadowOf(Looper.getMainLooper()).idle();

    assertThat(operation.passphrase).containsExactly(
        org.smssecure.smssecure.crypto.MasterSecretUtil.UNENCRYPTED_PASSPHRASE.toCharArray());
    assertThat(activity.findViewById(R.id.passphrase_edit).getVisibility())
        .isEqualTo(View.GONE);
    assertThat(activity.findViewById(R.id.ok_button).getVisibility()).isEqualTo(View.GONE);
  }

  @Test
  public void lockedPromptMenuLaunchesLogSubmitActivity() {
    AuthenticationActivity activity = launch();
    PopupMenu popup = new PopupMenu(activity, new View(activity));

    activity.onPrepareOptionsMenu(popup.getMenu());
    MenuItem submit = popup.getMenu().findItem(R.id.menu_submit_debug_logs);

    assertThat(submit).isNotNull();
    assertThat(activity.onOptionsItemSelected(submit)).isTrue();
    assertThat(Shadows.shadowOf(activity).getNextStartedActivity().getComponent().getClassName())
        .isEqualTo(LogSubmitActivity.class.getName());
  }

  private AuthenticationActivity launch() {
    return launchController().get();
  }

  private ActivityController<AuthenticationActivity> launchController() {
    Intent intent = AuthenticationActivity.createPromptPassphraseIntent(
        context, new Intent(context, ConversationListActivity.class));
    ActivityController<AuthenticationActivity> controller = Robolectric.buildActivity(
        AuthenticationActivity.class, intent).create().start().resume().visible();
    controller.get().getSupportFragmentManager().executePendingTransactions();
    return controller;
  }

  private static final class FakeUnlockOperation
      implements PassphrasePromptFragment.UnlockOperation {
    private PassphrasePromptController.Callback callback;
    private char[] passphrase;
    private boolean closed;

    @Override public void submit(WipeablePassphrase passphrase,
                                 PassphrasePromptController.Callback callback) {
      this.callback = callback;
      this.passphrase = passphrase.copy();
      passphrase.close();
    }

    @Override public void close() {
      closed = true;
      if (passphrase != null) java.util.Arrays.fill(passphrase, '\0');
    }

    private void fail(Exception exception) {
      callback.onFailure(exception);
    }
  }
}