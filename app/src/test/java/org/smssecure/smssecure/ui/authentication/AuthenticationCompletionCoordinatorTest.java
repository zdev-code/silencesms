package org.smssecure.smssecure.ui.authentication;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.service.KeyCachingService;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import javax.crypto.spec.SecretKeySpec;

public class AuthenticationCompletionCoordinatorTest {
  @Test
  public void closeCancelsBindingAndSuppressesLateCompletion() {
    FakeConnector connector = new FakeConnector();
    RecordingCallback callback = new RecordingCallback();
    AuthenticationCompletionCoordinator coordinator =
        new AuthenticationCompletionCoordinator(connector);

    coordinator.establish(secret(), callback);
    coordinator.close();
    connector.jobs.get(0).completeLate();

    assertTrue(connector.jobs.get(0).cancelled);
    assertFalse(connector.jobs.get(0).current.getAsBoolean());
    assertEquals(0, callback.established);
    assertEquals(0, callback.failures);
  }

  @Test
  public void replacementInvalidatesPriorBindingAndAcceptsOnlyCurrentCompletion() {
    FakeConnector connector = new FakeConnector();
    RecordingCallback callback = new RecordingCallback();
    AuthenticationCompletionCoordinator coordinator =
        new AuthenticationCompletionCoordinator(connector);

    coordinator.establish(secret(), callback);
    coordinator.establish(secret(), callback);
    connector.jobs.get(0).completeLate();
    connector.jobs.get(1).completeLate();

    assertTrue(connector.jobs.get(0).cancelled);
    assertEquals(1, callback.established);
  }

  @Test
  public void staleOwnerCannotReachActivationBoundary() {
    FakeConnector connector = new FakeConnector();
    AuthenticationCompletionCoordinator coordinator =
        new AuthenticationCompletionCoordinator(connector);

    coordinator.establish(secret(), new RecordingCallback());
    coordinator.close();

    assertFalse(connector.jobs.get(0).attemptActivation());
  }

  @Test
  public void pendingPrimeDiscardIsIdentityChecked() {
    MasterSecret pending = secret();
    KeyCachingService.markAuthenticationActivationPending(pending);
    KeyCachingService.primeMasterSecret(pending);

    KeyCachingService.discardPrimedMasterSecret(secret());
    assertTrue(KeyCachingService.isAuthenticationActivationPending(pending));
    assertTrue(KeyCachingService.getCachedMasterSecret() == null);

    KeyCachingService.discardPrimedMasterSecret(pending);
    assertTrue(KeyCachingService.getCachedMasterSecret() == null);
    assertFalse(KeyCachingService.isAuthenticationActivationPending(pending));
  }

  @Test
  public void pendingAuthenticationPrimeIsNotPublishedUntilSetPromotesIt() {
    MasterSecret pending = secret();
    KeyCachingService.markAuthenticationActivationPending(pending);
    KeyCachingService.primeMasterSecret(pending);

    assertTrue(KeyCachingService.getCachedMasterSecret() == null);
    assertTrue(KeyCachingService.getSecretSnapshot().getSecret() == null);

    KeyCachingService.discardPrimedMasterSecret(pending);
  }

  private static MasterSecret secret() {
    return new MasterSecret(new SecretKeySpec(new byte[16], "AES"),
                            new SecretKeySpec(new byte[20], "HmacSHA1"));
  }

  private static final class RecordingCallback
      implements AuthenticationCompletionCoordinator.Callback {
    private int established;
    private int failures;

    @Override public void onEstablished() { established++; }
    @Override public void onFailure(Exception exception) { failures++; }
  }

  private static final class FakeConnector
      implements AuthenticationCompletionCoordinator.Connector {
    private final List<Job> jobs = new ArrayList<>();

    @Override
    public AuthenticationCompletionCoordinator.Cancellable connect(
        MasterSecret masterSecret, BooleanSupplier current, Runnable established,
        AuthenticationCompletionCoordinator.FailureSink failure) {
      Job job = new Job(current, established);
      jobs.add(job);
      return () -> job.cancelled = true;
    }
  }

  private static final class Job {
    private final BooleanSupplier current;
    private final Runnable established;
    private boolean cancelled;

    private Job(BooleanSupplier current, Runnable established) {
      this.current = current;
      this.established = established;
    }

    private boolean attemptActivation() {
      if (cancelled || !current.getAsBoolean()) return false;
      established.run();
      return true;
    }

    private void completeLate() {
      established.run();
    }
  }
}