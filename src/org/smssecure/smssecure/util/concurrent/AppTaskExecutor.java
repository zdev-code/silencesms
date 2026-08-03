package org.smssecure.smssecure.util.concurrent;

import android.os.Handler;
import android.os.Looper;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class AppTaskExecutor {

  public interface SuccessCallback<T> {
    void onSuccess(T result);
  }

  public interface FailureCallback {
    void onFailure(Exception exception);
  }

  public static final class TaskHandle {
    private final AtomicBoolean           cancelled = new AtomicBoolean(false);
    private final AtomicReference<Future<?>> future = new AtomicReference<>();

    public boolean cancel() {
      cancelled.set(true);

      Future<?> current = future.get();
      return current == null || current.cancel(true);
    }

    public boolean isCancelled() {
      return cancelled.get();
    }

    public boolean isDone() {
      Future<?> current = future.get();
      return current != null && current.isDone();
    }

    private void attach(Future<?> submitted) {
      future.set(submitted);
      if (cancelled.get()) submitted.cancel(true);
    }
  }

  private static final class Holder {
    private static final AppTaskExecutor INSTANCE = createDefault();
  }

  private final ExecutorService serialExecutor;
  private final ExecutorService parallelExecutor;
  private final Executor        callbackExecutor;

  AppTaskExecutor(ExecutorService serialExecutor,
                  ExecutorService parallelExecutor,
                  Executor callbackExecutor)
  {
    this.serialExecutor   = Objects.requireNonNull(serialExecutor);
    this.parallelExecutor = Objects.requireNonNull(parallelExecutor);
    this.callbackExecutor = Objects.requireNonNull(callbackExecutor);
  }

  public static AppTaskExecutor getInstance() {
    return Holder.INSTANCE;
  }

  public <T> TaskHandle submitSerial(Callable<T> work,
                                     SuccessCallback<T> successCallback,
                                     FailureCallback failureCallback)
  {
    return submit(serialExecutor, work, successCallback, failureCallback);
  }

  public <T> TaskHandle submitParallel(Callable<T> work,
                                       SuccessCallback<T> successCallback,
                                       FailureCallback failureCallback)
  {
    return submit(parallelExecutor, work, successCallback, failureCallback);
  }

  private <T> TaskHandle submit(ExecutorService executor,
                                Callable<T> work,
                                SuccessCallback<T> successCallback,
                                FailureCallback failureCallback)
  {
    Objects.requireNonNull(work);
    Objects.requireNonNull(successCallback);
    Objects.requireNonNull(failureCallback);

    TaskHandle handle = new TaskHandle();

    try {
      handle.attach(executor.submit(() -> {
        try {
          T result = work.call();
          callbackExecutor.execute(() -> {
            if (!handle.isCancelled()) successCallback.onSuccess(result);
          });
        } catch (Exception exception) {
          callbackExecutor.execute(() -> {
            if (!handle.isCancelled()) failureCallback.onFailure(exception);
          });
        }
      }));
    } catch (RejectedExecutionException exception) {
      callbackExecutor.execute(() -> {
        if (!handle.isCancelled()) failureCallback.onFailure(exception);
      });
    }

    return handle;
  }

  private static AppTaskExecutor createDefault() {
    int parallelism = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors()));
    Handler mainHandler = new Handler(Looper.getMainLooper());

    return new AppTaskExecutor(Executors.newSingleThreadExecutor(new NamedThreadFactory("silence-serial")),
                               Executors.newFixedThreadPool(parallelism, new NamedThreadFactory("silence-parallel")),
                               command -> mainHandler.post(command));
  }

  private static final class NamedThreadFactory implements ThreadFactory {
    private final AtomicInteger sequence = new AtomicInteger(1);
    private final String        prefix;

    private NamedThreadFactory(String prefix) {
      this.prefix = prefix;
    }

    @Override
    public Thread newThread(Runnable runnable) {
      return new Thread(runnable, prefix + "-" + sequence.getAndIncrement());
    }
  }
}