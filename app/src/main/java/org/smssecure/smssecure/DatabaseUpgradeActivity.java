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
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;

import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.database.DatabaseFactory;
import org.smssecure.smssecure.notifications.MessageNotifier;
import org.smssecure.smssecure.util.dualsim.DualSimUtil;
import org.smssecure.smssecure.util.dualsim.SubscriptionInfoCompat;
import org.smssecure.smssecure.util.dualsim.SubscriptionManagerCompat;
import org.smssecure.smssecure.util.ParcelUtil;
import org.smssecure.smssecure.util.SilencePreferences;
import org.smssecure.smssecure.util.Util;
import org.smssecure.smssecure.util.VersionTracker;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor;
import org.whispersystems.jobqueue.EncryptionKeys;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

public class DatabaseUpgradeActivity extends BaseActivity {
  private static final String TAG = DatabaseUpgradeActivity.class.getSimpleName();
  private static final Object UPGRADE_LOCK = new Object();

  private static UpgradeController activeUpgrade;

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
  private UpgradeController upgradeController;

  @Override
  public void onCreate(Bundle bundle) {
    super.onCreate(bundle);
    MasterSecret masterSecret = androidx.core.content.IntentCompat.getParcelableExtra(getIntent(), "master_secret", MasterSecret.class);

    if (needsUpgradeTask()) {
      Log.w(TAG, "Upgrading...");
      setContentView(R.layout.database_upgrade_activity);

      indeterminateProgress = (ProgressBar)findViewById(R.id.indeterminate_progress);
      determinateProgress   = (ProgressBar)findViewById(R.id.determinate_progress);

      synchronized (UPGRADE_LOCK) {
        if (activeUpgrade == null) {
          activeUpgrade = new UpgradeController(getApplicationContext(), masterSecret,
                                                VersionTracker.getLastSeenVersion(this));
          activeUpgrade.start();
        }
        upgradeController = activeUpgrade;
      }
    } else {
      VersionTracker.updateLastSeenVersion(this);
      ApplicationContext.getInstance(this)
                        .getJobManager()
                        .setEncryptionKeys(new EncryptionKeys(ParcelUtil.serialize(masterSecret)));
//      DecryptingQueue.schedulePendingDecrypts(DatabaseUpgradeActivity.this, masterSecret);
      updateNotifications(this, masterSecret);
      startActivity(androidx.core.content.IntentCompat.getParcelableExtra(getIntent(), "next_intent", Intent.class));
      finish();
    }
  }

  @Override
  protected void onStart() {
    super.onStart();
    if (upgradeController != null) upgradeController.attach(this);
  }

  @Override
  protected void onStop() {
    if (upgradeController != null) upgradeController.detach(this);
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

  private void renderProgress(boolean hasProgress, double progress) {
    if (!hasProgress) return;

    indeterminateProgress.setVisibility(View.GONE);
    determinateProgress.setVisibility(View.VISIBLE);
    determinateProgress.setProgress((int)Math.floor(determinateProgress.getMax() * progress));
  }

  private void handleUpgradeCompleted(UpgradeController controller) {
    if (!controller.claimSuccessfulNavigation(this)) return;

    synchronized (UPGRADE_LOCK) {
      if (activeUpgrade == controller) activeUpgrade = null;
    }
    upgradeController = null;

    startActivity(androidx.core.content.IntentCompat.getParcelableExtra(getIntent(), "next_intent", Intent.class));
    finish();
  }

  private static final class UpgradeController implements DatabaseUpgradeListener {
    private final Context      context;
    private final MasterSecret masterSecret;
    private final int          lastSeenVersion;
    private final Handler      mainHandler = new Handler(Looper.getMainLooper());

    private WeakReference<DatabaseUpgradeActivity> activityReference = new WeakReference<>(null);
    private boolean completed;
    private boolean failed;
    private boolean navigationClaimed;
    private boolean hasProgress;
    private double  progress;

    private UpgradeController(Context context, MasterSecret masterSecret, int lastSeenVersion) {
      this.context         = context.getApplicationContext();
      this.masterSecret    = masterSecret;
      this.lastSeenVersion = lastSeenVersion;
    }

    private void start() {
      AppTaskExecutor.getInstance().submitSerial(
          () -> {
            runUpgrade();
            ApplicationContext.getInstance(context)
                              .getJobManager()
                              .setEncryptionKeys(new EncryptionKeys(ParcelUtil.serialize(masterSecret)));
            MessageNotifier.updateNotification(context, masterSecret);
            VersionTracker.updateLastSeenVersion(context);
            return null;
          },
          ignored -> finishSuccessfully(),
          this::finishWithFailure);
    }

    private void runUpgrade() {
      Log.w(TAG, "Running background upgrade..");
      DatabaseFactory.getInstance(context)
                     .onApplicationLevelUpgrade(context, masterSecret, lastSeenVersion, this);

      if (lastSeenVersion < ASK_FOR_SIM_CARD_VERSION) {
        if (!SilencePreferences.isFirstRun(context) &&
            SubscriptionManagerCompat.from(context).getActiveSubscriptionInfoList().size() > 1)
        {
          SilencePreferences.setSimCardAsked(context, false);
        }
      }

      if (lastSeenVersion < MULTI_SIM_MULTI_KEYS_VERSION) {
        if (Build.VERSION.SDK_INT >= 22) {
          /*
           * getDefaultSubscriptionId() is available for API 24+ only, so we
           * move keys and sessions to SIM card in the first available slot,
           * not to the default one.
           */
          List<SubscriptionInfoCompat> subscriptionInfoList = SubscriptionManagerCompat.from(context).getActiveSubscriptionInfoList();
          int smallerSlot = -1;
          int eligibleDeviceSubscriptionId = -1;

          for (SubscriptionInfoCompat subscriptionInfo : subscriptionInfoList) {
            if (smallerSlot == -1 || subscriptionInfo.getIccSlot() < smallerSlot) {
              smallerSlot                  = subscriptionInfo.getIccSlot();
              eligibleDeviceSubscriptionId = subscriptionInfo.getDeviceSubscriptionId();
            }
          }

          DualSimUtil.moveIdentityKeysAndSessionsToSubscriptionId(context, -1, eligibleDeviceSubscriptionId);
          DualSimUtil.generateKeysIfDoNotExist(context, masterSecret, subscriptionInfoList);
          SubscriptionManagerCompat.from(context).updateActiveSubscriptionInfoList();
        }
      }
    }

    private void attach(DatabaseUpgradeActivity activity) {
      activityReference = new WeakReference<>(activity);
      render(activity);
    }

    private void detach(DatabaseUpgradeActivity activity) {
      if (activityReference.get() == activity) activityReference.clear();
    }

    private void finishSuccessfully() {
      completed = true;
      DatabaseUpgradeActivity activity = activityReference.get();
      if (activity != null) activity.handleUpgradeCompleted(this);
    }

    private void finishWithFailure(Exception exception) {
      failed = true;
      Log.w(TAG, "Database upgrade failed", exception);
      DatabaseUpgradeActivity activity = activityReference.get();
      if (activity != null) render(activity);
    }

    private boolean claimSuccessfulNavigation(DatabaseUpgradeActivity activity) {
      if (!completed || failed || navigationClaimed || activityReference.get() != activity) return false;
      navigationClaimed = true;
      return true;
    }

    private void render(DatabaseUpgradeActivity activity) {
      activity.renderProgress(hasProgress, progress);
      if (completed && !failed) activity.handleUpgradeCompleted(this);
    }

    @Override
    public void setProgress(int progress, int total) {
      if (total <= 0) return;

      double scaledProgress = Math.max(0.0, Math.min(1.0, progress / (double)total));
      mainHandler.post(() -> {
        hasProgress = true;
        this.progress = scaledProgress;
        DatabaseUpgradeActivity activity = activityReference.get();
        if (activity != null) activity.renderProgress(true, scaledProgress);
      });
    }
  }
}
