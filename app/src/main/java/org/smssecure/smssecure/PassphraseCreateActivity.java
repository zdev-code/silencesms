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
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;

import org.smssecure.smssecure.crypto.IdentityKeyUtil;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.crypto.MasterSecretStorageException;
import org.smssecure.smssecure.crypto.MasterSecretUtil;
import org.smssecure.smssecure.util.dualsim.DualSimUtil;
import org.smssecure.smssecure.util.dualsim.SubscriptionInfoCompat;
import org.smssecure.smssecure.util.dualsim.SubscriptionManagerCompat;
import org.smssecure.smssecure.util.SilencePreferences;
import org.smssecure.smssecure.util.VersionTracker;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor;

import java.util.List;

/**
 * Activity for creating a user's local encryption passphrase.
 *
 * @author Moxie Marlinspike
 */

public class PassphraseCreateActivity extends PassphraseActivity {
  private static final String TAG = PassphraseCreateActivity.class.getSimpleName();

  private AppTaskExecutor.TaskHandle secretTask;

  public PassphraseCreateActivity() { }

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    setContentView(R.layout.create_passphrase_activity);

    initializeResources();
  }

  private void initializeResources() {
    getSupportActionBar().setDisplayOptions(ActionBar.DISPLAY_SHOW_CUSTOM);
    getSupportActionBar().setCustomView(R.layout.centered_app_title);

    generateSecret();
  }

  private void generateSecret() {
    Context context = getApplicationContext();
    secretTask = AppTaskExecutor.getInstance().submitSerial(
        () -> {
      MasterSecret masterSecret = MasterSecretUtil.generateMasterSecret(context,
                                                                         MasterSecretUtil.UNENCRYPTED_PASSPHRASE);

      MasterSecretUtil.generateAsymmetricMasterSecret(context, masterSecret);

      SubscriptionManagerCompat subscriptionManagerCompat = SubscriptionManagerCompat.from(context);

      if (Build.VERSION.SDK_INT >= 22) {
        List<SubscriptionInfoCompat> activeSubscriptions = subscriptionManagerCompat.getActiveSubscriptionInfoList();
        DualSimUtil.generateKeysIfDoNotExist(context, masterSecret, activeSubscriptions, false);
      } else {
        IdentityKeyUtil.generateIdentityKeys(context, masterSecret, -1, false);
        subscriptionManagerCompat.updateActiveSubscriptionInfoList();
      }
      VersionTracker.updateLastSeenVersion(context);
      SilencePreferences.setPasswordDisabled(context, true);

      return masterSecret;
        },
        this::setMasterSecret,
        exception -> {
          Log.w(TAG, "Unable to generate master secret", exception);
          if (exception instanceof MasterSecretStorageException && !isFinishing()) {
            new AlertDialog.Builder(this)
                .setMessage(R.string.master_secret_storage_error)
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> finish())
                .setPositiveButton(R.string.retry, (dialog, which) -> generateSecret())
                .setCancelable(false)
                .show();
          }
        });
  }

  @Override
  protected void onDestroy() {
    if (secretTask != null) secretTask.cancel();
    secretTask = null;
    super.onDestroy();
  }

  @Override
  protected void cleanup() {
    System.gc();
  }
}
