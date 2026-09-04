package org.smssecure.smssecure;

import static org.assertj.core.api.Assertions.assertThat;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.widget.EditText;

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
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.crypto.MasterSecretStorageException;
import org.smssecure.smssecure.crypto.MasterSecretUtil;
import org.smssecure.smssecure.domain.security.UnlockSession;
import org.smssecure.smssecure.domain.security.WipeablePassphrase;
import org.smssecure.smssecure.service.KeyCachingService;
import org.smssecure.smssecure.ui.authentication.AuthenticationCompletionCoordinator;
import org.smssecure.smssecure.ui.passphrasechange.PassphraseChangeController;
import org.smssecure.smssecure.util.SilencePreferences;

import java.util.Arrays;
import java.util.function.BooleanSupplier;

import javax.crypto.spec.SecretKeySpec;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 35)
public class PassphraseChangeFragmentTest {
  private final Context context = ApplicationProvider.getApplicationContext();
  private FakeChangeOperation changeOperation;
  private FakeCompletionOperation completionOperation;
  private MasterSecret unlockedSecret;

  @Before
  public void setUp() {
    changeOperation = new FakeChangeOperation();
    completionOperation = new FakeCompletionOperation();
    PassphraseChangeFragment.setOperationFactoryForTests(
        new PassphraseChangeFragment.OperationFactory() {
          @Override public PassphraseChangeFragment.ChangeOperation createChange(
              PassphraseChangeFragment fragment) {
            return changeOperation;
          }

          @Override public PassphraseChangeFragment.CompletionOperation createCompletion(
              PassphraseChangeFragment fragment) {
            return completionOperation;
          }
        });
    SilencePreferences.setPasswordDisabled(context, false);
    unlockedSecret = secret();
    KeyCachingService.primeMasterSecret(unlockedSecret);
    BootstrapContinuationStore.getInstance().clear();
  }

  @After
  public void tearDown() {
    PassphraseChangeFragment.resetOperationFactoryForTests();
    SilencePreferences.setPasswordDisabled(context, false);
    KeyCachingService.discardPrimedMasterSecret(unlockedSecret);
    unlockedSecret = null;
    BootstrapContinuationStore.getInstance().clear();
  }

  @Test
  public void submitCopiesEditableClearsFieldsAndShowsIncorrectOldError() {
    AuthenticationActivity activity = launchController().get();
    setInputs(activity, "old", "new", "new");

    activity.findViewById(R.id.ok_button).performClick();

    assertThat(changeOperation.original).containsExactly("old".toCharArray());
    assertThat(changeOperation.replacement).containsExactly("new".toCharArray());
    assertThat(changeOperation.repeated).containsExactly("new".toCharArray());
    assertInputsEmpty(activity);
    changeOperation.fail(new InvalidPassphraseException("wrong"));
    EditText old = activity.findViewById(R.id.old_passphrase);
    assertThat(old.getError().toString()).isEqualTo(activity.getString(
        R.string.PassphraseChangeActivity_incorrect_old_passphrase_exclamation));
    assertThat(activity.findViewById(R.id.ok_button).isEnabled()).isTrue();
  }

  @Test
  public void disabledPasswordHidesOldFieldAndUsesScopedCompatibilityBuffer() {
    SilencePreferences.setPasswordDisabled(context, true);
    AuthenticationActivity activity = launchController().get();
    setInputs(activity, "ignored", "new", "new");

    activity.findViewById(R.id.ok_button).performClick();

    assertThat(activity.findViewById(R.id.old_passphrase).getVisibility())
        .isEqualTo(android.view.View.GONE);
    assertThat(changeOperation.original)
        .containsExactly(MasterSecretUtil.UNENCRYPTED_PASSPHRASE.toCharArray());
  }

  @Test
  public void validationAndStorageErrorsPermitRetryWithoutNavigation() {
    AuthenticationActivity activity = launchController().get();
    setInputs(activity, "old", "new", "different");
    activity.findViewById(R.id.ok_button).performClick();

    changeOperation.validation(PassphraseChangeController.ValidationFailure.MISMATCH);
    EditText replacement = activity.findViewById(R.id.new_passphrase);
    assertThat(replacement.getError().toString()).isEqualTo(activity.getString(
        R.string.PassphraseChangeActivity_passphrases_dont_match_exclamation));

    setInputs(activity, "old", "new", "new");
    activity.findViewById(R.id.ok_button).performClick();
    changeOperation.fail(new MasterSecretStorageException("storage", new Exception("disk")));
    assertThat(activity.findViewById(R.id.ok_button).isEnabled()).isTrue();
    assertThat(Shadows.shadowOf(activity).getNextStartedActivity()).isNull();
  }

  @Test
  public void successEstablishesThenConsumesPrivateReturnOnce() {
    AuthenticationActivity activity = launchController().get();
    setInputs(activity, "old", "new", "new");
    activity.findViewById(R.id.ok_button).performClick();

    changeOperation.succeed(secret());
    assertThat(completionOperation.current.getAsBoolean()).isTrue();
    completionOperation.establish();

    Intent target = Shadows.shadowOf(activity).getNextStartedActivity();
    assertThat(target.getComponent().getClassName())
        .isEqualTo(ConversationListActivity.class.getName());
    assertThat(HostNavigationCommand.consume(target))
        .isEqualTo(HostNavigationCommand.Destination.APP_PROTECTION_PREFERENCES);
    assertThat(Shadows.shadowOf(activity).getNextStartedActivity()).isNull();
  }

  @Test
  public void cancelAndRelockCloseOperationsWipeInputsAndSuppressLateCallbacks() {
    ActivityController<AuthenticationActivity> cancelController = launchController();
    AuthenticationActivity cancelled = cancelController.get();
    setInputs(cancelled, "old", "new", "new");
    cancelled.findViewById(R.id.cancel_button).performClick();
    assertThat(cancelled.isFinishing()).isTrue();
    assertThat(changeOperation.closed).isTrue();
    assertThat(completionOperation.closed).isTrue();
    assertInputsEmpty(cancelled);

    changeOperation = new FakeChangeOperation();
    completionOperation = new FakeCompletionOperation();
    ActivityController<AuthenticationActivity> relockController = launchController();
    AuthenticationActivity relocked = relockController.get();
    setInputs(relocked, "old", "new", "new");
    relocked.findViewById(R.id.ok_button).performClick();
    relocked.sendBroadcast(new Intent(KeyCachingService.CLEAR_KEY_EVENT),
                           KeyCachingService.KEY_PERMISSION);
    Shadows.shadowOf(Looper.getMainLooper()).idle();
    assertThat(relocked.isFinishing()).isTrue();
    assertInputsEmpty(relocked);
    changeOperation.succeed(secret());
    assertThat(completionOperation.callback).isNull();
    assertThat(Shadows.shadowOf(relocked).getNextStartedActivity()).isNull();
  }

  @Test
  public void processLossBeforeRecreationFailsClosedWithoutRestoringForm() {
    ActivityController<AuthenticationActivity> controller = launchController();
    BootstrapContinuationStore.getInstance().clear();

    controller.recreate();

    AuthenticationActivity recreated = controller.get();
    assertThat(recreated.isFinishing()).isTrue();
    assertThat(Shadows.shadowOf(recreated).getNextStartedActivity().getComponent().getClassName())
        .isEqualTo(ConversationListActivity.class.getName());
  }

  private ActivityController<AuthenticationActivity> launchController() {
    ActivityController<AuthenticationActivity> controller = Robolectric.buildActivity(
        AuthenticationActivity.class,
        AuthenticationActivity.createChangePassphraseIntent(context))
        .create().start().resume().visible();
    controller.get().getSupportFragmentManager().executePendingTransactions();
    return controller;
  }

  private static void setInputs(AuthenticationActivity activity, String oldValue,
                                String newValue, String repeatedValue) {
    activity.<EditText>findViewById(R.id.old_passphrase).setText(oldValue);
    activity.<EditText>findViewById(R.id.new_passphrase).setText(newValue);
    activity.<EditText>findViewById(R.id.repeat_passphrase).setText(repeatedValue);
  }

  private static void assertInputsEmpty(AuthenticationActivity activity) {
    assertThat(activity.<EditText>findViewById(R.id.old_passphrase).getText().toString()).isEmpty();
    assertThat(activity.<EditText>findViewById(R.id.new_passphrase).getText().toString()).isEmpty();
    assertThat(activity.<EditText>findViewById(R.id.repeat_passphrase).getText().toString()).isEmpty();
  }

  private static MasterSecret secret() {
    return new MasterSecret(new SecretKeySpec(new byte[16], "AES"),
                            new SecretKeySpec(new byte[20], "HmacSHA1"));
  }

  private static final class FakeChangeOperation
      implements PassphraseChangeFragment.ChangeOperation {
    private PassphraseChangeController.Callback callback;
    private char[] original;
    private char[] replacement;
    private char[] repeated;
    private boolean closed;

    @Override public void submit(WipeablePassphrase original, WipeablePassphrase replacement,
                                 WipeablePassphrase repeated, UnlockSession unlockSession,
                                 PassphraseChangeController.Callback callback) {
      this.callback = callback;
      this.original = original.copy();
      this.replacement = replacement.copy();
      this.repeated = repeated.copy();
      original.close();
      replacement.close();
      repeated.close();
    }

    @Override public void close() {
      closed = true;
      if (original != null) Arrays.fill(original, '\0');
      if (replacement != null) Arrays.fill(replacement, '\0');
      if (repeated != null) Arrays.fill(repeated, '\0');
    }

    private void validation(PassphraseChangeController.ValidationFailure failure) {
      callback.onValidationFailure(failure);
    }

    private void succeed(MasterSecret masterSecret) { callback.onSuccess(masterSecret); }
    private void fail(Exception exception) { callback.onFailure(exception); }
  }

  private static final class FakeCompletionOperation
      implements PassphraseChangeFragment.CompletionOperation {
    private BooleanSupplier current;
    private AuthenticationCompletionCoordinator.Callback callback;
    private boolean closed;

    @Override public void establish(MasterSecret masterSecret, BooleanSupplier current,
                                    AuthenticationCompletionCoordinator.Callback callback) {
      this.current = current;
      this.callback = callback;
    }

    @Override public void close() {
      closed = true;
      current = null;
      callback = null;
    }

    private void establish() { callback.onEstablished(); }
  }
}