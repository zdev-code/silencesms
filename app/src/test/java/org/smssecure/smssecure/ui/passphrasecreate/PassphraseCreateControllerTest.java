package org.smssecure.smssecure.ui.passphrasecreate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.crypto.MasterSecretStorageException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import javax.crypto.spec.SecretKeySpec;

public class PassphraseCreateControllerTest {
  @Test
  public void cancellationCancelsWorkAndSuppressesLateSuccess() {
    FakeDispatcher dispatcher = new FakeDispatcher();
    RecordingCallback callback = new RecordingCallback();
    PassphraseCreateController controller =
        new PassphraseCreateController(dispatcher, PassphraseCreateControllerTest::secret);

    controller.start(callback);
    controller.cancel();
    dispatcher.succeedLate(0);

    assertTrue(dispatcher.jobs.get(0).cancelled);
    assertEquals(0, callback.successes);
    assertEquals(0, callback.failures);
  }

  @Test
  public void closeSuppressesLateFailureAndSuccess() {
    FakeDispatcher dispatcher = new FakeDispatcher();
    RecordingCallback callback = new RecordingCallback();
    PassphraseCreateController controller =
        new PassphraseCreateController(dispatcher, PassphraseCreateControllerTest::secret);

    controller.start(callback);
    controller.close();
    dispatcher.failLate(0, new MasterSecretStorageException("storage failed", null));
    dispatcher.succeedLate(0);

    assertEquals(0, callback.successes);
    assertEquals(0, callback.failures);
  }

  @Test
  public void retryAfterStorageFailureStartsFreshOperation() {
    FakeDispatcher dispatcher = new FakeDispatcher();
    RecordingCallback callback = new RecordingCallback();
    PassphraseCreateController controller =
        new PassphraseCreateController(dispatcher, PassphraseCreateControllerTest::secret);

    controller.start(callback);
    dispatcher.failLate(0, new MasterSecretStorageException("storage failed", null));
    controller.start(callback);
    dispatcher.succeedLate(0);
    dispatcher.succeedLate(1);

    assertEquals(2, dispatcher.jobs.size());
    assertEquals(1, callback.failures);
    assertEquals(1, callback.successes);
  }

  private static MasterSecret secret() {
    return new MasterSecret(new SecretKeySpec(new byte[16], "AES"),
                            new SecretKeySpec(new byte[20], "HmacSHA1"));
  }

  private static final class RecordingCallback implements PassphraseCreateController.Callback {
    private int successes;
    private int failures;

    @Override public void onSuccess(MasterSecret masterSecret) { successes++; }
    @Override public void onFailure(Exception exception) { failures++; }
  }

  private static final class FakeDispatcher implements PassphraseCreateController.Dispatcher {
    private final List<Job> jobs = new ArrayList<>();

    @Override
    public PassphraseCreateController.Cancellable submit(
        Callable<MasterSecret> work, PassphraseCreateController.SuccessSink success,
        PassphraseCreateController.FailureSink failure)
    {
      Job job = new Job(work, success, failure);
      jobs.add(job);
      return () -> job.cancelled = true;
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
    private final PassphraseCreateController.SuccessSink success;
    private final PassphraseCreateController.FailureSink failure;
    private boolean cancelled;

    private Job(Callable<MasterSecret> work,
                PassphraseCreateController.SuccessSink success,
                PassphraseCreateController.FailureSink failure)
    {
      this.work = work;
      this.success = success;
      this.failure = failure;
    }
  }
}