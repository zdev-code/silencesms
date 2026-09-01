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

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.app.ActionBar;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.text.style.TypefaceSpan;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.smssecure.smssecure.crypto.InvalidPassphraseException;
import org.smssecure.smssecure.crypto.MasterSecretUtil;
import org.smssecure.smssecure.util.DynamicIntroTheme;
import org.smssecure.smssecure.util.DynamicLanguage;
import org.smssecure.smssecure.util.SilencePreferences;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor;

/**
 * Activity that prompts for a user's passphrase.
 *
 * @author Moxie Marlinspike
 */
public class PassphrasePromptActivity extends PassphraseActivity {

  private static final long UNLOCK_PROGRESS_DELAY_MILLIS = 500;

  private DynamicIntroTheme dynamicTheme    = new DynamicIntroTheme();
  private DynamicLanguage   dynamicLanguage = new DynamicLanguage();

  private EditText                   passphraseText;
  private ImageButton                okButton;
  private ProgressBar                unlockProgress;
  private View                       unlockContent;
  private AppTaskExecutor.TaskHandle unlockTask;
  private boolean                    automaticUnlock;
  private boolean                    unlocking;
  private final Runnable showUnlockProgress = () -> {
    if (!unlocking) return;
    getSupportActionBar().show();
    unlockContent.setVisibility(View.VISIBLE);
    unlockProgress.setVisibility(View.VISIBLE);
  };

  @Override
  public void onCreate(Bundle savedInstanceState) {
    dynamicTheme.onCreate(this);
    dynamicLanguage.onCreate(this);
    super.onCreate(savedInstanceState);

    setContentView(R.layout.prompt_passphrase_activity);
    initializeResources();
    automaticUnlock = SilencePreferences.isPasswordDisabled(this);
    if (automaticUnlock) {
      getSupportActionBar().hide();
      passphraseText.setVisibility(View.GONE);
      okButton.setVisibility(View.GONE);
      unlockContent.setVisibility(View.INVISIBLE);
      unlockProgress.post(() -> beginUnlock(MasterSecretUtil.UNENCRYPTED_PASSPHRASE));
    }
  }

  @Override
  public void onResume() {
    super.onResume();
    dynamicTheme.onResume(this);
    dynamicLanguage.onResume(this);
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
  }

  @Override
  public boolean onPrepareOptionsMenu(Menu menu) {
    MenuInflater inflater = this.getMenuInflater();
    menu.clear();

    inflater.inflate(R.menu.log_submit, menu);
    super.onPrepareOptionsMenu(menu);
    return true;
  }

  @Override
  @SuppressLint("NonConstantResourceId")
  public boolean onOptionsItemSelected(MenuItem item) {
    super.onOptionsItemSelected(item);
    if (item.getItemId() == R.id.menu_submit_debug_logs) { handleLogSubmit(); return true; }

    return false;
  }

  private void handleLogSubmit() {
    Intent intent = new Intent(this, LogSubmitActivity.class);
    startActivity(intent);
  }

  private void handlePassphrase() {
    if (unlockTask != null) return;

    Editable text = passphraseText.getText();
    String passphrase = (text == null ? "" : text.toString());
    beginUnlock(passphrase);
  }

  private void beginUnlock(String passphrase) {
    if (unlockTask != null) return;

    setUnlocking(true);
    unlockTask = AppTaskExecutor.getInstance().submitSerial(
        () -> MasterSecretUtil.getMasterSecret(getApplicationContext(), passphrase),
        masterSecret -> {
          unlockTask = null;
          stopUnlockProgress();
          setMasterSecret(masterSecret);
        },
        exception -> {
          unlockTask = null;
          setUnlocking(false);
          if (exception instanceof InvalidPassphraseException) {
            passphraseText.setText("");
            passphraseText.setError(
                getString(R.string.PassphrasePromptActivity_invalid_passphrase_exclamation));
          }
        });
  }

  private void setUnlocking(boolean unlocking) {
    this.unlocking = unlocking;
    passphraseText.setEnabled(!unlocking);
    okButton.setEnabled(!unlocking);
    unlockProgress.removeCallbacks(showUnlockProgress);
    unlockProgress.setVisibility(View.GONE);
    if (unlocking) {
      unlockProgress.postDelayed(showUnlockProgress, UNLOCK_PROGRESS_DELAY_MILLIS);
    }
  }

  private void stopUnlockProgress() {
    unlocking = false;
    unlockProgress.removeCallbacks(showUnlockProgress);
    unlockProgress.setVisibility(View.GONE);
  }

  private void initializeResources() {
    getSupportActionBar().setDisplayOptions(ActionBar.DISPLAY_SHOW_CUSTOM);
    getSupportActionBar().setCustomView(R.layout.centered_app_title);

    okButton       = (ImageButton) findViewById(R.id.ok_button);
    passphraseText = (EditText)    findViewById(R.id.passphrase_edit);
    unlockProgress = (ProgressBar) findViewById(R.id.unlock_progress);
    unlockContent  = findViewById(R.id.scroll_parent);
    SpannableString hint = new SpannableString("  " + getString(R.string.PassphrasePromptActivity_enter_passphrase));
    hint.setSpan(new RelativeSizeSpan(0.9f), 0, hint.length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE);
    hint.setSpan(new TypefaceSpan("sans-serif"), 0, hint.length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE);

    passphraseText.setHint(hint);
    okButton.setOnClickListener(new OkButtonClickListener());
    passphraseText.setOnEditorActionListener(new PassphraseActionListener());
    passphraseText.setImeActionLabel(getString(R.string.prompt_passphrase_activity__unlock),
                                     EditorInfo.IME_ACTION_DONE);
  }

  private class PassphraseActionListener implements TextView.OnEditorActionListener {
    @Override
    public boolean onEditorAction(TextView exampleView, int actionId, KeyEvent keyEvent) {
      if ((keyEvent == null && actionId == EditorInfo.IME_ACTION_DONE) ||
          (keyEvent != null && keyEvent.getAction() == KeyEvent.ACTION_DOWN &&
              (actionId == EditorInfo.IME_NULL)))
      {
        handlePassphrase();
        return true;
      } else if (keyEvent != null && keyEvent.getAction() == KeyEvent.ACTION_UP &&
                 actionId == EditorInfo.IME_NULL)
      {
        return true;
      }

      return false;
    }
  }

  private class OkButtonClickListener implements OnClickListener {
    @Override
    public void onClick(View v) {
      handlePassphrase();
    }
  }

  @Override
  protected void cleanup() {
    this.passphraseText.setText("");
    System.gc();
  }

  @Override
  protected void onDestroy() {
    unlockProgress.removeCallbacks(showUnlockProgress);
    if (unlockTask != null) unlockTask.cancel();
    unlockTask = null;
    super.onDestroy();
  }
}
