package org.smssecure.smssecure;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.crypto.MasterSecretStorageException;
import org.smssecure.smssecure.ui.authentication.AuthenticationCompletionCoordinator;
import org.smssecure.smssecure.ui.passphrasecreate.PassphraseCreateController;

public final class PassphraseCreateFragment extends Fragment {
  private static final String TAG = PassphraseCreateFragment.class.getSimpleName();

  interface Callback {
    void onPassphraseCreateCompleted();
    void onPassphraseCreateCancelled();
  }

  interface CreationOperation {
    void start(PassphraseCreateController.Callback callback);
    void close();
  }

  interface OperationFactory {
    CreationOperation create(PassphraseCreateFragment fragment);
  }

  private static final OperationFactory DEFAULT_OPERATION_FACTORY = fragment -> {
    PassphraseCreateController controller =
        PassphraseCreateController.create(fragment.requireContext());
    return new CreationOperation() {
      @Override public void start(PassphraseCreateController.Callback callback) {
        controller.start(callback);
      }

      @Override public void close() {
        controller.close();
      }
    };
  };

  private static OperationFactory operationFactory = DEFAULT_OPERATION_FACTORY;

  private CreationOperation creationOperation;
  private AuthenticationCompletionCoordinator completionCoordinator;
  private AlertDialog storageErrorDialog;

  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater,
                           @Nullable ViewGroup container,
                           @Nullable Bundle savedInstanceState) {
    return inflater.inflate(R.layout.create_passphrase_activity, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);
    ActionBar actionBar = ((BaseActionBarActivity) requireActivity()).getSupportActionBar();
    if (actionBar == null) throw new IllegalStateException("Create passphrase requires an action bar");
    actionBar.setDisplayOptions(ActionBar.DISPLAY_SHOW_CUSTOM);
    actionBar.setCustomView(R.layout.centered_app_title);

    creationOperation = operationFactory.create(this);
    completionCoordinator = AuthenticationCompletionCoordinator.create(requireActivity());
    startCreation();
  }

  private void startCreation() {
    if (creationOperation == null) return;
    creationOperation.start(new PassphraseCreateController.Callback() {
      @Override
      public void onSuccess(@NonNull MasterSecret masterSecret) {
        if (!isAdded() || completionCoordinator == null) return;
        completionCoordinator.establish(masterSecret,
            new AuthenticationCompletionCoordinator.Callback() {
              @Override public void onEstablished() {
                if (!isAdded()) return;
                System.gc();
                callback().onPassphraseCreateCompleted();
              }

              @Override public void onFailure(@NonNull Exception exception) {
                showStorageError(exception);
              }
            });
      }

      @Override
      public void onFailure(@NonNull Exception exception) {
        if (exception instanceof MasterSecretStorageException) showStorageError(exception);
        else Log.w(TAG, "Unable to generate master secret", exception);
      }
    });
  }

  private void showStorageError(Exception exception) {
    Log.w(TAG, "Unable to establish master secret", exception);
    if (!isAdded() || storageErrorDialog != null) return;
    storageErrorDialog = new AlertDialog.Builder(requireContext())
        .setMessage(R.string.master_secret_storage_error)
        .setNegativeButton(android.R.string.cancel, (dialog, which) ->
            callback().onPassphraseCreateCancelled())
        .setPositiveButton(R.string.retry, (dialog, which) -> {
          storageErrorDialog = null;
          startCreation();
        })
        .setOnDismissListener(dialog -> storageErrorDialog = null)
        .setCancelable(false)
        .show();
  }

  private Callback callback() {
    if (!(requireActivity() instanceof Callback)) {
      throw new IllegalStateException("Create passphrase host must implement callback");
    }
    return (Callback) requireActivity();
  }

  @Override
  public void onDestroy() {
    if (storageErrorDialog != null) storageErrorDialog.dismiss();
    storageErrorDialog = null;
    if (completionCoordinator != null) completionCoordinator.close();
    completionCoordinator = null;
    if (creationOperation != null) creationOperation.close();
    creationOperation = null;
    super.onDestroy();
  }

  static void setOperationFactoryForTests(OperationFactory factory) {
    operationFactory = factory;
  }

  static void resetOperationFactoryForTests() {
    operationFactory = DEFAULT_OPERATION_FACTORY;
  }
}