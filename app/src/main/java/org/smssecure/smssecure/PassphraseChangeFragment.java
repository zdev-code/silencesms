package org.smssecure.smssecure;

import android.os.Bundle;
import android.text.Editable;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;

import org.smssecure.smssecure.crypto.InvalidPassphraseException;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.crypto.MasterSecretStorageException;
import org.smssecure.smssecure.crypto.MasterSecretUtil;
import org.smssecure.smssecure.domain.security.UnlockSession;
import org.smssecure.smssecure.domain.security.WipeablePassphrase;
import org.smssecure.smssecure.ui.authentication.AuthenticationCompletionCoordinator;
import org.smssecure.smssecure.ui.passphrasechange.PassphraseChangeController;
import org.smssecure.smssecure.util.SilencePreferences;

import java.util.function.BooleanSupplier;

public final class PassphraseChangeFragment extends Fragment {
  private static final String TAG = PassphraseChangeFragment.class.getSimpleName();

  interface Callback {
    void onPassphraseChangeCompleted(@NonNull PassphraseChangeFragment fragment);
    void onPassphraseChangeCancelled(@NonNull PassphraseChangeFragment fragment);
  }

  interface ChangeOperation {
    void submit(WipeablePassphrase original, WipeablePassphrase replacement,
                WipeablePassphrase repeated, UnlockSession unlockSession,
                PassphraseChangeController.Callback callback);
    void close();
  }

  interface CompletionOperation {
    void establish(MasterSecret masterSecret, BooleanSupplier current,
                   AuthenticationCompletionCoordinator.Callback callback);
    void close();
  }

  interface OperationFactory {
    ChangeOperation createChange(PassphraseChangeFragment fragment);
    CompletionOperation createCompletion(PassphraseChangeFragment fragment);
  }

  private static final OperationFactory DEFAULT_OPERATION_FACTORY = new OperationFactory() {
    @Override public ChangeOperation createChange(PassphraseChangeFragment fragment) {
      PassphraseChangeController controller =
          PassphraseChangeController.create(fragment.requireContext());
      return new ChangeOperation() {
        @Override public void submit(WipeablePassphrase original,
                                     WipeablePassphrase replacement,
                                     WipeablePassphrase repeated,
                                     UnlockSession unlockSession,
                                     PassphraseChangeController.Callback callback) {
          controller.submit(original, replacement, repeated, unlockSession, callback);
        }

        @Override public void close() { controller.close(); }
      };
    }

    @Override public CompletionOperation createCompletion(PassphraseChangeFragment fragment) {
      AuthenticationCompletionCoordinator coordinator =
          AuthenticationCompletionCoordinator.create(fragment.requireActivity());
      return new CompletionOperation() {
        @Override public void establish(MasterSecret masterSecret, BooleanSupplier current,
                                        AuthenticationCompletionCoordinator.Callback callback) {
          coordinator.establish(masterSecret, current, callback);
        }

        @Override public void close() { coordinator.close(); }
      };
    }
  };

  private static OperationFactory operationFactory = DEFAULT_OPERATION_FACTORY;

  private EditText originalPassphrase;
  private EditText newPassphrase;
  private EditText repeatPassphrase;
  private Button okButton;
  private ChangeOperation changeOperation;
  private CompletionOperation completionOperation;
  private UnlockSession activeUnlockSession;

  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater,
                           @Nullable ViewGroup container,
                           @Nullable Bundle savedInstanceState) {
    return inflater.inflate(R.layout.change_passphrase_activity, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);
    changeOperation = operationFactory.createChange(this);
    completionOperation = operationFactory.createCompletion(this);
    originalPassphrase = view.findViewById(R.id.old_passphrase);
    newPassphrase = view.findViewById(R.id.new_passphrase);
    repeatPassphrase = view.findViewById(R.id.repeat_passphrase);
    okButton = view.findViewById(R.id.ok_button);
    Button cancelButton = view.findViewById(R.id.cancel_button);
    okButton.setOnClickListener(ignored -> verifyAndSavePassphrases());
    cancelButton.setOnClickListener(ignored -> cancel());
    originalPassphrase.setVisibility(
        SilencePreferences.isPasswordDisabled(requireContext()) ? View.GONE : View.VISIBLE);
  }

  private void verifyAndSavePassphrases() {
    if (changeOperation == null || okButton == null) return;
    WipeablePassphrase original = readPassphrase(originalPassphrase);
    WipeablePassphrase replacement = readPassphrase(newPassphrase);
    WipeablePassphrase repeated = readPassphrase(repeatPassphrase);
    if (SilencePreferences.isPasswordDisabled(requireContext())) {
      original.close();
      original = WipeablePassphrase.takeOwnership(
          MasterSecretUtil.UNENCRYPTED_PASSPHRASE.toCharArray());
    }

    UnlockSession unlockSession = UnlockSession.capture();
    activeUnlockSession = unlockSession;
    clearInputs();
    okButton.setEnabled(false);
    changeOperation.submit(original, replacement, repeated, unlockSession,
                 new PassphraseChangeCallback(unlockSession));
  }

  private static WipeablePassphrase readPassphrase(@Nullable EditText editText) {
    if (editText == null) return WipeablePassphrase.takeOwnership(new char[0]);
    Editable editable = editText.getText();
    return editable == null ? WipeablePassphrase.takeOwnership(new char[0])
                            : WipeablePassphrase.copyOf(editable);
  }

  private void cancel() {
    clearSensitiveState();
    callback().onPassphraseChangeCancelled(this);
  }

  void clearSensitiveState() {
    clearInputs();
    activeUnlockSession = null;
    if (completionOperation != null) completionOperation.close();
    completionOperation = null;
    if (changeOperation != null) changeOperation.close();
    changeOperation = null;
  }

  private void clearInputs() {
    if (originalPassphrase != null) originalPassphrase.getText().clear();
    if (newPassphrase != null) newPassphrase.getText().clear();
    if (repeatPassphrase != null) repeatPassphrase.getText().clear();
  }

  private boolean isCurrent(UnlockSession unlockSession) {
    return activeUnlockSession == unlockSession && unlockSession.isCurrent() && isAdded() &&
        getView() != null && getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED) &&
        !requireActivity().isFinishing() && !requireActivity().isDestroyed();
  }

  private Callback callback() {
    if (!(requireActivity() instanceof Callback)) {
      throw new IllegalStateException("Passphrase change host must implement callback");
    }
    return (Callback) requireActivity();
  }

  private final class PassphraseChangeCallback implements PassphraseChangeController.Callback {
    private final UnlockSession unlockSession;

    private PassphraseChangeCallback(UnlockSession unlockSession) {
      this.unlockSession = unlockSession;
    }

    @Override public void onValidationFailure(PassphraseChangeController.ValidationFailure failure) {
      if (!isCurrent(unlockSession)) return;
      okButton.setEnabled(true);
      if (failure == PassphraseChangeController.ValidationFailure.MISMATCH) {
        newPassphrase.setError(getString(
            R.string.PassphraseChangeActivity_passphrases_dont_match_exclamation));
      } else {
        newPassphrase.setError(getString(
            R.string.PassphraseChangeActivity_enter_new_passphrase_exclamation));
      }
      newPassphrase.requestFocus();
    }

    @Override public void onSuccess(@NonNull MasterSecret masterSecret) {
      if (!isCurrent(unlockSession) || completionOperation == null) return;
      completionOperation.establish(masterSecret, () -> isCurrent(unlockSession),
          new AuthenticationCompletionCoordinator.Callback() {
            @Override public void onEstablished() {
              if (!isCurrent(unlockSession)) return;
              activeUnlockSession = null;
              callback().onPassphraseChangeCompleted(PassphraseChangeFragment.this);
            }

            @Override public void onFailure(@NonNull Exception exception) {
              if (!isCurrent(unlockSession)) return;
              activeUnlockSession = null;
              okButton.setEnabled(true);
              Toast.makeText(requireContext(), R.string.master_secret_storage_error,
                             Toast.LENGTH_LONG).show();
            }
          });
    }

    @Override public void onFailure(@NonNull Exception exception) {
      if (!isCurrent(unlockSession)) return;
      activeUnlockSession = null;
      okButton.setEnabled(true);
      Log.w(TAG, "Unable to change passphrase", exception);
      if (exception instanceof InvalidPassphraseException) {
        originalPassphrase.setError(getString(
            R.string.PassphraseChangeActivity_incorrect_old_passphrase_exclamation));
        originalPassphrase.requestFocus();
      } else if (exception instanceof MasterSecretStorageException) {
        Toast.makeText(requireContext(), R.string.master_secret_storage_error,
                       Toast.LENGTH_LONG).show();
      }
    }
  }

  @Override
  public void onDestroyView() {
    clearSensitiveState();
    originalPassphrase = null;
    newPassphrase = null;
    repeatPassphrase = null;
    okButton = null;
    super.onDestroyView();
  }

  static void setOperationFactoryForTests(OperationFactory factory) {
    operationFactory = factory;
  }

  static void resetOperationFactoryForTests() {
    operationFactory = DEFAULT_OPERATION_FACTORY;
  }
}