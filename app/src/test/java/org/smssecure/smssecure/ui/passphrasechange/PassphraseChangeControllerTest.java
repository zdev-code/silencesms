package org.smssecure.smssecure.ui.passphrasechange;

import org.junit.Test;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.domain.security.UnlockSession;
import org.smssecure.smssecure.domain.security.WipeablePassphrase;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PassphraseChangeControllerTest {
  @Test
  public void mismatchWipesEveryInputAndNeverDispatches() {
    FakeDispatcher dispatcher = new FakeDispatcher();
    PassphraseChangeController controller = new PassphraseChangeController(
        dispatcher, (original, replacement, guard) -> secret());
    char[] original = "old".toCharArray();
    char[] replacement = "new".toCharArray();
    char[] repeated = "different".toCharArray();
    RecordingCallback callback = new RecordingCallback();

    controller.submit(owned(original), owned(replacement), owned(repeated), currentSession(), callback);

    assertEquals(PassphraseChangeController.ValidationFailure.MISMATCH, callback.validationFailure);
    assertTrue(dispatcher.jobs.isEmpty());
    assertTrue(isAllZero(original));
    assertTrue(isAllZero(replacement));
    assertTrue(isAllZero(repeated));
  }

  @Test
  public void emptyReplacementWipesEveryInputAndNeverDispatches() {
    FakeDispatcher dispatcher = new FakeDispatcher();
    PassphraseChangeController controller = new PassphraseChangeController(
        dispatcher, (original, replacement, guard) -> secret());
    char[] original = "old".toCharArray();
    char[] replacement = new char[0];
    char[] repeated = new char[0];
    RecordingCallback callback = new RecordingCallback();

    controller.submit(owned(original), owned(replacement), owned(repeated), currentSession(), callback);

    assertEquals(PassphraseChangeController.ValidationFailure.EMPTY, callback.validationFailure);
    assertTrue(dispatcher.jobs.isEmpty());
    assertTrue(isAllZero(original));
  }

  @Test
  public void successWipesCallerAndOperationBuffers() {
    FakeDispatcher dispatcher = new FakeDispatcher();
    AtomicReference<char[]> operationOriginal = new AtomicReference<>();
    AtomicReference<char[]> operationReplacement = new AtomicReference<>();
    PassphraseChangeController controller = new PassphraseChangeController(dispatcher,
        (original, replacement, guard) -> {
          operationOriginal.set(original);
          operationReplacement.set(replacement);
          guard.check();
          return secret();
        });
    char[] original = "old".toCharArray();
    char[] replacement = "new".toCharArray();
    char[] repeated = "new".toCharArray();
    RecordingCallback callback = new RecordingCallback();

    controller.submit(owned(original), owned(replacement), owned(repeated), currentSession(), callback);
    assertTrue(isAllZero(original));
    assertTrue(isAllZero(replacement));
    assertTrue(isAllZero(repeated));

    dispatcher.run(0);

    assertEquals(1, callback.successes);
    assertTrue(isAllZero(operationOriginal.get()));
    assertTrue(isAllZero(operationReplacement.get()));
  }

  @Test
  public void errorWipesOperationBuffers() {
    FakeDispatcher dispatcher = new FakeDispatcher();
    AtomicReference<char[]> operationOriginal = new AtomicReference<>();
    PassphraseChangeController controller = new PassphraseChangeController(dispatcher,
        (original, replacement, guard) -> {
          operationOriginal.set(original);
          throw new Exception("storage failed");
        });
    RecordingCallback callback = new RecordingCallback();

    controller.submit(owned("old".toCharArray()), owned("new".toCharArray()),
                      owned("new".toCharArray()), currentSession(), callback);
    dispatcher.run(0);

    assertEquals(1, callback.failures);
    assertTrue(isAllZero(operationOriginal.get()));
  }

  @Test
  public void staleGenerationBeforeStartRejectsWithoutSideEffectOrCallback() {
    FakeDispatcher dispatcher = new FakeDispatcher();
    AtomicBoolean changed = new AtomicBoolean();
    AtomicReference<UnlockSession.Snapshot> snapshot =
        new AtomicReference<>(new UnlockSession.Snapshot(4L, secret()));
    UnlockSession session = new UnlockSession(4L, snapshot::get);
    PassphraseChangeController controller = new PassphraseChangeController(dispatcher,
        (original, replacement, guard) -> {
          guard.check();
          changed.set(true);
          return secret();
        });
    RecordingCallback callback = new RecordingCallback();
    controller.submit(owned("old".toCharArray()), owned("new".toCharArray()),
                      owned("new".toCharArray()), session, callback);

    snapshot.set(new UnlockSession.Snapshot(5L, secret()));
    dispatcher.run(0);

    assertFalse(changed.get());
    assertEquals(0, callback.successes);
    assertEquals(0, callback.failures);
  }

  @Test
  public void replacementCancelsPriorWorkAndSuppressesItsLateSuccess() {
    FakeDispatcher dispatcher = new FakeDispatcher();
    PassphraseChangeController controller = new PassphraseChangeController(
        dispatcher, (original, replacement, guard) -> secret());
    RecordingCallback first = new RecordingCallback();
    RecordingCallback second = new RecordingCallback();

    controller.submit(owned("old".toCharArray()), owned("first".toCharArray()),
                      owned("first".toCharArray()), currentSession(), first);
    controller.submit(owned("old".toCharArray()), owned("second".toCharArray()),
                      owned("second".toCharArray()), currentSession(), second);

    assertTrue(dispatcher.jobs.get(0).cancelled);
    dispatcher.succeedLate(0);
    dispatcher.succeedLate(1);

    assertEquals(0, first.successes);
    assertEquals(1, second.successes);
  }

  @Test
  public void cancellationWipesQueuedBuffersAndSuppressesLateSuccess() {
    FakeDispatcher dispatcher = new FakeDispatcher();
    AtomicReference<char[]> operationOriginal = new AtomicReference<>();
    PassphraseChangeController controller = new PassphraseChangeController(dispatcher,
        (original, replacement, guard) -> {
          operationOriginal.set(original);
          guard.check();
          return secret();
        });
    RecordingCallback callback = new RecordingCallback();
    controller.submit(owned("old".toCharArray()), owned("new".toCharArray()),
                      owned("new".toCharArray()), currentSession(), callback);

    controller.cancel();
    dispatcher.run(0);
    dispatcher.succeedLate(0);

    assertTrue(dispatcher.jobs.get(0).cancelled);
    assertTrue(isAllZero(operationOriginal.get()));
    assertEquals(0, callback.successes);
    assertEquals(0, callback.failures);
  }

  @Test
  public void cancellationSuppressesLateError() {
    FakeDispatcher dispatcher = new FakeDispatcher();
    PassphraseChangeController controller = new PassphraseChangeController(
        dispatcher, (original, replacement, guard) -> secret());
    RecordingCallback callback = new RecordingCallback();
    controller.submit(owned("old".toCharArray()), owned("new".toCharArray()),
                      owned("new".toCharArray()), currentSession(), callback);

    controller.cancel();
    dispatcher.failLate(0);

    assertEquals(0, callback.successes);
    assertEquals(0, callback.failures);
  }

  private static WipeablePassphrase owned(char[] value) {
    return WipeablePassphrase.takeOwnership(value);
  }

  private static UnlockSession currentSession() {
    MasterSecret secret = secret();
    return new UnlockSession(1L, () -> new UnlockSession.Snapshot(1L, secret));
  }

  private static MasterSecret secret() {
    return new MasterSecret(new SecretKeySpec(new byte[16], "AES"),
                            new SecretKeySpec(new byte[20], "HmacSHA1"));
  }

  private static boolean isAllZero(char[] value) {
    for (char character : value) if (character != '\0') return false;
    return true;
  }

  private static final class RecordingCallback implements PassphraseChangeController.Callback {
    private PassphraseChangeController.ValidationFailure validationFailure;
    private int successes;
    private int failures;

    @Override public void onValidationFailure(PassphraseChangeController.ValidationFailure failure) {
      validationFailure = failure;
    }

    @Override public void onSuccess(MasterSecret masterSecret) { successes++; }
    @Override public void onFailure(Exception exception) { failures++; }
  }

  private static final class FakeDispatcher implements PassphraseChangeController.Dispatcher {
    private final List<Job> jobs = new ArrayList<>();

    @Override public PassphraseChangeController.Cancellable submit(
        Callable<MasterSecret> work, PassphraseChangeController.CallbackSink success,
        PassphraseChangeController.FailureSink failure)
    {
      Job job = new Job(work, success, failure);
      jobs.add(job);
      return () -> job.cancelled = true;
    }

    private void run(int index) {
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

    private void failLate(int index) {
      jobs.get(index).failure.accept(new Exception("late"));
    }
  }

  private static final class Job {
    private final Callable<MasterSecret> work;
    private final PassphraseChangeController.CallbackSink success;
    private final PassphraseChangeController.FailureSink failure;
    private boolean cancelled;

    private Job(Callable<MasterSecret> work, PassphraseChangeController.CallbackSink success,
                PassphraseChangeController.FailureSink failure)
    {
      this.work = work;
      this.success = success;
      this.failure = failure;
    }
  }
}