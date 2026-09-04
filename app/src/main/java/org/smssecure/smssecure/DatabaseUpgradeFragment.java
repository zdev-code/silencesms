package org.smssecure.smssecure;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import org.smssecure.smssecure.domain.conversation.ConversationUnlockCapability;
import org.smssecure.smssecure.domain.security.UnlockSession;
import org.smssecure.smssecure.domain.upgrade.DatabaseUpgradeCoordinator;
import org.smssecure.smssecure.domain.upgrade.DatabaseUpgradePolicy;
import org.smssecure.smssecure.notifications.MessageNotifier;
import org.smssecure.smssecure.ui.databaseupgrade.DatabaseUpgradeController;
import org.smssecure.smssecure.util.ParcelUtil;
import org.smssecure.smssecure.util.Util;
import org.smssecure.smssecure.util.VersionTracker;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor;
import org.whispersystems.jobqueue.EncryptionKeys;

import dagger.hilt.EntryPoint;
import dagger.hilt.InstallIn;
import dagger.hilt.android.EntryPointAccessors;
import dagger.hilt.components.SingletonComponent;

public final class DatabaseUpgradeFragment extends Fragment {
  private static final String TAG = DatabaseUpgradeFragment.class.getSimpleName();

  interface Callback {
    void onDatabaseUpgradeCompleted(@NonNull DatabaseUpgradeFragment fragment);
    void onDatabaseUpgradeInvalidated(@NonNull DatabaseUpgradeFragment fragment);
  }

  interface OperationFactory {
    DatabaseUpgradeController.Operation create(@NonNull DatabaseUpgradeFragment fragment);
  }

  @EntryPoint
  @InstallIn(SingletonComponent.class)
  interface UpgradeEntryPoint {
    DatabaseUpgradeCoordinator databaseUpgradeCoordinator();
  }

  private static final OperationFactory DEFAULT_OPERATION_FACTORY = fragment -> {
    DatabaseUpgradeCoordinator coordinator = EntryPointAccessors.fromApplication(
        fragment.requireContext().getApplicationContext(), UpgradeEntryPoint.class)
        .databaseUpgradeCoordinator();
    return new DatabaseUpgradeController.Operation() {
      @Override public void start(int fromVersion, int targetVersion,
                                  @NonNull ConversationUnlockCapability capability) {
        coordinator.start(fromVersion, targetVersion, capability);
      }

      @NonNull
      @Override public DatabaseUpgradeController.Subscription observe(
          @NonNull DatabaseUpgradeController.Observer observer) {
        DatabaseUpgradeCoordinator.Subscription subscription = coordinator.observe(
            new DatabaseUpgradeCoordinator.Observer() {
              @Override public void onProgress(int progress, int total) {
                observer.onProgress(progress, total);
              }

              @Override public void onComplete() {
                observer.onComplete();
              }

              @Override public void onFailure(Exception exception) {
                observer.onFailure(exception);
              }
            });
        return subscription::close;
      }

      @Override public void clearCompletedRecord() {
        coordinator.clearCompletedRecord();
      }

      @Override public void finalizeWithoutUpgrade(@NonNull Context context,
                                                   @NonNull UnlockSession unlockSession)
          throws Exception {
        unlockSession.use(secret -> {
          coordinator.clearCompletedRecord();
          VersionTracker.updateLastSeenVersion(context);
          ApplicationContext.getInstance(context)
              .getJobManager()
              .setEncryptionKeys(new EncryptionKeys(ParcelUtil.serialize(secret)));
          updateNotifications(context, secret);
          return null;
        });
      }
    };
  };

  private static OperationFactory operationFactory = DEFAULT_OPERATION_FACTORY;

  private ProgressBar indeterminateProgress;
  private ProgressBar determinateProgress;
  private DatabaseUpgradeController controller;
  private UnlockSession unlockSession;
  private boolean upgradeRequired;

  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    int lastSeenVersion = VersionTracker.getLastSeenVersion(requireContext());
    int currentVersion = Util.getCurrentApkReleaseVersion(requireContext());
    Log.w(TAG, "LastSeenVersion: " + lastSeenVersion);

    unlockSession = UnlockSession.capture();
    upgradeRequired = DatabaseUpgradePolicy.needsUpgrade(lastSeenVersion, currentVersion);
    controller = new DatabaseUpgradeController(operationFactory.create(this));
    if (upgradeRequired) {
      Log.w(TAG, "Upgrading...");
      controller.start(lastSeenVersion, currentVersion,
          new ConversationUnlockCapability(unlockSession));
    }
  }

  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater,
                           @Nullable ViewGroup container,
                           @Nullable Bundle savedInstanceState) {
    return inflater.inflate(R.layout.database_upgrade_activity, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);
    indeterminateProgress = view.findViewById(R.id.indeterminate_progress);
    determinateProgress = view.findViewById(R.id.determinate_progress);
  }

  @Override
  public void onStart() {
    super.onStart();
    if (!upgradeRequired) return;
    controller.attach(new DatabaseUpgradeController.Observer() {
      @Override public void onProgress(int progress, int total) {
        runOnUiThread(() -> renderProgress(progress, total));
      }

      @Override public void onComplete() {
        runOnUiThread(DatabaseUpgradeFragment.this::handleUpgradeCompleted);
      }

      @Override public void onFailure(@NonNull Exception exception) {
        Log.w(TAG, "Database upgrade failed", exception);
        if (exception instanceof ConversationUnlockCapability.LockedException) {
          runOnUiThread(DatabaseUpgradeFragment.this::failClosed);
        }
      }
    });
  }

  @Override
  public void onResume() {
    super.onResume();
    if (!upgradeRequired) finalizeWithoutUpgrade();
  }

  @Override
  public void onStop() {
    if (controller != null) controller.detach();
    super.onStop();
  }

  @Override
  public void onDestroy() {
    if (controller != null) controller.close();
    controller = null;
    unlockSession = null;
    super.onDestroy();
  }

  private void renderProgress(int progress, int total) {
    if (!isStarted() || total <= 0 || indeterminateProgress == null ||
        determinateProgress == null) return;
    indeterminateProgress.setVisibility(View.GONE);
    determinateProgress.setVisibility(View.VISIBLE);
    double scaled = Math.max(0.0, Math.min(1.0, progress / (double) total));
    determinateProgress.setProgress((int) Math.floor(determinateProgress.getMax() * scaled));
  }

  private void handleUpgradeCompleted() {
    if (!canComplete()) return;
    try {
      unlockSession.use(secret -> null);
      controller.clearCompletedRecord();
      callback().onDatabaseUpgradeCompleted(this);
    } catch (Exception exception) {
      Log.w(TAG, "Unlock changed before database upgrade completion", exception);
      callback().onDatabaseUpgradeInvalidated(this);
    }
  }

  private void finalizeWithoutUpgrade() {
    if (!canComplete()) return;
    try {
      controller.finalizeWithoutUpgrade(requireContext(), unlockSession);
      callback().onDatabaseUpgradeCompleted(this);
    } catch (Exception exception) {
      Log.w(TAG, "Unlock changed before database upgrade finalization", exception);
      callback().onDatabaseUpgradeInvalidated(this);
    }
  }

  private boolean canComplete() {
    return isStarted() && controller != null && controller.claimCompletion();
  }

  private boolean isStarted() {
    return isAdded() && getLifecycle().getCurrentState().isAtLeast(
        androidx.lifecycle.Lifecycle.State.STARTED);
  }

  private void failClosed() {
    if (!canComplete()) return;
    callback().onDatabaseUpgradeInvalidated(this);
  }

  private void runOnUiThread(@NonNull Runnable runnable) {
    View view = getView();
    if (view != null) view.post(runnable);
  }

  private Callback callback() {
    return (Callback) requireActivity();
  }

  private static void updateNotifications(Context context,
                                          org.smssecure.smssecure.crypto.MasterSecret secret) {
    Context applicationContext = context.getApplicationContext();
    AppTaskExecutor.getInstance().submitSerial(
        () -> {
          MessageNotifier.updateNotification(applicationContext, secret);
          return null;
        },
        ignored -> {},
        exception -> Log.w(TAG, "Unable to update notifications after database upgrade", exception));
  }

  static void setOperationFactoryForTests(@NonNull OperationFactory factory) {
    operationFactory = factory;
  }

  static void resetOperationFactoryForTests() {
    operationFactory = DEFAULT_OPERATION_FACTORY;
  }
}