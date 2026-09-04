package org.smssecure.smssecure.domain.upgrade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.Before;
import org.junit.Test;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.domain.conversation.ConversationUnlockCapability;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor;

import java.util.concurrent.Callable;

import javax.crypto.spec.SecretKeySpec;

public class DatabaseUpgradeCoordinatorTest {
  private AppTaskExecutor executor;
  private MemoryStorage storage;

  @Before
  public void setUp() throws Exception {
    executor = mock(AppTaskExecutor.class);
    storage = new MemoryStorage();
    org.mockito.Mockito.when(executor.submitSerial(any(), any(), any())).thenAnswer(invocation -> {
      Callable<?> work = invocation.getArgument(0);
      AppTaskExecutor.SuccessCallback<Object> success = invocation.getArgument(1);
      try {
        success.onSuccess(work.call());
      } catch (Exception exception) {
        AppTaskExecutor.FailureCallback failure = invocation.getArgument(2);
        failure.onFailure(exception);
      }
      return mock(AppTaskExecutor.TaskHandle.class);
    });
  }

  @Test
  public void restartResumesAfterLastCompletedCheckpoint() {
    RecordingSteps interrupted = new RecordingSteps();
    interrupted.failSimPrompt = true;
    DatabaseUpgradeCoordinator first = new DatabaseUpgradeCoordinator(executor, storage, interrupted);

    first.start(100, 216, unlockCapability());

    assertThat(interrupted.databaseRuns).isEqualTo(1);
    assertThat(storage.record.getStage()).isEqualTo(UpgradeOperationStore.Stage.SIM_PROMPT);

    RecordingSteps resumed = new RecordingSteps();
    DatabaseUpgradeCoordinator second = new DatabaseUpgradeCoordinator(executor, storage, resumed);
    DatabaseUpgradeCoordinator.Observer observer = mock(DatabaseUpgradeCoordinator.Observer.class);
    second.observe(observer);
    second.start(100, 216, unlockCapability());

    assertThat(resumed.databaseRuns).isZero();
    assertThat(resumed.simPromptRuns).isEqualTo(1);
    assertThat(resumed.multiSimRuns).isEqualTo(1);
    assertThat(resumed.finalizeRuns).isEqualTo(1);
    assertThat(storage.record.getStage()).isEqualTo(UpgradeOperationStore.Stage.COMPLETE);
    verify(observer).onComplete();
  }

  @Test
  public void relockLeavesCurrentCheckpointAndDoesNotFinalize() {
    RecordingSteps steps = new RecordingSteps();
    MasterSecret original = secret((byte) 1);
    MasterSecret replacement = secret((byte) 2);
    DatabaseUpgradeCoordinator coordinator = new DatabaseUpgradeCoordinator(executor, storage, steps);
    DatabaseUpgradeCoordinator.Observer observer = mock(DatabaseUpgradeCoordinator.Observer.class);
    coordinator.observe(observer);

    coordinator.start(100, 216, new ConversationUnlockCapability(original, () -> replacement));

    assertThat(storage.record.getStage()).isEqualTo(UpgradeOperationStore.Stage.DATABASE);
    assertThat(steps.databaseRuns).isZero();
    assertThat(steps.finalizeRuns).isZero();
    verify(observer).onFailure(any(ConversationUnlockCapability.LockedException.class));
  }

  @Test
  public void relockAtFinalizationLeavesFinalizationCheckpointForResume() {
    storage.record = new UpgradeOperationStore.Record(
        100, 216, UpgradeOperationStore.Stage.FINALIZE);
    RecordingSteps steps = new RecordingSteps();
    MasterSecret original = secret((byte) 1);
    MasterSecret replacement = secret((byte) 2);
    DatabaseUpgradeCoordinator coordinator = new DatabaseUpgradeCoordinator(executor, storage, steps);
    DatabaseUpgradeCoordinator.Observer observer = mock(DatabaseUpgradeCoordinator.Observer.class);
    coordinator.observe(observer);

    coordinator.start(100, 216, new ConversationUnlockCapability(original, () -> replacement));

    assertThat(storage.record.getStage()).isEqualTo(UpgradeOperationStore.Stage.FINALIZE);
    assertThat(steps.databaseRuns).isZero();
    assertThat(steps.simPromptRuns).isZero();
    assertThat(steps.multiSimRuns).isZero();
    assertThat(steps.finalizeRuns).isZero();
    verify(observer).onFailure(any(ConversationUnlockCapability.LockedException.class));
  }

  @Test
  public void observerReattachReceivesLatestProgressAndCompletion() {
    RecordingSteps steps = new RecordingSteps();
    DatabaseUpgradeCoordinator coordinator = new DatabaseUpgradeCoordinator(executor, storage, steps);

    coordinator.start(100, 216, unlockCapability());
    DatabaseUpgradeCoordinator.Observer observer = mock(DatabaseUpgradeCoordinator.Observer.class);
    coordinator.observe(observer);

    verify(observer).onProgress(1, 2);
    verify(observer).onComplete();
  }

  @Test
  public void restartRunsOnlyThePersistedStageAndThoseAfterIt() {
    UpgradeOperationStore.Stage[] stages = UpgradeOperationStore.Stage.values();
    int[][] expectedRuns = {
        {1, 1, 1, 1},
        {0, 1, 1, 1},
        {0, 0, 1, 1},
        {0, 0, 0, 1},
        {0, 0, 0, 0}
    };

    for (int index = 0; index < stages.length; index++) {
      MemoryStorage resumedStorage = new MemoryStorage();
      resumedStorage.record = new UpgradeOperationStore.Record(100, 216, stages[index]);
      RecordingSteps steps = new RecordingSteps();
      DatabaseUpgradeCoordinator coordinator =
          new DatabaseUpgradeCoordinator(executor, resumedStorage, steps);

      coordinator.start(100, 216, unlockCapability());

      assertThat(new int[] {steps.databaseRuns, steps.simPromptRuns,
                            steps.multiSimRuns, steps.finalizeRuns})
          .as("restart from %s", stages[index])
          .containsExactly(expectedRuns[index]);
      assertThat(resumedStorage.record.getStage()).isEqualTo(UpgradeOperationStore.Stage.COMPLETE);
    }
  }

  private static ConversationUnlockCapability unlockCapability() {
    MasterSecret secret = secret((byte) 0);
    return new ConversationUnlockCapability(secret, () -> secret);
  }

  private static MasterSecret secret(byte value) {
    byte[] key = new byte[16];
    java.util.Arrays.fill(key, value);
    return new MasterSecret(new SecretKeySpec(key, "AES"), new SecretKeySpec(key, "HmacSHA1"));
  }

  private static final class MemoryStorage implements UpgradeOperationStore.Storage {
    private UpgradeOperationStore.Record record;
    @Override public UpgradeOperationStore.Record read() { return record; }
    @Override public void write(UpgradeOperationStore.Record record) { this.record = record; }
    @Override public void clear() { record = null; }
  }

  private static final class RecordingSteps implements DatabaseUpgradeCoordinator.Steps {
    private int databaseRuns;
    private int simPromptRuns;
    private int multiSimRuns;
    private int finalizeRuns;
    private boolean failSimPrompt;

    @Override public void runDatabase(MasterSecret secret, int fromVersion,
                                      DatabaseUpgradePolicy.ProgressListener listener) {
      databaseRuns++;
      listener.setProgress(1, 2);
    }
    @Override public void updateSimPrompt(int fromVersion) {
      simPromptRuns++;
      if (failSimPrompt) throw new IllegalStateException("interrupted");
    }
    @Override public void migrateMultiSim(MasterSecret secret, int fromVersion) { multiSimRuns++; }
    @Override public void finalizeUpgrade(MasterSecret secret) { finalizeRuns++; }
  }
}
