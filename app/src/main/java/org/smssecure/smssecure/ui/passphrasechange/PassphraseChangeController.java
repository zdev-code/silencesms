package org.smssecure.smssecure.ui.passphrasechange;

import android.content.Context;

import androidx.annotation.NonNull;

import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.crypto.MasterSecretUtil;
import org.smssecure.smssecure.domain.security.UnlockSession;
import org.smssecure.smssecure.domain.security.WipeablePassphrase;
import org.smssecure.smssecure.util.SilencePreferences;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.concurrent.Callable;

public final class PassphraseChangeController implements AutoCloseable {
  public enum ValidationFailure { MISMATCH, EMPTY }

  public interface Callback {
    void onValidationFailure(@NonNull ValidationFailure failure);
    void onSuccess(@NonNull MasterSecret masterSecret);
    void onFailure(@NonNull Exception exception);
  }

  interface Cancellable {
    void cancel();
  }

  interface Dispatcher {
    Cancellable submit(Callable<MasterSecret> work, CallbackSink success, FailureSink failure);
  }

  interface CallbackSink {
    void accept(MasterSecret masterSecret);
  }

  interface FailureSink {
    void accept(Exception exception);
  }

  interface Operation {
    MasterSecret change(char[] originalPassphrase, char[] newPassphrase,
                        MasterSecretUtil.PassphraseChangeGuard guard) throws Exception;
  }

  private final Dispatcher dispatcher;
  private final Operation  operation;
  private Cancellable      activeTask;
  private PendingBuffers   activeBuffers;
  private long             taskGeneration;
  private boolean          closed;

  public static PassphraseChangeController create(@NonNull Context context) {
    Context applicationContext = context.getApplicationContext();
    AppTaskExecutor executor = AppTaskExecutor.getInstance();
    return new PassphraseChangeController(
        (work, success, failure) -> {
          AppTaskExecutor.TaskHandle handle = executor.submitSerial(work, success::accept,
                                                                     failure::accept);
          return handle::cancel;
        },
        (original, replacement, guard) -> {
          MasterSecret masterSecret = MasterSecretUtil.changeMasterSecretPassphrase(
              applicationContext, original, replacement, guard);
          guard.check();
          SilencePreferences.setPasswordDisabled(applicationContext, false);
          return masterSecret;
        });
  }

  PassphraseChangeController(Dispatcher dispatcher, Operation operation) {
    this.dispatcher = dispatcher;
    this.operation = operation;
  }

  public void submit(@NonNull WipeablePassphrase originalPassphrase,
                     @NonNull WipeablePassphrase newPassphrase,
                     @NonNull WipeablePassphrase repeatPassphrase,
                     @NonNull UnlockSession unlockSession,
                     @NonNull Callback callback)
  {
    char[] originalCopy = null;
    char[] replacementCopy = null;
    try {
      if (!newPassphrase.contentEquals(repeatPassphrase)) {
        callback.onValidationFailure(ValidationFailure.MISMATCH);
        return;
      }
      if (newPassphrase.isEmpty()) {
        callback.onValidationFailure(ValidationFailure.EMPTY);
        return;
      }

      originalCopy = originalPassphrase.copy();
      replacementCopy = newPassphrase.copy();
      dispatch(originalCopy, replacementCopy, unlockSession, callback);
      originalCopy = null;
      replacementCopy = null;
    } finally {
      if (originalCopy != null) Arrays.fill(originalCopy, '\0');
      if (replacementCopy != null) Arrays.fill(replacementCopy, '\0');
      originalPassphrase.close();
      newPassphrase.close();
      repeatPassphrase.close();
    }
  }

  private synchronized void dispatch(char[] originalPassphrase, char[] newPassphrase,
                                     UnlockSession unlockSession, Callback callback)
  {
    cancelCurrentLocked();
    long requestGeneration = ++taskGeneration;
    PendingBuffers buffers = new PendingBuffers(originalPassphrase, newPassphrase);
    activeBuffers = buffers;
    activeTask = dispatcher.submit(
        () -> {
          try {
            return operation.change(buffers.original, buffers.replacement,
                                    () -> requireCurrent(requestGeneration, unlockSession));
          } finally {
            buffers.close();
          }
        },
        masterSecret -> {
          if (complete(requestGeneration, unlockSession)) callback.onSuccess(masterSecret);
        },
        exception -> {
          if (complete(requestGeneration, unlockSession)) callback.onFailure(exception);
        });
  }

  private synchronized void requireCurrent(long requestGeneration, UnlockSession unlockSession)
      throws GeneralSecurityException
  {
    if (closed || requestGeneration != taskGeneration || Thread.currentThread().isInterrupted()) {
      throw new GeneralSecurityException("Passphrase change cancelled");
    }
    try {
      unlockSession.requireCurrent();
    } catch (UnlockSession.LockedException exception) {
      throw new GeneralSecurityException("Unlock generation changed", exception);
    }
  }

  private synchronized boolean complete(long requestGeneration, UnlockSession unlockSession) {
    if (closed || requestGeneration != taskGeneration || !unlockSession.isCurrent()) return false;
    activeTask = null;
    activeBuffers = null;
    return true;
  }

  public synchronized void cancel() {
    cancelCurrentLocked();
  }

  private void cancelCurrentLocked() {
    taskGeneration++;
    if (activeTask != null) activeTask.cancel();
    if (activeBuffers != null) activeBuffers.close();
    activeTask = null;
    activeBuffers = null;
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    cancelCurrentLocked();
  }

  private static final class PendingBuffers implements AutoCloseable {
    private final char[] original;
    private final char[] replacement;

    private PendingBuffers(char[] original, char[] replacement) {
      this.original = original;
      this.replacement = replacement;
    }

    @Override public void close() {
      Arrays.fill(original, '\0');
      Arrays.fill(replacement, '\0');
    }
  }
}