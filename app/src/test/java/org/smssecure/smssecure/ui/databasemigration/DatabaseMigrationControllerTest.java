package org.smssecure.smssecure.ui.databasemigration;

import static org.assertj.core.api.Assertions.assertThat;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.smssecure.smssecure.database.SmsMigrator.ProgressDescription;
import org.smssecure.smssecure.domain.security.UnlockSession;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 35)
public class DatabaseMigrationControllerTest {
  private final Context context = ApplicationProvider.getApplicationContext();

  @Test
  public void attachDetachPairsBindingAndReceiverIdempotently() {
    FakeEnvironment environment = new FakeEnvironment();
    DatabaseMigrationController controller = new DatabaseMigrationController(environment);

    controller.attach(new RecordingObserver());
    controller.attach(new RecordingObserver());
    controller.detach();
    controller.detach();

    assertThat(environment.bindCount).isEqualTo(2);
    assertThat(environment.unbindCount).isEqualTo(2);
    assertThat(environment.registerCount).isEqualTo(2);
    assertThat(environment.unregisterCount).isEqualTo(2);
  }

  @Test
  public void disconnectBeforeConnectAndFailedBindAreSafe() {
    FakeEnvironment environment = new FakeEnvironment();
    environment.bindResult = false;
    DatabaseMigrationController controller = new DatabaseMigrationController(environment);

    controller.attach(new RecordingObserver());
    environment.connection.onServiceDisconnected(null);
    controller.detach();

    assertThat(environment.unbindCount).isZero();
    assertThat(environment.unregisterCount).isEqualTo(1);
  }

  @Test
  public void stoppedAndReplacedCallbacksCannotComplete() {
    FakeEnvironment environment = new FakeEnvironment();
    DatabaseMigrationController controller = new DatabaseMigrationController(environment);
    RecordingObserver first = new RecordingObserver();
    RecordingObserver second = new RecordingObserver();

    controller.attach(first);
    BroadcastReceiver stale = environment.receiver;
    controller.detach();
    stale.onReceive(context, new android.content.Intent());
    controller.attach(second);
    environment.receiver.onReceive(context, new android.content.Intent());

    assertThat(first.completions).isZero();
    assertThat(second.completions).isEqualTo(1);
  }

  @Test
  public void durableCompletionReplaysAndNavigationClaimIsOneShot() {
    FakeEnvironment environment = new FakeEnvironment();
    environment.imported = true;
    DatabaseMigrationController controller = new DatabaseMigrationController(environment);
    RecordingObserver observer = new RecordingObserver();

    controller.attach(observer);
    Shadows.shadowOf(Looper.getMainLooper()).idle();

    assertThat(observer.completions).isEqualTo(1);
    assertThat(controller.claimCompletion()).isTrue();
    assertThat(controller.claimCompletion()).isFalse();
    controller.close();
    assertThat(controller.claimCompletion()).isFalse();
  }

  @Test
  public void importUsesCapturedGenerationAndSkipRemainsExplicit() {
    FakeEnvironment environment = new FakeEnvironment();
    DatabaseMigrationController controller = new DatabaseMigrationController(environment);
    UnlockSession session = new UnlockSession(42L, () -> null);

    controller.startMigration(session);
    controller.skip();

    assertThat(environment.startedSession).isSameAs(session);
    assertThat(environment.startedSession.getGeneration()).isEqualTo(42L);
    assertThat(environment.skipCount).isEqualTo(1);
  }

  private static final class RecordingObserver implements DatabaseMigrationController.Observer {
    private int completions;

    @Override public void onState(int state, ProgressDescription progress) {}
    @Override public void onComplete() { completions++; }
  }

  private static final class FakeEnvironment implements DatabaseMigrationController.Environment {
    private ServiceConnection connection;
    private BroadcastReceiver receiver;
    private UnlockSession startedSession;
    private boolean bindResult = true;
    private boolean imported;
    private int bindCount;
    private int unbindCount;
    private int registerCount;
    private int unregisterCount;
    private int skipCount;

    @Override public boolean bind(@NonNull ServiceConnection connection) {
      bindCount++;
      this.connection = connection;
      return bindResult;
    }

    @Override public void unbind(@NonNull ServiceConnection connection) { unbindCount++; }

    @Override public void register(@NonNull BroadcastReceiver receiver) {
      registerCount++;
      this.receiver = receiver;
    }

    @Override public void unregister(@NonNull BroadcastReceiver receiver) {
      unregisterCount++;
    }

    @Override public void startMigration(@NonNull UnlockSession unlockSession) {
      startedSession = unlockSession;
    }

    @Override public void skip() { skipCount++; }
    @Override public boolean isDatabaseImported() { return imported; }
  }
}