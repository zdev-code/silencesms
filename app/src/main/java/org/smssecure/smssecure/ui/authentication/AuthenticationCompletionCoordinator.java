package org.smssecure.smssecure.ui.authentication;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;

import androidx.annotation.NonNull;

import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.service.KeyCachingService;

import java.util.function.BooleanSupplier;

public final class AuthenticationCompletionCoordinator implements AutoCloseable {
  public interface Callback {
    void onEstablished();
    void onFailure(@NonNull Exception exception);
  }

  interface Cancellable {
    void cancel();
  }

  interface Connector {
    Cancellable connect(MasterSecret masterSecret, BooleanSupplier current,
                        Runnable established, FailureSink failure);
  }

  interface FailureSink {
    void accept(Exception exception);
  }

  private final Connector connector;
  private Cancellable activeConnection;
  private long connectionGeneration;
  private boolean closed;

  public static AuthenticationCompletionCoordinator create(@NonNull Activity activity) {
    return new AuthenticationCompletionCoordinator(KeyCacheConnector.create(activity));
  }

  AuthenticationCompletionCoordinator(Connector connector) {
    this.connector = connector;
  }

  public synchronized void establish(@NonNull MasterSecret masterSecret,
                                     @NonNull Callback callback) {
    establish(masterSecret, () -> true, callback);
  }

  public synchronized void establish(@NonNull MasterSecret masterSecret,
                                     @NonNull BooleanSupplier ownerCurrent,
                                     @NonNull Callback callback) {
    if (closed) return;

    cancelCurrentLocked();
    long generation = ++connectionGeneration;
    activeConnection = connector.connect(
        masterSecret,
        () -> isCurrent(generation) && ownerCurrent.getAsBoolean(),
        () -> {
          if (complete(generation, ownerCurrent)) callback.onEstablished();
        },
        exception -> {
          if (complete(generation, ownerCurrent)) callback.onFailure(exception);
        });
  }

  private synchronized boolean isCurrent(long generation) {
    return !closed && generation == connectionGeneration;
  }

  private synchronized boolean complete(long generation, BooleanSupplier ownerCurrent) {
    if (!isCurrent(generation) || !ownerCurrent.getAsBoolean()) return false;
    activeConnection = null;
    return true;
  }

  private void cancelCurrentLocked() {
    connectionGeneration++;
    if (activeConnection != null) activeConnection.cancel();
    activeConnection = null;
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    cancelCurrentLocked();
  }

  private static final class KeyCacheConnector {
    private KeyCacheConnector() {}

    private static Connector create(Activity activity) {
      return (masterSecret, current, established, failure) -> {
        Connection connection =
            new Connection(activity, masterSecret, current, established, failure);
        connection.connect();
        return connection;
      };
    }
  }

  private static final class Connection implements ServiceConnection, Cancellable {
    private final Activity activity;
    private final BooleanSupplier current;
    private final Runnable established;
    private final FailureSink failure;
    private MasterSecret masterSecret;
    private boolean bound;
    private boolean cancelled;

    private Connection(Activity activity, MasterSecret masterSecret,
                       BooleanSupplier current, Runnable established, FailureSink failure) {
      this.activity = activity;
      this.masterSecret = masterSecret;
      this.current = current;
      this.established = established;
      this.failure = failure;
    }

    private void connect() {
      try {
        KeyCachingService.markAuthenticationActivationPending(masterSecret);
        KeyCachingService.primeMasterSecret(masterSecret);
        Intent serviceIntent = new Intent(activity, KeyCachingService.class);
        activity.startService(serviceIntent);
        bound = activity.bindService(serviceIntent, this, Context.BIND_AUTO_CREATE);
        if (!bound) fail(new IllegalStateException("Unable to bind key cache"));
      } catch (RuntimeException exception) {
        fail(exception);
      }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
      MasterSecret pending = masterSecret;
      if (!isUsable(pending) || !(binder instanceof KeyCachingService.KeySetBinder)) {
        cancel();
        return;
      }

      ((KeyCachingService.KeySetBinder) binder).getService().setMasterSecret(pending);
      masterSecret = null;
      unbind();
      if (current.getAsBoolean()) established.run();
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
      bound = false;
    }

    @Override
    public void onBindingDied(ComponentName name) {
      fail(new IllegalStateException("Key cache binding died"));
    }

    @Override
    public void onNullBinding(ComponentName name) {
      fail(new IllegalStateException("Key cache returned a null binding"));
    }

    private boolean isUsable(MasterSecret pending) {
      return !cancelled && pending != null && current.getAsBoolean() &&
          !activity.isFinishing() && !activity.isDestroyed() &&
          KeyCachingService.isAuthenticationActivationPending(pending);
    }

    private void fail(Exception exception) {
      boolean notify = !cancelled && current.getAsBoolean();
      cancel();
      if (notify) failure.accept(exception);
    }

    private void unbind() {
      if (!bound) return;
      bound = false;
      activity.unbindService(this);
    }

    @Override
    public void cancel() {
      if (cancelled) return;
      cancelled = true;
      KeyCachingService.discardPrimedMasterSecret(masterSecret);
      masterSecret = null;
      unbind();
    }
  }
}