/**
 * Copyright (C) 2013 Open Whisper Systems
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
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;

import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.domain.conversation.ConversationUnlockCapability;
import org.smssecure.smssecure.domain.security.UnlockSession;
import org.smssecure.smssecure.domain.upgrade.DatabaseUpgradeCoordinator;
import org.smssecure.smssecure.notifications.MessageNotifier;
import org.smssecure.smssecure.util.ParcelUtil;
import org.smssecure.smssecure.util.SilencePreferences;
import org.smssecure.smssecure.util.Util;
import org.smssecure.smssecure.util.VersionTracker;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor;
import org.whispersystems.jobqueue.EncryptionKeys;

import java.util.SortedSet;
import java.util.TreeSet;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class DatabaseUpgradeActivity extends BaseActivity {
  private static final String TAG = DatabaseUpgradeActivity.class.getSimpleName();
  public static final int ASK_FOR_SIM_CARD_VERSION     = 143;
  public static final int MULTI_SIM_MULTI_KEYS_VERSION = 200;
  public static final int MERGE_EQUIVALENT_PHONE_THREADS_VERSION = 216;

  private static final SortedSet<Integer> UPGRADE_VERSIONS = new TreeSet<Integer>() {{
    add(ASK_FOR_SIM_CARD_VERSION);
    add(MULTI_SIM_MULTI_KEYS_VERSION);
    add(MERGE_EQUIVALENT_PHONE_THREADS_VERSION);
  }};

  private ProgressBar       indeterminateProgress;
  private ProgressBar       determinateProgress;
  @Inject DatabaseUpgradeCoordinator upgradeCoordinator;
  private DatabaseUpgradeCoordinator.Subscription upgradeSubscription;
  private boolean navigationClaimed;
  private boolean upgradeRequired;
  private UnlockSession unlockSession;

  @Override
  public void onCreate(Bundle bundle) {
    super.onCreate(bundle);
    unlockSession = UnlockSession.capture();
    upgradeRequired = needsUpgradeTask();
    if (upgradeRequired) {
      Log.w(TAG, "Upgrading...");
      setContentView(R.layout.database_upgrade_activity);

      indeterminateProgress = (ProgressBar)findViewById(R.id.indeterminate_progress);
      determinateProgress   = (ProgressBar)findViewById(R.id.determinate_progress);

      upgradeCoordinator.start(VersionTracker.getLastSeenVersion(this),
          Util.getCurrentApkReleaseVersion(this),
          new ConversationUnlockCapability(unlockSession));
    } else {
      try {
        unlockSession.use(masterSecret -> {
          upgradeCoordinator.clearCompletedRecord();
          VersionTracker.updateLastSeenVersion(this);
          ApplicationContext.getInstance(this)
                            .getJobManager()
                            .setEncryptionKeys(new EncryptionKeys(ParcelUtil.serialize(masterSecret)));
          updateNotifications(this, masterSecret);
          return null;
        });
        startContinuationOrInbox();
      } catch (Exception exception) {
        Log.w(TAG, "Unlock changed before database upgrade finalization", exception);
        startFreshPolicyEvaluation();
      }
      finish();
    }
  }

  @Override
  protected void onStart() {
    super.onStart();
    if (upgradeRequired) {
      upgradeSubscription = upgradeCoordinator.observe(new DatabaseUpgradeCoordinator.Observer() {
        @Override public void onProgress(int progress, int total) {
          runOnUiThread(() -> renderProgress(progress, total));
        }
        @Override public void onComplete() { runOnUiThread(() -> handleUpgradeCompleted()); }
        @Override public void onFailure(Exception exception) {
          Log.w(TAG, "Database upgrade failed", exception);
        }
      });
    }
  }

  @Override
  protected void onStop() {
    if (upgradeSubscription != null) upgradeSubscription.close();
    upgradeSubscription = null;
    super.onStop();
  }

  private boolean needsUpgradeTask() {
    int currentVersionCode = Util.getCurrentApkReleaseVersion(this);
    int lastSeenVersion    = VersionTracker.getLastSeenVersion(this);

    Log.w(TAG, "LastSeenVersion: " + lastSeenVersion);

    if (lastSeenVersion >= currentVersionCode)
      return false;

    for (int version : UPGRADE_VERSIONS) {
      Log.w(TAG, "Comparing: " + version);
      if (lastSeenVersion < version)
        return true;
    }

    return false;
  }

  public static boolean isUpdate(Context context) {
    int currentVersionCode  = Util.getCurrentApkReleaseVersion(context);
    int previousVersionCode = VersionTracker.getLastSeenVersion(context);

    return previousVersionCode < currentVersionCode;
  }

  private static void updateNotifications(Context context, MasterSecret masterSecret) {
    Context applicationContext = context.getApplicationContext();
    AppTaskExecutor.getInstance().submitSerial(
        () -> {
          MessageNotifier.updateNotification(applicationContext, masterSecret);
          return null;
        },
        ignored -> {},
        exception -> Log.w(TAG, "Unable to update notifications after database upgrade", exception));
  }

  public interface DatabaseUpgradeListener {
    public void setProgress(int progress, int total);
  }

  private void renderProgress(int progress, int total) {
    if (total <= 0) return;

    indeterminateProgress.setVisibility(View.GONE);
    determinateProgress.setVisibility(View.VISIBLE);
    double scaled = Math.max(0.0, Math.min(1.0, progress / (double) total));
    determinateProgress.setProgress((int)Math.floor(determinateProgress.getMax() * scaled));
  }

  private void handleUpgradeCompleted() {
    if (navigationClaimed) return;
    navigationClaimed = true;
    upgradeCoordinator.clearCompletedRecord();

    startContinuationOrInbox();
    finish();
  }

  private void startContinuationOrInbox() {
    try {
      startActivity(BootstrapContinuationStore.getInstance()
          .consume(getIntent(), DatabaseUpgradeActivity.class));
    } catch (BootstrapContinuationStore.InvalidContinuationException exception) {
      startFreshPolicyEvaluation();
    }
  }

  private void startFreshPolicyEvaluation() {
    startActivity(new Intent(this, ConversationListActivity.class));
  }

}
