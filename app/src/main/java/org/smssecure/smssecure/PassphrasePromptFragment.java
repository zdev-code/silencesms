package org.smssecure.smssecure;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.text.style.TypefaceSpan;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.fragment.app.Fragment;

import org.smssecure.smssecure.crypto.InvalidPassphraseException;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.crypto.MasterSecretUtil;
import org.smssecure.smssecure.domain.security.WipeablePassphrase;
import org.smssecure.smssecure.ui.authentication.AuthenticationCompletionCoordinator;
import org.smssecure.smssecure.ui.passphraseprompt.PassphrasePromptController;
import org.smssecure.smssecure.util.SilencePreferences;

public final class PassphrasePromptFragment extends Fragment {
  static final long UNLOCK_PROGRESS_DELAY_MILLIS = 500;

  interface Callback {
    void onPassphrasePromptCompleted();
  }

  interface UnlockOperation {
    void submit(WipeablePassphrase passphrase, PassphrasePromptController.Callback callback);
    void close();
  }

  interface OperationFactory {
    UnlockOperation create(PassphrasePromptFragment fragment);
  }

  private static final OperationFactory DEFAULT_OPERATION_FACTORY = fragment -> {
    PassphrasePromptController controller =
        PassphrasePromptController.create(fragment.requireContext());
    return new UnlockOperation() {
      @Override public void submit(WipeablePassphrase passphrase,
                                   PassphrasePromptController.Callback callback) {
        controller.submit(passphrase, callback);
      }

      @Override public void close() {
        controller.close();
      }
    };
  };

  private static OperationFactory operationFactory = DEFAULT_OPERATION_FACTORY;

  private EditText passphraseText;
  private ImageButton okButton;
  private ProgressBar unlockProgress;
  private View unlockContent;
  private UnlockOperation unlockOperation;
  private AuthenticationCompletionCoordinator completionCoordinator;
  private boolean unlocking;
  private final Runnable showUnlockProgress = () -> {
    if (!unlocking || !isAdded()) return;
    ActionBar actionBar = ((BaseActionBarActivity) requireActivity()).getSupportActionBar();
    if (actionBar != null) actionBar.show();
    if (unlockContent != null) unlockContent.setVisibility(View.VISIBLE);
    if (unlockProgress != null) unlockProgress.setVisibility(View.VISIBLE);
  };

  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater,
                           @Nullable ViewGroup container,
                           @Nullable Bundle savedInstanceState) {
    return inflater.inflate(R.layout.prompt_passphrase_activity, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);
    initializeResources(view);
    unlockOperation = operationFactory.create(this);
    completionCoordinator = AuthenticationCompletionCoordinator.create(requireActivity());

    if (SilencePreferences.isPasswordDisabled(requireContext())) {
      ActionBar actionBar = ((BaseActionBarActivity) requireActivity()).getSupportActionBar();
      if (actionBar != null) actionBar.hide();
      passphraseText.setVisibility(View.GONE);
      okButton.setVisibility(View.GONE);
      unlockContent.setVisibility(View.INVISIBLE);
      unlockProgress.post(() -> beginUnlock(
          WipeablePassphrase.copyOf(MasterSecretUtil.UNENCRYPTED_PASSPHRASE)));
    }
  }

  void populateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
    menu.clear();
    inflater.inflate(R.menu.log_submit, menu);
  }

  @SuppressLint("NonConstantResourceId")
  boolean handleOptionsItem(@NonNull MenuItem item) {
    if (item.getItemId() != R.id.menu_submit_debug_logs) return false;
    startActivity(new android.content.Intent(requireContext(), LogSubmitActivity.class));
    return true;
  }

  private void handlePassphrase() {
    if (unlocking || passphraseText == null) return;
    Editable editable = passphraseText.getText();
    WipeablePassphrase passphrase = WipeablePassphrase.copyOf(
        editable == null ? "" : editable);
    if (editable != null) editable.clear();
    beginUnlock(passphrase);
  }

  private void beginUnlock(WipeablePassphrase passphrase) {
    if (unlocking || unlockOperation == null) {
      passphrase.close();
      return;
    }

    setUnlocking(true);
    unlockOperation.submit(passphrase, new PassphrasePromptController.Callback() {
      @Override
      public void onSuccess(@NonNull MasterSecret masterSecret) {
        stopUnlockProgress();
        if (!isAdded() || completionCoordinator == null) return;
        completionCoordinator.establish(masterSecret,
            new AuthenticationCompletionCoordinator.Callback() {
              @Override public void onEstablished() {
                if (!isAdded()) return;
                callback().onPassphrasePromptCompleted();
              }

              @Override public void onFailure(@NonNull Exception exception) {
                setUnlocking(false);
              }
            });
      }

      @Override
      public void onFailure(@NonNull Exception exception) {
        setUnlocking(false);
        if (exception instanceof InvalidPassphraseException && passphraseText != null) {
          passphraseText.setText("");
          passphraseText.setError(
              getString(R.string.PassphrasePromptActivity_invalid_passphrase_exclamation));
        }
      }
    });
  }

  private void setUnlocking(boolean unlocking) {
    this.unlocking = unlocking;
    if (passphraseText != null) passphraseText.setEnabled(!unlocking);
    if (okButton != null) okButton.setEnabled(!unlocking);
    if (unlockProgress != null) {
      unlockProgress.removeCallbacks(showUnlockProgress);
      unlockProgress.setVisibility(View.GONE);
      if (unlocking) {
        unlockProgress.postDelayed(showUnlockProgress, UNLOCK_PROGRESS_DELAY_MILLIS);
      }
    }
  }

  private void stopUnlockProgress() {
    unlocking = false;
    if (unlockProgress == null) return;
    unlockProgress.removeCallbacks(showUnlockProgress);
    unlockProgress.setVisibility(View.GONE);
  }

  private void initializeResources(View view) {
    ActionBar actionBar = ((BaseActionBarActivity) requireActivity()).getSupportActionBar();
    if (actionBar == null) throw new IllegalStateException("Passphrase prompt requires an action bar");
    actionBar.show();
    actionBar.setDisplayOptions(ActionBar.DISPLAY_SHOW_CUSTOM);
    actionBar.setCustomView(R.layout.centered_app_title);

    okButton = view.findViewById(R.id.ok_button);
    passphraseText = view.findViewById(R.id.passphrase_edit);
    unlockProgress = view.findViewById(R.id.unlock_progress);
    unlockContent = view.findViewById(R.id.scroll_parent);
    SpannableString hint = new SpannableString(
        "  " + getString(R.string.PassphrasePromptActivity_enter_passphrase));
    hint.setSpan(new RelativeSizeSpan(0.9f), 0, hint.length(),
                 Spanned.SPAN_INCLUSIVE_INCLUSIVE);
    hint.setSpan(new TypefaceSpan("sans-serif"), 0, hint.length(),
                 Spanned.SPAN_INCLUSIVE_INCLUSIVE);

    passphraseText.setHint(hint);
    okButton.setOnClickListener(ignored -> handlePassphrase());
    passphraseText.setOnEditorActionListener(this::onEditorAction);
    passphraseText.setImeActionLabel(
        getString(R.string.prompt_passphrase_activity__unlock), EditorInfo.IME_ACTION_DONE);
  }

  private boolean onEditorAction(TextView view, int actionId, KeyEvent keyEvent) {
    if ((keyEvent == null && actionId == EditorInfo.IME_ACTION_DONE) ||
        (keyEvent != null && keyEvent.getAction() == KeyEvent.ACTION_DOWN &&
            actionId == EditorInfo.IME_NULL)) {
      handlePassphrase();
      return true;
    }
    return keyEvent != null && keyEvent.getAction() == KeyEvent.ACTION_UP &&
        actionId == EditorInfo.IME_NULL;
  }

  private Callback callback() {
    if (!(requireActivity() instanceof Callback)) {
      throw new IllegalStateException("Passphrase prompt host must implement callback");
    }
    return (Callback) requireActivity();
  }

  @Override
  public void onDestroyView() {
    if (unlockProgress != null) unlockProgress.removeCallbacks(showUnlockProgress);
    if (passphraseText != null) passphraseText.setText("");
    if (completionCoordinator != null) completionCoordinator.close();
    completionCoordinator = null;
    if (unlockOperation != null) unlockOperation.close();
    unlockOperation = null;
    unlocking = false;
    passphraseText = null;
    okButton = null;
    unlockProgress = null;
    unlockContent = null;
    super.onDestroyView();
  }

  static void setOperationFactoryForTests(OperationFactory factory) {
    operationFactory = factory;
  }

  static void resetOperationFactoryForTests() {
    operationFactory = DEFAULT_OPERATION_FACTORY;
  }
}