package org.smssecure.smssecure.util.concurrent;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AppTaskExecutorTest {

  private ExecutorService serialExecutor;
  private ExecutorService parallelExecutor;
  private ExecutorService callbackExecutor;
  private AppTaskExecutor  taskExecutor;

  @Before
  public void setUp() {
    serialExecutor   = Executors.newSingleThreadExecutor();
    parallelExecutor = Executors.newFixedThreadPool(2);
    callbackExecutor = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "test-callback"));
    taskExecutor     = new AppTaskExecutor(serialExecutor, parallelExecutor, callbackExecutor);
  }

  @After
  public void tearDown() {
    serialExecutor.shutdownNow();
    parallelExecutor.shutdownNow();
    callbackExecutor.shutdownNow();
  }

  @Test
  public void serialTasksPreserveSubmissionOrder() throws Exception {
    CountDownLatch firstStarted  = new CountDownLatch(1);
    CountDownLatch releaseFirst  = new CountDownLatch(1);
    CountDownLatch secondStarted = new CountDownLatch(1);
    CountDownLatch completed     = new CountDownLatch(2);
    List<Integer> results        = Collections.synchronizedList(new ArrayList<>());

    taskExecutor.submitSerial(() -> {
      firstStarted.countDown();
      releaseFirst.await();
      return 1;
    }, result -> {
      results.add(result);
      completed.countDown();
    }, exception -> fail(exception.getMessage()));

    assertTrue(firstStarted.await(1, TimeUnit.SECONDS));

    taskExecutor.submitSerial(() -> {
      secondStarted.countDown();
      return 2;
    }, result -> {
      results.add(result);
      completed.countDown();
    }, exception -> fail(exception.getMessage()));

    assertFalse(secondStarted.await(100, TimeUnit.MILLISECONDS));
    releaseFirst.countDown();

    assertTrue(completed.await(1, TimeUnit.SECONDS));
    assertEquals(List.of(1, 2), results);
  }

  @Test
  public void parallelTasksCanOverlap() throws Exception {
    CountDownLatch bothStarted = new CountDownLatch(2);
    CountDownLatch release     = new CountDownLatch(1);
    CountDownLatch completed   = new CountDownLatch(2);

    for (int i = 0; i < 2; i++) {
      taskExecutor.submitParallel(() -> {
        bothStarted.countDown();
        release.await();
        return null;
      }, result -> completed.countDown(), exception -> fail(exception.getMessage()));
    }

    assertTrue(bothStarted.await(1, TimeUnit.SECONDS));
    release.countDown();
    assertTrue(completed.await(1, TimeUnit.SECONDS));
  }

  @Test
  public void successRunsOnCallbackExecutor() throws Exception {
    CountDownLatch completed       = new CountDownLatch(1);
    AtomicReference<String> result = new AtomicReference<>();
    AtomicReference<String> thread = new AtomicReference<>();

    taskExecutor.submitSerial(() -> "result", value -> {
      result.set(value);
      thread.set(Thread.currentThread().getName());
      completed.countDown();
    }, exception -> fail(exception.getMessage()));

    assertTrue(completed.await(1, TimeUnit.SECONDS));
    assertEquals("result", result.get());
    assertEquals("test-callback", thread.get());
  }

  @Test
  public void exceptionRunsFailureCallback() throws Exception {
    CountDownLatch completed                = new CountDownLatch(1);
    IllegalStateException expected          = new IllegalStateException("failure");
    AtomicReference<Exception> actual       = new AtomicReference<>();
    AtomicReference<String> callbackThread  = new AtomicReference<>();

    taskExecutor.submitSerial(() -> {
      throw expected;
    }, result -> fail("Success callback should not run"), exception -> {
      actual.set(exception);
      callbackThread.set(Thread.currentThread().getName());
      completed.countDown();
    });

    assertTrue(completed.await(1, TimeUnit.SECONDS));
    assertSame(expected, actual.get());
    assertEquals("test-callback", callbackThread.get());
  }

  @Test
  public void cancellationSuppressesLateCallbacks() throws Exception {
    CountDownLatch started  = new CountDownLatch(1);
    CountDownLatch release  = new CountDownLatch(1);
    CountDownLatch callback = new CountDownLatch(1);

    AppTaskExecutor.TaskHandle handle = taskExecutor.submitSerial(() -> {
      started.countDown();
      boolean interrupted = false;
      while (true) {
        try {
          release.await();
          break;
        } catch (InterruptedException exception) {
          interrupted = true;
        }
      }
      if (interrupted) Thread.currentThread().interrupt();
      return "late";
    }, result -> callback.countDown(), exception -> callback.countDown());

    assertTrue(started.await(1, TimeUnit.SECONDS));
    assertTrue(handle.cancel());
    release.countDown();

    assertTrue(handle.isCancelled());
    assertFalse(callback.await(200, TimeUnit.MILLISECONDS));
  }

  @Test
  public void cancellingQueuedTaskPreventsExecution() throws Exception {
    CountDownLatch firstStarted = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    AtomicBoolean secondRan     = new AtomicBoolean(false);

    taskExecutor.submitSerial(() -> {
      firstStarted.countDown();
      releaseFirst.await();
      return null;
    }, result -> {}, exception -> fail(exception.getMessage()));

    assertTrue(firstStarted.await(1, TimeUnit.SECONDS));

    AppTaskExecutor.TaskHandle second = taskExecutor.submitSerial(() -> {
      secondRan.set(true);
      return null;
    }, result -> {}, exception -> fail(exception.getMessage()));

    assertTrue(second.cancel());
    releaseFirst.countDown();
    serialExecutor.shutdown();
    assertTrue(serialExecutor.awaitTermination(1, TimeUnit.SECONDS));
    assertFalse(secondRan.get());
  }

  @Test
  public void broadcastTaskFinishesOnceAfterSuccess() throws Exception {
    CountDownLatch completed = new CountDownLatch(1);
    AtomicInteger finishes   = new AtomicInteger();

    AsyncBroadcastTask.submit(taskExecutor, () -> {
      finishes.incrementAndGet();
      completed.countDown();
    }, "test", () -> null, exception -> fail(exception.getMessage()));

    assertTrue(completed.await(1, TimeUnit.SECONDS));
    assertEquals(1, finishes.get());
  }

  @Test
  public void broadcastTaskFinishesOnceAfterFailure() throws Exception {
    CountDownLatch completed          = new CountDownLatch(1);
    CountDownLatch failureLogged      = new CountDownLatch(1);
    AtomicInteger finishes            = new AtomicInteger();
    AtomicReference<Exception> logged = new AtomicReference<>();
    IllegalStateException expected    = new IllegalStateException("failure");

    AsyncBroadcastTask.submit(taskExecutor, () -> {
      finishes.incrementAndGet();
      completed.countDown();
    }, "test", () -> {
      throw expected;
    }, exception -> {
      logged.set(exception);
      failureLogged.countDown();
    });

    assertTrue(completed.await(1, TimeUnit.SECONDS));
    assertTrue(failureLogged.await(1, TimeUnit.SECONDS));
    assertEquals(1, finishes.get());
    assertSame(expected, logged.get());
  }
}