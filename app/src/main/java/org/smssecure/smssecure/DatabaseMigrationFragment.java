package org.smssecure.smssecure;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import org.smssecure.smssecure.database.SmsMigrator.ProgressDescription;
import org.smssecure.smssecure.domain.security.UnlockSession;
import org.smssecure.smssecure.service.ApplicationMigrationService;
import org.smssecure.smssecure.service.ApplicationMigrationService.ImportState;
import org.smssecure.smssecure.ui.databasemigration.DatabaseMigrationController;

public final class DatabaseMigrationFragment extends Fragment {
  interface Callback {
    void onDatabaseMigrationCompleted(@NonNull DatabaseMigrationFragment fragment);
  }

  interface ControllerFactory {
    DatabaseMigrationController create(@NonNull DatabaseMigrationFragment fragment);
  }

  private static final ControllerFactory DEFAULT_CONTROLLER_FACTORY = fragment -> {
    Context context = fragment.requireContext();
    return new DatabaseMigrationController(new DatabaseMigrationController.Environment() {
      @Override
      public boolean bind(@NonNull ServiceConnection connection) {
        return context.bindService(new Intent(context, ApplicationMigrationService.class),
                                   connection, Context.BIND_AUTO_CREATE);
      }

      @Override
      public void unbind(@NonNull ServiceConnection connection) {
        context.unbindService(connection);
      }

      @Override
      public void register(@NonNull BroadcastReceiver receiver) {
        IntentFilter filter = new IntentFilter(ApplicationMigrationService.COMPLETED_ACTION);
        filter.setPriority(1000);
        ContextCompat.registerReceiver(context, receiver, filter,
                                       ContextCompat.RECEIVER_NOT_EXPORTED);
      }

      @Override
      public void unregister(@NonNull BroadcastReceiver receiver) {
        context.unregisterReceiver(receiver);
      }

      @Override
      public void startMigration(@NonNull UnlockSession unlockSession) {
        context.startService(ApplicationMigrationService.createMigrationIntent(context,
                                                                                unlockSession));
      }

      @Override
      public void skip() {
        ApplicationMigrationService.setDatabaseImported(context);
      }

      @Override
      public boolean isDatabaseImported() {
        return ApplicationMigrationService.isDatabaseImported(context);
      }
    });
  };

  private static ControllerFactory controllerFactory = DEFAULT_CONTROLLER_FACTORY;

  private DatabaseMigrationController controller;
  private LinearLayout promptLayout;
  private LinearLayout progressLayout;
  private ProgressBar progress;
  private TextView progressLabel;

  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    controller = controllerFactory.create(this);
  }

  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater,
                           @Nullable ViewGroup container,
                           @Nullable Bundle savedInstanceState) {
    return inflater.inflate(R.layout.database_migration_activity, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);
    view.setSaveFromParentEnabled(false);
    promptLayout = view.findViewById(R.id.prompt_layout);
    progressLayout = view.findViewById(R.id.progress_layout);
    Button skipButton = view.findViewById(R.id.skip_button);
    Button importButton = view.findViewById(R.id.import_button);
    progress = view.findViewById(R.id.import_progress);
    progressLabel = view.findViewById(R.id.import_status);
    progress.setSaveEnabled(false);
    progressLabel.setSaveEnabled(false);
    promptLayout.setVisibility(View.GONE);
    progressLayout.setVisibility(View.GONE);

    importButton.setOnClickListener(ignored -> {
      controller.startMigration(UnlockSession.capture());
      showProgress();
    });
    skipButton.setOnClickListener(ignored -> {
      controller.skip();
      handleCompletion();
    });
  }

  @Override
  public void onStart() {
    super.onStart();
    controller.attach(new DatabaseMigrationController.Observer() {
      @Override
      public void onState(int state, ProgressDescription update) {
        View view = getView();
        if (view != null) view.post(() -> renderState(state, update));
      }

      @Override
      public void onComplete() {
        View view = getView();
        if (view != null) view.post(DatabaseMigrationFragment.this::handleCompletion);
      }
    });
  }

  @Override
  public void onStop() {
    if (controller != null) controller.detach();
    super.onStop();
  }

  @Override
  public void onDestroyView() {
    promptLayout = null;
    progressLayout = null;
    progress = null;
    progressLabel = null;
    super.onDestroyView();
  }

  @Override
  public void onDestroy() {
    if (controller != null) controller.close();
    controller = null;
    super.onDestroy();
  }

  private void renderState(int state, @Nullable ProgressDescription update) {
    if (!isCurrent()) return;
    if (state == ImportState.STATE_IDLE) {
      promptLayout.setVisibility(View.VISIBLE);
      progressLayout.setVisibility(View.GONE);
    } else if (state == ImportState.STATE_MIGRATING_BEGIN) {
      showProgress();
    } else if (state == ImportState.STATE_MIGRATING_IN_PROGRESS) {
      renderProgress(update);
    }
  }

  private void renderProgress(@Nullable ProgressDescription update) {
    showProgress();
    if (update == null) {
      progressLabel.setText("0/0");
      progress.setProgress(0);
      progress.setSecondaryProgress(0);
      return;
    }
    progressLabel.setText(Math.max(0, update.primaryComplete) + "/" +
                Math.max(0, update.primaryTotal));
    progress.setProgress(scale(update.primaryComplete, update.primaryTotal, progress.getMax()));
    progress.setSecondaryProgress(
        scale(update.secondaryComplete, update.secondaryTotal, progress.getMax()));
  }

  private void showProgress() {
    if (promptLayout == null || progressLayout == null) return;
    promptLayout.setVisibility(View.GONE);
    progressLayout.setVisibility(View.VISIBLE);
  }

  private void handleCompletion() {
    if (!isCurrent() || !controller.claimCompletion()) return;
    callback().onDatabaseMigrationCompleted(this);
  }

  private boolean isCurrent() {
    return isAdded() && !isHidden() && getView() != null &&
        getLifecycle().getCurrentState().isAtLeast(
            androidx.lifecycle.Lifecycle.State.STARTED);
  }

  private Callback callback() {
    return (Callback) requireActivity();
  }

  static int scale(int complete, int total, int maximum) {
    if (total <= 0 || maximum <= 0) return 0;
    double fraction = Math.max(0.0, Math.min(1.0, complete / (double) total));
    return (int) Math.round(fraction * maximum);
  }

  static void setControllerFactoryForTests(@NonNull ControllerFactory factory) {
    controllerFactory = factory;
  }

  static void resetControllerFactoryForTests() {
    controllerFactory = DEFAULT_CONTROLLER_FACTORY;
  }
}