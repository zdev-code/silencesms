package org.smssecure.smssecure.ui.databaseupgrade;

import static org.assertj.core.api.Assertions.assertThat;

import androidx.annotation.NonNull;

import org.junit.Test;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.domain.conversation.ConversationUnlockCapability;
import org.smssecure.smssecure.domain.security.UnlockSession;

import java.util.ArrayList;
import java.util.List;

import javax.crypto.spec.SecretKeySpec;

public class DatabaseUpgradeControllerTest {
  @Test
  public void startsOnceAndReattachesWithoutRestarting() {
    FakeOperation operation = new FakeOperation();
    DatabaseUpgradeController controller = new DatabaseUpgradeController(operation);
    RecordingObserver first = new RecordingObserver();
    RecordingObserver second = new RecordingObserver();

    controller.start(100, 216, capability());
    controller.start(100, 216, capability());
    controller.attach(first);
    operation.emitProgress(1, 4);
    controller.detach();
    controller.attach(second);
    operation.emitProgress(3, 4);

    assertThat(operation.startCount).isEqualTo(1);
    assertThat(first.progress).containsExactly("1/4");
    assertThat(second.progress).containsExactly("3/4");
  }

  @Test
  public void detachedAndReplacedObserversCannotDeliverStaleCallbacks() {
    FakeOperation operation = new FakeOperation();
    DatabaseUpgradeController controller = new DatabaseUpgradeController(operation);
    RecordingObserver first = new RecordingObserver();
    RecordingObserver second = new RecordingObserver();

    controller.attach(first);
    DatabaseUpgradeController.Observer stale = operation.observers.get(0);
    controller.attach(second);
    stale.onProgress(1, 2);
    stale.onComplete();
    operation.emitComplete();

    assertThat(first.progress).isEmpty();
    assertThat(first.completions).isZero();
    assertThat(second.completions).isEqualTo(1);
  }

  @Test
  public void completionAndNavigationCanOnlyBeClaimedOnce() {
    DatabaseUpgradeController controller =
        new DatabaseUpgradeController(new FakeOperation());

    assertThat(controller.claimCompletion()).isTrue();
    assertThat(controller.claimCompletion()).isFalse();
    controller.close();
    assertThat(controller.claimCompletion()).isFalse();
  }

  private static ConversationUnlockCapability capability() {
    byte[] key = new byte[16];
    MasterSecret secret = new MasterSecret(new SecretKeySpec(key, "AES"),
                                           new SecretKeySpec(key, "HmacSHA1"));
    return new ConversationUnlockCapability(secret, () -> secret);
  }

  private static final class RecordingObserver implements DatabaseUpgradeController.Observer {
    private final List<String> progress = new ArrayList<>();
    private int completions;

    @Override public void onProgress(int progress, int total) {
      this.progress.add(progress + "/" + total);
    }

    @Override public void onComplete() {
      completions++;
    }

    @Override public void onFailure(@NonNull Exception exception) {}
  }

  private static final class FakeOperation implements DatabaseUpgradeController.Operation {
    private final List<DatabaseUpgradeController.Observer> observers = new ArrayList<>();
    private int startCount;

    @Override public void start(int fromVersion, int targetVersion,
                                @NonNull ConversationUnlockCapability capability) {
      startCount++;
    }

    @NonNull
    @Override public DatabaseUpgradeController.Subscription observe(
        @NonNull DatabaseUpgradeController.Observer observer) {
      observers.add(observer);
      return () -> observers.remove(observer);
    }

    @Override public void clearCompletedRecord() {}

    @Override public void finalizeWithoutUpgrade(@NonNull android.content.Context context,
                           @NonNull UnlockSession unlockSession) {}

    private void emitProgress(int progress, int total) {
      for (DatabaseUpgradeController.Observer observer : new ArrayList<>(observers)) {
        observer.onProgress(progress, total);
      }
    }

    private void emitComplete() {
      for (DatabaseUpgradeController.Observer observer : new ArrayList<>(observers)) {
        observer.onComplete();
      }
    }
  }
}