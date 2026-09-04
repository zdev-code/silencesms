package org.smssecure.smssecure.ui.passphrasecreate;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;

import org.smssecure.smssecure.crypto.IdentityKeyUtil;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.crypto.MasterSecretUtil;
import org.smssecure.smssecure.util.SilencePreferences;
import org.smssecure.smssecure.util.VersionTracker;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor;
import org.smssecure.smssecure.util.dualsim.DualSimUtil;
import org.smssecure.smssecure.util.dualsim.SubscriptionInfoCompat;
import org.smssecure.smssecure.util.dualsim.SubscriptionManagerCompat;

import java.util.List;
import java.util.concurrent.Callable;

public final class PassphraseCreateController implements AutoCloseable {
  public interface Callback {
    void onSuccess(@NonNull MasterSecret masterSecret);
    void onFailure(@NonNull Exception exception);
  }

  interface Cancellable {
    void cancel();
  }

  interface Dispatcher {
    Cancellable submit(Callable<MasterSecret> work, SuccessSink success, FailureSink failure);
  }

  interface SuccessSink {
    void accept(MasterSecret masterSecret);
  }

  interface FailureSink {
    void accept(Exception exception);
  }

  interface Operation {
    MasterSecret generate() throws Exception;
  }

  private final Dispatcher dispatcher;
  private final Operation operation;
  private Cancellable activeTask;
  private long operationGeneration;
  private boolean closed;

  public static PassphraseCreateController create(@NonNull Context context) {
    Context applicationContext = context.getApplicationContext();
    AppTaskExecutor executor = AppTaskExecutor.getInstance();
    return new PassphraseCreateController(
        (work, success, failure) -> {
          AppTaskExecutor.TaskHandle handle =
              executor.submitSerial(work, success::accept, failure::accept);
          return handle::cancel;
        },
        () -> generateMasterSecret(applicationContext));
  }

  PassphraseCreateController(Dispatcher dispatcher, Operation operation) {
    this.dispatcher = dispatcher;
    this.operation = operation;
  }

  public synchronized void start(@NonNull Callback callback) {
    if (closed) return;

    cancelCurrentLocked();
    long generation = ++operationGeneration;
    activeTask = dispatcher.submit(
        operation::generate,
        masterSecret -> {
          if (complete(generation)) callback.onSuccess(masterSecret);
        },
        exception -> {
          if (complete(generation)) callback.onFailure(exception);
        });
  }

  public synchronized void cancel() {
    cancelCurrentLocked();
  }

  private synchronized boolean complete(long generation) {
    if (closed || generation != operationGeneration) return false;
    activeTask = null;
    return true;
  }

  private void cancelCurrentLocked() {
    operationGeneration++;
    if (activeTask != null) activeTask.cancel();
    activeTask = null;
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    cancelCurrentLocked();
  }

  private static MasterSecret generateMasterSecret(Context context) throws Exception {
    MasterSecret masterSecret = MasterSecretUtil.generateMasterSecret(
        context, MasterSecretUtil.UNENCRYPTED_PASSPHRASE);

    MasterSecretUtil.generateAsymmetricMasterSecret(context, masterSecret);

    SubscriptionManagerCompat subscriptionManager = SubscriptionManagerCompat.from(context);
    if (Build.VERSION.SDK_INT >= 22) {
      List<SubscriptionInfoCompat> activeSubscriptions =
          subscriptionManager.getActiveSubscriptionInfoList();
      DualSimUtil.generateKeysIfDoNotExist(
          context, masterSecret, activeSubscriptions, false);
    } else {
      IdentityKeyUtil.generateIdentityKeys(context, masterSecret, -1, false);
      subscriptionManager.updateActiveSubscriptionInfoList();
    }

    VersionTracker.updateLastSeenVersion(context);
    SilencePreferences.setPasswordDisabled(context, true);
    return masterSecret;
  }
}