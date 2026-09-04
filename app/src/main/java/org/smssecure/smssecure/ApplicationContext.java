/*
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

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.util.Log;

import androidx.core.app.NotificationManagerCompat;
import androidx.hilt.work.HiltWorkerFactory;
import androidx.work.Configuration;

import org.smssecure.smssecure.backup.SecureBackupArchive;
import org.smssecure.smssecure.crypto.MasterSecretUtil;
import org.smssecure.smssecure.crypto.PRNGFixes;
import org.smssecure.smssecure.jobs.persistence.EncryptingJobSerializer;
import org.smssecure.smssecure.jobs.requirements.MasterSecretRequirementProvider;
import org.smssecure.smssecure.jobs.requirements.MediaNetworkRequirementProvider;
import org.smssecure.smssecure.jobs.requirements.ServiceRequirementProvider;
import org.smssecure.smssecure.notifications.NotificationChannels;
import org.smssecure.smssecure.providers.PersistentBlobProvider;
import org.smssecure.smssecure.util.SilencePreferences;
import org.smssecure.smssecure.util.dualsim.SimChangedReceiver;
import org.whispersystems.jobqueue.JobManager;
import org.whispersystems.jobqueue.dependencies.DependencyInjector;
import org.whispersystems.jobqueue.requirements.NetworkRequirementProvider;
import org.whispersystems.libsignal.logging.SignalProtocolLoggerProvider;
import org.whispersystems.libsignal.util.AndroidSignalProtocolLogger;

import java.io.File;
import java.io.IOException;
import java.security.Security;

import dagger.hilt.android.HiltAndroidApp;

import javax.inject.Inject;


/**
 * Will be called once when the Silence process is created.
 *
 * We're using this as an insertion point to patch up the Android PRNG disaster
 * and to initialize the job manager.
 *
 * @author Moxie Marlinspike
 */
@HiltAndroidApp
public class ApplicationContext extends Application implements DependencyInjector, Configuration.Provider {
  private static final String TAG = ApplicationContext.class.getSimpleName();

  private JobManager jobManager;
  @Inject HiltWorkerFactory workerFactory;

  private MediaNetworkRequirementProvider mediaNetworkRequirementProvider = new MediaNetworkRequirementProvider();

  public static ApplicationContext getInstance(Context context) {
    return (ApplicationContext)context.getApplicationContext();
  }

  @Override
  public void onCreate() {
    super.onCreate();
    PersistentBlobProvider.getInstance(this).deleteAll();
    recoverInterruptedRestore();
    initializeRandomNumberFix();
    initializeLogging();
    initializeJobManager();
    checkSimState();
    NotificationChannels.create(this);
  }

  @Override
  public void injectDependencies(Object object) {
    // Dependency injection is no longer used; method retained for API compatibility.
  }

  public JobManager getJobManager() {
    return jobManager;
  }

  @Override
  public Configuration getWorkManagerConfiguration() {
    return new Configuration.Builder().setWorkerFactory(workerFactory).build();
  }

  private void recoverInterruptedRestore() {
    File filesDirectory = getFilesDir();
    File root = filesDirectory == null ? null : filesDirectory.getParentFile();
    if (root == null) return;
    recoverInterruptedRestore(this, root);
  }

  static void recoverInterruptedRestore(Context context, File root) {
    try {
      SecureBackupArchive.recoverInterruptedRestore(root);
      MasterSecretUtil.reconcileDeviceProtection(context);
    } catch (IOException | java.security.GeneralSecurityException error) {
      throw new IllegalStateException("Unable to recover interrupted secure backup restore", error);
    }
  }

  private void initializeRandomNumberFix() {
    PRNGFixes.apply();
  }
  private void initializeLogging() {
    SignalProtocolLoggerProvider.setProvider(new AndroidSignalProtocolLogger());
  }

  private void initializeJobManager() {
    this.jobManager = JobManager.newBuilder(this)
                                .withName("SilenceJobs")
                                .withDependencyInjector(this)
                                .withJobSerializer(new EncryptingJobSerializer())
                                .withRequirementProviders(new MasterSecretRequirementProvider(this),
                                                          new ServiceRequirementProvider(this),
                                                          new NetworkRequirementProvider(this),
                                                          mediaNetworkRequirementProvider)
                                .withConsumerThreads(5)
                                .build();
  }

  public void notifyMediaControlEvent() {
    mediaNetworkRequirementProvider.notifyMediaControlEvent();
  }

  private void checkSimState() {
    SimChangedReceiver.checkSimState(this);
  }

}
