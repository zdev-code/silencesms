package org.smssecure.smssecure.ui.passphraseprompt;

import android.content.Context;

import androidx.annotation.NonNull;

import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.crypto.MasterSecretUtil;
import org.smssecure.smssecure.domain.security.WipeablePassphrase;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor;

import java.util.Arrays;
import java.util.concurrent.Callable;

public final class PassphrasePromptController implements AutoCloseable {
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
    MasterSecret unlock(char[] passphrase) throws Exception;
  }

  private final Dispatcher dispatcher;
  private final Operation operation;
  private Cancellable activeTask;
  private PendingBuffer activeBuffer;
  private long operationGeneration;
  private boolean closed;

  public static PassphrasePromptController create(@NonNull Context context) {
    Context applicationContext = context.getApplicationContext();
    AppTaskExecutor executor = AppTaskExecutor.getInstance();
    return new PassphrasePromptController(
        (work, success, failure) -> {
          AppTaskExecutor.TaskHandle handle =
              executor.submitSerial(work, success::accept, failure::accept);
          return handle::cancel;
        },
        passphrase -> MasterSecretUtil.getMasterSecret(applicationContext, passphrase));
  }

  PassphrasePromptController(Dispatcher dispatcher, Operation operation) {
    this.dispatcher = dispatcher;
    this.operation = operation;
  }

  public void submit(@NonNull WipeablePassphrase passphrase, @NonNull Callback callback) {
    char[] workerCopy = null;
    try {
      workerCopy = passphrase.copy();
      dispatch(workerCopy, callback);
      workerCopy = null;
    } finally {
      if (workerCopy != null) Arrays.fill(workerCopy, '\0');
      passphrase.close();
    }
  }

  private synchronized void dispatch(char[] passphrase, Callback callback) {
    if (closed) {
      Arrays.fill(passphrase, '\0');
      return;
    }
    cancelCurrentLocked();
    long generation = ++operationGeneration;
    PendingBuffer buffer = new PendingBuffer(passphrase);
    activeBuffer = buffer;
    activeTask = dispatcher.submit(
        () -> {
          try {
            return operation.unlock(buffer.value);
          } finally {
            buffer.close();
          }
        },
        masterSecret -> {
          if (complete(generation)) callback.onSuccess(masterSecret);
        },
        exception -> {
          if (complete(generation)) callback.onFailure(exception);
        });
  }

  private synchronized boolean complete(long generation) {
    if (closed || generation != operationGeneration) return false;
    activeTask = null;
    if (activeBuffer != null) activeBuffer.close();
    activeBuffer = null;
    return true;
  }

  public synchronized void cancel() {
    cancelCurrentLocked();
  }

  private void cancelCurrentLocked() {
    operationGeneration++;
    if (activeTask != null) activeTask.cancel();
    if (activeBuffer != null) activeBuffer.close();
    activeTask = null;
    activeBuffer = null;
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    cancelCurrentLocked();
  }

  private static final class PendingBuffer implements AutoCloseable {
    private final char[] value;

    private PendingBuffer(char[] value) {
      this.value = value;
    }

    @Override public void close() {
      Arrays.fill(value, '\0');
    }
  }
}