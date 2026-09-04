package org.smssecure.smssecure.ui.databasemigration;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;

import androidx.annotation.NonNull;

import org.smssecure.smssecure.database.SmsMigrator.ProgressDescription;
import org.smssecure.smssecure.domain.security.UnlockSession;
import org.smssecure.smssecure.service.ApplicationMigrationService;
import org.smssecure.smssecure.service.ApplicationMigrationService.ImportState;

import java.util.Objects;

public final class DatabaseMigrationController implements AutoCloseable {
  public interface Observer {
    void onState(int state, ProgressDescription progress);
    void onComplete();
  }

  public interface Environment {
    boolean bind(@NonNull ServiceConnection connection);
    void unbind(@NonNull ServiceConnection connection);
    void register(@NonNull BroadcastReceiver receiver);
    void unregister(@NonNull BroadcastReceiver receiver);
    void startMigration(@NonNull UnlockSession unlockSession);
    void skip();
    boolean isDatabaseImported();
  }

  private final Environment environment;
  private Handler handler;
  private ServiceConnection connection;
  private BroadcastReceiver receiver;
  private ApplicationMigrationService service;
  private int attachmentGeneration;
  private boolean bindRequested;
  private boolean receiverRegistered;
  private boolean completionClaimed;
  private boolean closed;

  public DatabaseMigrationController(@NonNull Environment environment) {
    this.environment = Objects.requireNonNull(environment);
  }

  public void attach(@NonNull Observer observer) {
    Objects.requireNonNull(observer);
    if (closed) return;
    detach();
    int generation = attachmentGeneration;
    handler = new Handler(Looper.getMainLooper()) {
      @Override
      public void handleMessage(@NonNull Message message) {
        if (!isCurrent(generation)) return;
        if (message.what == ImportState.STATE_MIGRATING_COMPLETE) observer.onComplete();
        else observer.onState(message.what, (ProgressDescription) message.obj);
      }
    };
    connection = new ServiceConnection() {
      @Override
      public void onServiceConnected(ComponentName name, IBinder binder) {
        if (!isCurrent(generation)) return;
        service = ((ApplicationMigrationService.ApplicationMigrationBinder) binder).getService();
        service.setImportStateHandler(handler);
        ImportState state = service.getState();
        handler.obtainMessage(state.state, state.progress).sendToTarget();
      }

      @Override
      public void onServiceDisconnected(ComponentName name) {
        if (!isCurrent(generation)) return;
        if (service != null) service.setImportStateHandler(null);
        service = null;
      }
    };
    receiver = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
        if (isOrderedBroadcast()) abortBroadcast();
        if (isCurrent(generation)) observer.onComplete();
      }
    };

    environment.register(receiver);
    receiverRegistered = true;
    bindRequested = environment.bind(connection);
    if (environment.isDatabaseImported()) handler.post(observer::onComplete);
  }

  public void startMigration(@NonNull UnlockSession unlockSession) {
    if (!closed) environment.startMigration(Objects.requireNonNull(unlockSession));
  }

  public void skip() {
    if (!closed) environment.skip();
  }

  public boolean claimCompletion() {
    if (closed || completionClaimed) return false;
    completionClaimed = true;
    return true;
  }

  public void detach() {
    attachmentGeneration++;
    if (service != null) service.setImportStateHandler(null);
    service = null;
    if (handler != null) handler.removeCallbacksAndMessages(null);
    handler = null;
    if (bindRequested && connection != null) environment.unbind(connection);
    bindRequested = false;
    connection = null;
    if (receiverRegistered && receiver != null) environment.unregister(receiver);
    receiverRegistered = false;
    receiver = null;
  }

  private boolean isCurrent(int generation) {
    return !closed && generation == attachmentGeneration;
  }

  @Override
  public void close() {
    if (closed) return;
    detach();
    closed = true;
  }
}