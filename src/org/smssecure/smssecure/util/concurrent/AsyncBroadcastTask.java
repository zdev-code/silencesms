package org.smssecure.smssecure.util.concurrent;

import android.content.BroadcastReceiver;
import android.util.Log;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class AsyncBroadcastTask {

  private AsyncBroadcastTask() {}

  public static AppTaskExecutor.TaskHandle submit(BroadcastReceiver.PendingResult pendingResult,
                                                  String tag,
                                                  Callable<Void> work)
  {
    return submit(AppTaskExecutor.getInstance(), pendingResult::finish, tag, work,
                  exception -> Log.w(tag, "Asynchronous broadcast work failed", exception));
  }

  static AppTaskExecutor.TaskHandle submit(AppTaskExecutor executor,
                                           Runnable finish,
                                           String tag,
                                           Callable<Void> work,
                                           Consumer<Exception> failureLogger)
  {
    AtomicBoolean finished = new AtomicBoolean(false);
    Runnable finishOnce = () -> {
      if (finished.compareAndSet(false, true)) finish.run();
    };

    return executor.submitSerial(() -> {
      try {
        return work.call();
      } finally {
        finishOnce.run();
      }
    }, ignored -> {}, exception -> {
      failureLogger.accept(exception);
      finishOnce.run();
    });
  }
}