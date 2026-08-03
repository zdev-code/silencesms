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

import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import org.smssecure.smssecure.crypto.InvalidPassphraseException;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.crypto.MasterSecretStorageException;
import org.smssecure.smssecure.crypto.MasterSecretUtil;
import org.smssecure.smssecure.util.DynamicLanguage;
import org.smssecure.smssecure.util.DynamicTheme;
import org.smssecure.smssecure.util.SilencePreferences;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor;

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
  private AppTaskExecutor.TaskHandle changeTask;

  @Override
  public void onCreate(Bundle savedInstanceState) {
    dynamicTheme.onCreate(this);
    dynamicLanguage.onCreate(this);
    super.onCreate(savedInstanceState);

    setContentView(R.layout.change_passphrase_activity);

    initializeResources();
  }

  @Override
  public void onResume() {
    super.onResume();
    dynamicTheme.onResume(this);
    dynamicLanguage.onResume(this);
  }

  @Override
  protected void onDestroy() {
    if (changeTask != null) changeTask.cancel();
    changeTask = null;
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
    String original;
    String passphrase;
    String passphraseRepeat;

    if (this.originalPassphrase == null) {
      original = "";
    } else {
      Editable originalText = this.originalPassphrase.getText();
      original = (originalText == null ? "" : originalText.toString());
    }

    if (this.newPassphrase == null) {
      passphrase = "";
    } else {
      Editable newText = this.newPassphrase.getText();
      passphrase = (newText == null ? "" : newText.toString());
    }

    if (this.repeatPassphrase == null) {
      passphraseRepeat = "";
    } else {
      Editable repeatText = this.repeatPassphrase.getText();
      passphraseRepeat = (repeatText == null ? "" : repeatText.toString());
    }

    if (SilencePreferences.isPasswordDisabled(this)) {
      original = MasterSecretUtil.UNENCRYPTED_PASSPHRASE;
    }

    if (!passphrase.equals(passphraseRepeat)) {
      this.newPassphrase.setText("");
      this.repeatPassphrase.setText("");
      this.newPassphrase.setError(getString(R.string.PassphraseChangeActivity_passphrases_dont_match_exclamation));
      this.newPassphrase.requestFocus();
    } else if (passphrase.equals("")) {
      this.newPassphrase.setError(getString(R.string.PassphraseChangeActivity_enter_new_passphrase_exclamation));
      this.newPassphrase.requestFocus();
    } else {
      originalPassphrase.setText("");
      newPassphrase.setText("");
      repeatPassphrase.setText("");
      changePassphrase(original, passphrase);
    }
  }

  private class CancelButtonClickListener implements OnClickListener {
    public void onClick(View v) {
      finish();
    }
  }

  private class OkButtonClickListener implements OnClickListener {
    public void onClick(View v) {
      verifyAndSavePassphrases();
    }
  }

  private void changePassphrase(String original, String passphrase) {
      Context context = getApplicationContext();
      okButton.setEnabled(false);
      changeTask = AppTaskExecutor.getInstance().submitSerial(
          () -> {
        MasterSecret masterSecret = MasterSecretUtil.changeMasterSecretPassphrase(context, original, passphrase);
        SilencePreferences.setPasswordDisabled(context, false);
        return masterSecret;
          },
          masterSecret -> {
            okButton.setEnabled(true);
            setMasterSecret(masterSecret);
          },
          exception -> {
            okButton.setEnabled(true);
            Log.w(TAG, "Unable to change passphrase", exception);
            if (exception instanceof InvalidPassphraseException) {
              originalPassphrase.setError(getString(R.string.PassphraseChangeActivity_incorrect_old_passphrase_exclamation));
              originalPassphrase.requestFocus();
            } else if (exception instanceof MasterSecretStorageException) {
              Toast.makeText(PassphraseChangeActivity.this,
                             R.string.master_secret_storage_error,
                             Toast.LENGTH_LONG).show();
            }
          });
    }

  @Override
  protected void cleanup() {
    this.originalPassphrase = null;
    this.newPassphrase      = null;
    this.repeatPassphrase   = null;

    System.gc();
  }
}
