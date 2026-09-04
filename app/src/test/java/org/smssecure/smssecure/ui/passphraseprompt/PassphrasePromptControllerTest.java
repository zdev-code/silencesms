package org.smssecure.smssecure.ui.passphraseprompt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.smssecure.smssecure.crypto.InvalidPassphraseException;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.domain.security.WipeablePassphrase;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import javax.crypto.spec.SecretKeySpec;

public class PassphrasePromptControllerTest {
  @Test
  public void submitClosesCallerBufferAndWipesWorkerBufferAfterSuccess() throws Exception {
    FakeDispatcher dispatcher = new FakeDispatcher();
    RecordingOperation operation = new RecordingOperation();
    RecordingCallback callback = new RecordingCallback();
    PassphrasePromptController controller =
        new PassphrasePromptController(dispatcher, operation);
    WipeablePassphrase passphrase = WipeablePassphrase.copyOf("secret");

    controller.submit(passphrase, callback);
    dispatcher.run(0);

    assertThrows(IllegalStateException.class, passphrase::copy);
    assertEquals(1, callback.successes);
    assertTrue(allZero(operation.received));
  }

  @Test
  public void invalidPassphraseWipesWorkerBufferAndReportsFailure() throws Exception {
    FakeDispatcher dispatcher = new FakeDispatcher();
    RecordingOperation operation = new RecordingOperation();
    operation.failure = new InvalidPassphraseException("wrong");
    RecordingCallback callback = new RecordingCallback();
    PassphrasePromptController controller =
        new PassphrasePromptController(dispatcher, operation);

    controller.submit(WipeablePassphrase.copyOf("wrong"), callback);
    dispatcher.run(0);

    assertEquals(0, callback.successes);
    assertEquals(1, callback.failures);
    assertTrue(callback.lastFailure instanceof InvalidPassphraseException);
    assertTrue(allZero(operation.received));
  }

  @Test
  public void cancelWipesQueuedBufferAndSuppressesLateSuccessAndFailure() throws Exception {
    FakeDispatcher dispatcher = new FakeDispatcher();
    RecordingOperation operation = new RecordingOperation();
    RecordingCallback callback = new RecordingCallback();
    PassphrasePromptController controller =
        new PassphrasePromptController(dispatcher, operation);

    controller.submit(WipeablePassphrase.copyOf("secret"), callback);
    controller.cancel();
    dispatcher.run(0);
    dispatcher.succeedLate(0);
    dispatcher.failLate(0, new InvalidPassphraseException("late"));

    assertTrue(dispatcher.jobs.get(0).cancelled);
    assertTrue(allZero(operation.received));
    assertEquals(0, callback.successes);
    assertEquals(0, callback.failures);
  }

  @Test
  public void replacementAndCloseSuppressStaleCallbacks() {
    FakeDispatcher dispatcher = new FakeDispatcher();
    RecordingCallback callback = new RecordingCallback();
    PassphrasePromptController controller =
        new PassphrasePromptController(dispatcher, passphrase -> secret());

    controller.submit(WipeablePassphrase.copyOf("first"), callback);
    controller.submit(WipeablePassphrase.copyOf("second"), callback);
    dispatcher.succeedLate(0);
    dispatcher.failLate(0, new InvalidPassphraseException("stale"));
    dispatcher.succeedLate(1);
    controller.close();
    dispatcher.failLate(1, new InvalidPassphraseException("closed"));

    assertEquals(1, callback.successes);
    assertEquals(0, callback.failures);
  }

  private static boolean allZero(char[] value) {
    for (char character : value) if (character != '\0') return false;
    return true;
  }

  private static MasterSecret secret() {
    return new MasterSecret(new SecretKeySpec(new byte[16], "AES"),
                            new SecretKeySpec(new byte[20], "HmacSHA1"));
  }

  private static final class RecordingOperation implements PassphrasePromptController.Operation {
    private char[] received;
    private Exception failure;

    @Override public MasterSecret unlock(char[] passphrase) throws Exception {
      received = passphrase;
      if (failure != null) throw failure;
      return secret();
    }
  }

  private static final class RecordingCallback implements PassphrasePromptController.Callback {
    private int successes;
    private int failures;
    private Exception lastFailure;

    @Override public void onSuccess(MasterSecret masterSecret) { successes++; }
    @Override public void onFailure(Exception exception) {
      failures++;
      lastFailure = exception;
    }
  }

  private static final class FakeDispatcher implements PassphrasePromptController.Dispatcher {
    private final List<Job> jobs = new ArrayList<>();

    @Override public PassphrasePromptController.Cancellable submit(
        Callable<MasterSecret> work, PassphrasePromptController.SuccessSink success,
        PassphrasePromptController.FailureSink failure) {
      Job job = new Job(work, success, failure);
      jobs.add(job);
      return () -> job.cancelled = true;
    }

    private void run(int index) throws Exception {
      Job job = jobs.get(index);
      try {
        job.success.accept(job.work.call());
      } catch (Exception exception) {
        job.failure.accept(exception);
      }
    }

    private void succeedLate(int index) {
      jobs.get(index).success.accept(secret());
    }

    private void failLate(int index, Exception exception) {
      jobs.get(index).failure.accept(exception);
    }
  }

  private static final class Job {
    private final Callable<MasterSecret> work;
    private final PassphrasePromptController.SuccessSink success;
    private final PassphrasePromptController.FailureSink failure;
    private boolean cancelled;

    private Job(Callable<MasterSecret> work,
                PassphrasePromptController.SuccessSink success,
                PassphrasePromptController.FailureSink failure) {
      this.work = work;
      this.success = success;
      this.failure = failure;
    }
  }
}