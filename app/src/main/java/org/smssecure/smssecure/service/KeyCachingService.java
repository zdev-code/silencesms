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
package org.smssecure.smssecure.service;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import android.util.Log;
import android.widget.RemoteViews;

import org.smssecure.smssecure.ApplicationContext;
import org.smssecure.smssecure.ConversationListActivity;
import org.smssecure.smssecure.DummyActivity;
import org.smssecure.smssecure.R;
import org.smssecure.smssecure.crypto.InvalidPassphraseException;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.crypto.MasterSecretUtil;
import org.smssecure.smssecure.domain.identity.ConflictIdentityStore;
import org.smssecure.smssecure.domain.security.UnlockSession;
import org.smssecure.smssecure.domain.upgrade.DatabaseUpgradePolicy;
import org.smssecure.smssecure.notifications.MessageNotifier;
import org.smssecure.smssecure.notifications.NotificationChannels;
import org.smssecure.smssecure.util.DynamicLanguage;
import org.smssecure.smssecure.util.ParcelUtil;
import org.smssecure.smssecure.util.SilencePreferences;
import org.whispersystems.jobqueue.EncryptionKeys;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Small service that stays running to keep a key cached in memory.
 *
 * @author Moxie Marlinspike
 */

public class KeyCachingService extends Service {

  public static final int SERVICE_RUNNING_ID = 4141;

  public  static final String KEY_PERMISSION           = "org.smssecure.smssecure.ACCESS_SECRETS";
  public  static final String NEW_KEY_EVENT            = "org.smssecure.smssecure.service.action.NEW_KEY_EVENT";
  public  static final String CLEAR_KEY_EVENT          = "org.smssecure.smssecure.service.action.CLEAR_KEY_EVENT";
  private static final String PASSPHRASE_EXPIRED_EVENT = "org.smssecure.smssecure.service.action.PASSPHRASE_EXPIRED_EVENT";
  public  static final String CLEAR_KEY_ACTION         = "org.smssecure.smssecure.service.action.CLEAR_KEY";
  public  static final String DISABLE_ACTION           = "org.smssecure.smssecure.service.action.DISABLE";
  public  static final String ACTIVITY_START_EVENT     = "org.smssecure.smssecure.service.action.ACTIVITY_START_EVENT";
  public  static final String ACTIVITY_STOP_EVENT      = "org.smssecure.smssecure.service.action.ACTIVITY_STOP_EVENT";
  public  static final String LOCALE_CHANGE_EVENT      = "org.smssecure.smssecure.service.action.LOCALE_CHANGE_EVENT";

  private DynamicLanguage dynamicLanguage = new DynamicLanguage();

  private PendingIntent pending;
  private int activitiesRunning = 0;
  private final IBinder binder  = new KeySetBinder();
  private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor(runnable ->
      new Thread(runnable, "key-caching-service"));

  private static MasterSecret masterSecret;
  private static MasterSecret pendingAuthenticationMasterSecret;
  private static long masterSecretGeneration;

  public KeyCachingService() {}

  public static synchronized MasterSecret getCachedMasterSecret() {
    if (masterSecret == pendingAuthenticationMasterSecret) return null;
    return masterSecret;
  }

  public static synchronized UnlockSession.Snapshot getSecretSnapshot() {
    return new UnlockSession.Snapshot(masterSecretGeneration, getCachedMasterSecret());
  }

  public static synchronized void markAuthenticationActivationPending(MasterSecret masterSecret) {
    pendingAuthenticationMasterSecret = masterSecret;
  }

  public static synchronized void primeMasterSecret(MasterSecret masterSecret) {
    if (masterSecret != null && KeyCachingService.masterSecret != masterSecret) {
      KeyCachingService.masterSecret = masterSecret;
      masterSecretGeneration++;
    }
  }

  public static synchronized void discardPrimedMasterSecret(MasterSecret expected) {
    if (pendingAuthenticationMasterSecret == expected) {
      pendingAuthenticationMasterSecret = null;
    }
    if (expected != null && KeyCachingService.masterSecret == expected) {
      KeyCachingService.masterSecret = null;
      masterSecretGeneration++;
    }
  }

  public static synchronized boolean isAuthenticationActivationPending(MasterSecret expected) {
    return expected != null && pendingAuthenticationMasterSecret == expected &&
        masterSecret == expected;
  }

  public static synchronized MasterSecret getMasterSecret(Context context) {
    if (masterSecret == null && SilencePreferences.isPasswordDisabled(context)) {
      try {
        MasterSecret masterSecret = MasterSecretUtil.getMasterSecret(context, MasterSecretUtil.UNENCRYPTED_PASSPHRASE);
        primeMasterSecret(masterSecret);
        Intent       intent       = new Intent(context, KeyCachingService.class);

        try {
          context.startService(intent);
        } catch (IllegalStateException e) {
          // Android O+ forbids starting a background service when the app is not in the
          // foreground (e.g. launched from a notification while locked). The master secret
          // has already been retrieved above, so it is safe to skip the caching start here.
          Log.w("KeyCachingService", "Unable to start service from background", e);
        }

        return masterSecret;
      } catch (InvalidPassphraseException e) {
        Log.w("KeyCachingService", e);
      }
    }

    return masterSecret;
  }

  public void setMasterSecret(final MasterSecret masterSecret) {
    if (masterSecret == null) {
      Log.w("KeyCachingService", "Ignoring null master secret");
      return;
    }
    synchronized (KeyCachingService.class) {
      if (pendingAuthenticationMasterSecret == masterSecret) {
        pendingAuthenticationMasterSecret = null;
      }
      if (KeyCachingService.masterSecret != masterSecret) {
        KeyCachingService.masterSecret = masterSecret;
        masterSecretGeneration++;
      }

      foregroundService();
      broadcastNewSecret();
      startTimeoutIfAppropriate();

      executeInBackground(() -> {
        if (!DatabaseUpgradePolicy.isUpdate(KeyCachingService.this)) {
          ApplicationContext.getInstance(KeyCachingService.this)
                            .getJobManager()
                            .setEncryptionKeys(new EncryptionKeys(ParcelUtil.serialize(masterSecret)));
          MessageNotifier.updateNotification(KeyCachingService.this, masterSecret);
        }
      });
    }
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent == null) return START_NOT_STICKY;
    Log.w("KeyCachingService", "onStartCommand, " + intent.getAction());

    if (intent.getAction() != null) {
      switch (intent.getAction()) {
        case CLEAR_KEY_ACTION:         handleClearKey();        break;
        case ACTIVITY_START_EVENT:     handleActivityStarted(); break;
        case ACTIVITY_STOP_EVENT:      handleActivityStopped(); break;
        case PASSPHRASE_EXPIRED_EVENT: handleClearKey();        break;
        case DISABLE_ACTION:           handleDisableService();  break;
        case LOCALE_CHANGE_EVENT:      handleLocaleChanged();   break;
      }
    }

    return START_NOT_STICKY;
  }

  @Override
  public void onCreate() {
    Log.w("KeyCachingService", "onCreate()");
    super.onCreate();
  this.pending = PendingIntent.getService(this, 0, new Intent(PASSPHRASE_EXPIRED_EVENT, null,
                                this, KeyCachingService.class), PendingIntent.FLAG_IMMUTABLE);

    MasterSecret cachedMasterSecret = getCachedMasterSecret();
    if (cachedMasterSecret != null) {
      setMasterSecret(cachedMasterSecret);
    } else if (SilencePreferences.isPasswordDisabled(this) &&
           !hasPendingAuthenticationActivation()) {
      try {
        MasterSecret masterSecret = MasterSecretUtil.getMasterSecret(this, MasterSecretUtil.UNENCRYPTED_PASSPHRASE);
        setMasterSecret(masterSecret);
      } catch (InvalidPassphraseException e) {
        Log.w("KeyCachingService", e);
      }
    }
  }

  @Override
  public void onDestroy() {
    Log.w("KeyCachingService", "KCS Is Being Destroyed!");
    handleClearKey();
    backgroundExecutor.shutdown();
    super.onDestroy();
  }

  /**
   * Workaround for Android bug:
   * https://code.google.com/p/android/issues/detail?id=53313
   */
  @Override
  public void onTaskRemoved(Intent rootIntent) {
    Intent intent = new Intent(this, DummyActivity.class);
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    startActivity(intent);
  }

  private void handleActivityStarted() {
    Log.w("KeyCachingService", "Incrementing activity count...");

    AlarmManager alarmManager = (AlarmManager)this.getSystemService(ALARM_SERVICE);
    alarmManager.cancel(pending);
    activitiesRunning++;
  }

  private void handleActivityStopped() {
    Log.w("KeyCachingService", "Decrementing activity count...");

    activitiesRunning--;
    startTimeoutIfAppropriate();
  }

  private void handleClearKey() {
    Log.w("KeyCachingService", "handleClearKey()");
    synchronized (KeyCachingService.class) {
      KeyCachingService.masterSecret = null;
      pendingAuthenticationMasterSecret = null;
      masterSecretGeneration++;
    }
    ConflictIdentityStore.getInstance().clear();
    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);

    Intent intent = new Intent(CLEAR_KEY_EVENT);
    intent.setPackage(getApplicationContext().getPackageName());

    sendBroadcast(intent, KEY_PERMISSION);

    executeInBackground(() -> MessageNotifier.updateNotification(KeyCachingService.this, null));
  }

  private void executeInBackground(Runnable work) {
    try {
      backgroundExecutor.execute(work);
    } catch (RejectedExecutionException exception) {
      Log.w("KeyCachingService", "Ignoring work submitted after service shutdown", exception);
    }
  }

  private static synchronized boolean hasPendingAuthenticationActivation() {
    return pendingAuthenticationMasterSecret != null;
  }

  private void handleDisableService() {
    if (SilencePreferences.isPasswordDisabled(this))
      ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
  }

  private void handleLocaleChanged() {
    dynamicLanguage.updateServiceLocale(this);
    foregroundService();
  }

  private void startTimeoutIfAppropriate() {
    boolean timeoutEnabled = SilencePreferences.isPassphraseTimeoutEnabled(this);

    if ((activitiesRunning == 0) && (KeyCachingService.masterSecret != null) && timeoutEnabled && !SilencePreferences.isPasswordDisabled(this)) {
      long timeoutMinutes = SilencePreferences.getPassphraseTimeoutInterval(this);
      long timeoutMillis  = TimeUnit.MINUTES.toMillis(timeoutMinutes);

      Log.w("KeyCachingService", "Starting timeout: " + timeoutMillis);

      AlarmManager alarmManager = (AlarmManager)this.getSystemService(ALARM_SERVICE);
      alarmManager.cancel(pending);
      alarmManager.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + timeoutMillis, pending);
    }
  }

  private void foregroundServiceModern() {
    Log.w("KeyCachingService", "foregrounding KCS");
    NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NotificationChannels.LOCKED_STATUS);

    builder.setContentTitle(getString(R.string.KeyCachingService_passphrase_cached));
    builder.setContentText(getString(R.string.KeyCachingService_silence_passphrase_cached));
    builder.setSmallIcon(R.drawable.icon_cached);
    builder.setWhen(0);
    builder.setPriority(NotificationCompat.PRIORITY_MIN);

    builder.addAction(R.drawable.ic_menu_lock_dark, getString(R.string.KeyCachingService_lock), buildLockIntent());
    builder.setContentIntent(buildLaunchIntent());

    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
    startForegroundCompat(builder.build());
  }

  private void foregroundServiceICS() {
    NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NotificationChannels.LOCKED_STATUS);
    RemoteViews remoteViews            = new RemoteViews(getPackageName(), R.layout.key_caching_notification);

    remoteViews.setOnClickPendingIntent(R.id.lock_cache_icon, buildLockIntent());

    builder.setSmallIcon(R.drawable.icon_cached);
    builder.setContent(remoteViews);
    builder.setContentIntent(buildLaunchIntent());

    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
    startForegroundCompat(builder.build());
  }

  private void foregroundServiceLegacy() {
    NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NotificationChannels.LOCKED_STATUS);
    builder.setSmallIcon(R.drawable.icon_cached);
    builder.setWhen(System.currentTimeMillis());

    builder.setContentTitle(getString(R.string.KeyCachingService_passphrase_cached));
    builder.setContentText(getString(R.string.KeyCachingService_silence_passphrase_cached));
    builder.setContentIntent(buildLaunchIntent());

    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
    startForegroundCompat(builder.build());
  }

  private void startForegroundCompat(Notification notification) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(SERVICE_RUNNING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    } else {
      startForeground(SERVICE_RUNNING_ID, notification);
    }
  }

  private void foregroundService() {
    if (SilencePreferences.isPasswordDisabled(this)) {
      ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
      return;
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
      foregroundServiceModern();
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
      foregroundServiceICS();
    } else {
      foregroundServiceLegacy();
    }
  }

  private void broadcastNewSecret() {
    Log.w("service", "Broadcasting new secret...");

    Intent intent = new Intent(NEW_KEY_EVENT);
    intent.setPackage(getApplicationContext().getPackageName());

    sendBroadcast(intent, KEY_PERMISSION);
  }

  private PendingIntent buildLockIntent() {
    Intent intent = new Intent(this, KeyCachingService.class);
    intent.setAction(PASSPHRASE_EXPIRED_EVENT);
    return PendingIntent.getService(getApplicationContext(), 0, intent, PendingIntent.FLAG_IMMUTABLE);
  }

  private PendingIntent buildLaunchIntent() {
    Intent intent              = new Intent(this, ConversationListActivity.class);
    intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
    PendingIntent launchIntent = PendingIntent.getActivity(getApplicationContext(), 0, intent, PendingIntent.FLAG_IMMUTABLE);
    return launchIntent;
  }

  @Override
  public IBinder onBind(Intent arg0) {
    return binder;
  }

  public class KeySetBinder extends Binder {
    public KeyCachingService getService() {
      return KeyCachingService.this;
    }
  }

  public static void registerPassphraseActivityStarted(Context activity) {
    Intent intent = new Intent(activity, KeyCachingService.class);
    intent.setAction(KeyCachingService.ACTIVITY_START_EVENT);
    try {
      activity.startService(intent);
    } catch (IllegalStateException e) {
      Log.w("KeyCachingService", "Unable to start service from background", e);
    }
  }

  public static void registerPassphraseActivityStopped(Context activity) {
    Intent intent = new Intent(activity, KeyCachingService.class);
    intent.setAction(KeyCachingService.ACTIVITY_STOP_EVENT);
    try {
      activity.startService(intent);
    } catch (IllegalStateException e) {
      Log.w("KeyCachingService", "Unable to start service from background", e);
    }
  }
}
