/**
 * Copyright (C) 2011 Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.smssecure.smssecure;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.text.Editable;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import org.smssecure.smssecure.crypto.InvalidPassphraseException;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.crypto.MasterSecretStorageException;
import org.smssecure.smssecure.crypto.MasterSecretUtil;
import org.smssecure.smssecure.domain.security.UnlockSession;
import org.smssecure.smssecure.domain.security.WipeablePassphrase;
import org.smssecure.smssecure.service.KeyCachingService;
import org.smssecure.smssecure.ui.passphrasechange.PassphraseChangeController;
import org.smssecure.smssecure.util.DynamicLanguage;
import org.smssecure.smssecure.util.DynamicTheme;
import org.smssecure.smssecure.util.SilencePreferences;

/**
 * Activity for changing a user's local encryption passphrase.
 *
 * @author Moxie Marlinspike
 */

public class PassphraseChangeActivity extends PassphraseActivity {
  private static final String TAG = PassphraseChangeActivity.class.getSimpleName();

  private DynamicTheme    dynamicTheme    = new DynamicTheme();
  private DynamicLanguage dynamicLanguage = new DynamicLanguage();

  private EditText originalPassphrase;
  private EditText newPassphrase;
  private EditText repeatPassphrase;
  private Button   okButton;
  private Button   cancelButton;
  private PassphraseChangeController controller;
  private BroadcastReceiver           clearKeyReceiver;

  @Override
  public void onCreate(Bundle savedInstanceState) {
    dynamicTheme.onCreate(this);
    dynamicLanguage.onCreate(this);
    super.onCreate(savedInstanceState);

    setContentView(R.layout.change_passphrase_activity);

    controller = PassphraseChangeController.create(getApplicationContext());
    initializeResources();
    initializeClearKeyReceiver();
  }

  @Override
  public void onResume() {
    super.onResume();
    dynamicTheme.onResume(this);
    dynamicLanguage.onResume(this);
  }

  @Override
  protected void onDestroy() {
    if (clearKeyReceiver != null) {
      unregisterReceiver(clearKeyReceiver);
      clearKeyReceiver = null;
    }
    clearInputs();
    if (controller != null) controller.close();
    controller = null;
    super.onDestroy();
  }

  private void initializeResources() {
    this.originalPassphrase      = (EditText) findViewById(R.id.old_passphrase      );
    this.newPassphrase           = (EditText) findViewById(R.id.new_passphrase      );
    this.repeatPassphrase        = (EditText) findViewById(R.id.repeat_passphrase   );

    this.okButton                = (Button  ) findViewById(R.id.ok_button           );
    this.cancelButton            = (Button  ) findViewById(R.id.cancel_button       );

    this.okButton.setOnClickListener(new OkButtonClickListener());
    this.cancelButton.setOnClickListener(new CancelButtonClickListener());

    if (SilencePreferences.isPasswordDisabled(this)) {
      this.originalPassphrase.setVisibility(View.GONE);
    } else {
      this.originalPassphrase.setVisibility(View.VISIBLE);
    }
  }

  private void verifyAndSavePassphrases() {
    WipeablePassphrase original = readPassphrase(originalPassphrase);
    WipeablePassphrase replacement = readPassphrase(newPassphrase);
    WipeablePassphrase repeated = readPassphrase(repeatPassphrase);
    if (SilencePreferences.isPasswordDisabled(this)) {
      original.close();
      original = WipeablePassphrase.takeOwnership(
          MasterSecretUtil.UNENCRYPTED_PASSPHRASE.toCharArray());
    }

    UnlockSession unlockSession = UnlockSession.capture();
    clearInputs();
    okButton.setEnabled(false);
    controller.submit(original, replacement, repeated, unlockSession,
                      new PassphraseChangeCallback());
  }

  private WipeablePassphrase readPassphrase(EditText editText) {
    if (editText == null) return WipeablePassphrase.takeOwnership(new char[0]);
    Editable editable = editText.getText();
    return editable == null ? WipeablePassphrase.takeOwnership(new char[0])
                            : WipeablePassphrase.copyOf(editable);
  }

  private void clearInputs() {
    if (originalPassphrase != null) originalPassphrase.getText().clear();
    if (newPassphrase != null) newPassphrase.getText().clear();
    if (repeatPassphrase != null) repeatPassphrase.getText().clear();
  }

  private void initializeClearKeyReceiver() {
    clearKeyReceiver = new BroadcastReceiver() {
      @Override public void onReceive(Context context, Intent intent) {
        if (controller != null) controller.close();
        clearInputs();
        finish();
      }
    };
    ContextCompat.registerReceiver(this, clearKeyReceiver,
        new IntentFilter(KeyCachingService.CLEAR_KEY_EVENT), KeyCachingService.KEY_PERMISSION,
        null, ContextCompat.RECEIVER_NOT_EXPORTED);
  }

  private class CancelButtonClickListener implements OnClickListener {
    public void onClick(View v) {
      if (controller != null) controller.cancel();
      clearInputs();
      finish();
    }
  }

  private class OkButtonClickListener implements OnClickListener {
    public void onClick(View v) {
      verifyAndSavePassphrases();
    }
  }

  private final class PassphraseChangeCallback implements PassphraseChangeController.Callback {
    @Override public void onValidationFailure(PassphraseChangeController.ValidationFailure failure) {
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

    @Override public void onSuccess(MasterSecret masterSecret) {
      okButton.setEnabled(true);
      setMasterSecret(masterSecret);
    }

    @Override public void onFailure(Exception exception) {
      okButton.setEnabled(true);
      Log.w(TAG, "Unable to change passphrase", exception);
      if (exception instanceof InvalidPassphraseException) {
        originalPassphrase.setError(getString(
            R.string.PassphraseChangeActivity_incorrect_old_passphrase_exclamation));
        originalPassphrase.requestFocus();
      } else if (exception instanceof MasterSecretStorageException) {
        Toast.makeText(PassphraseChangeActivity.this, R.string.master_secret_storage_error,
                       Toast.LENGTH_LONG).show();
      }
    }
  }

  @Override
  protected void cleanup() {
    clearInputs();
    if (controller != null) controller.close();
    this.originalPassphrase = null;
    this.newPassphrase      = null;
    this.repeatPassphrase   = null;

    System.gc();
  }
}
