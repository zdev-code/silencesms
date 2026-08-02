package org.smssecure.smssecure;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Build;
import android.net.Uri;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.appcompat.app.AlertDialog;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.Toast;

import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.database.EncryptedBackupExporter;
import org.smssecure.smssecure.database.NoExternalStorageException;
import org.smssecure.smssecure.database.PlaintextBackupExporter;
import org.smssecure.smssecure.database.PlaintextBackupImporter;
import org.smssecure.smssecure.permissions.Permissions;
import org.smssecure.smssecure.service.ApplicationMigrationService;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Callable;


public class ImportExportFragment extends Fragment {

  @SuppressWarnings("unused")
  private static final String TAG = ImportExportFragment.class.getSimpleName();

  private static final int SUCCESS                             = 0;
  private static final int NO_SD_CARD                          = 1;
  private static final int ERROR_IO                            = 2;
  private static final String[] PLAINTEXT_BACKUP_MIME_TYPES    = new String[] {
      "application/xml",
      "text/xml",
      "text/plain",
      "application/octet-stream"
  };

  private MasterSecret                masterSecret;
  private final Permissions.FragmentPermissionLauncher permissionLauncher = Permissions.registerForResult(this);
  private final ActivityResultLauncher<String[]> plaintextBackupPicker =
      registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::handlePlaintextBackupDocument);
  private final ActivityResultLauncher<String> plaintextBackupSaver =
      registerForActivityResult(new ActivityResultContracts.CreateDocument("application/xml"),
                                this::handlePlaintextBackupDestination);
  private AlertDialog                 progressDialog;
  private AppTaskExecutor.TaskHandle currentTask;
  private int                         operationGeneration;

  @Override
  public void onCreate(Bundle bundle) {
    super.onCreate(bundle);
    this.masterSecret = androidx.core.os.BundleCompat.getParcelable(getArguments(), "master_secret", MasterSecret.class);
  }

  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle bundle) {
    View layout              = inflater.inflate(R.layout.import_export_fragment, container, false);
    View importSmsView       = layout.findViewById(R.id.import_sms             );
    View importEncryptedView = layout.findViewById(R.id.import_encrypted_backup);
    View importPlaintextView = layout.findViewById(R.id.import_plaintext_backup);
    View exportEncryptedView = layout.findViewById(R.id.export_encrypted_backup);
    View exportPlaintextView = layout.findViewById(R.id.export_plaintext_backup);

    importSmsView.setOnClickListener(v -> handleImportSms());
    importEncryptedView.setOnClickListener(v -> handleImportEncryptedBackup());
    importPlaintextView.setOnClickListener(v -> handleImportPlaintextBackup());
    exportEncryptedView.setOnClickListener(v -> handleExportEncryptedBackup());
    exportPlaintextView.setOnClickListener(v -> handleExportPlaintextBackup());

    return layout;
  }

  @Override
  public void onDestroyView() {
    cancelCurrentOperation();
    super.onDestroyView();
  }

  @SuppressWarnings("CodeBlock2Expr")
  private void handleImportSms() {
    Activity activity = getActivity();
    if (!isAdded() || activity == null) return;

    AlertDialog.Builder builder = new AlertDialog.Builder(activity);
    builder.setIconAttribute(R.attr.dialog_info_icon);
    builder.setTitle(activity.getString(R.string.ImportFragment_import_system_sms_database));
    builder.setMessage(activity.getString(R.string.ImportFragment_this_will_import_messages_from_the_system));
    builder.setPositiveButton(activity.getString(R.string.ImportFragment_import), (dialog, which) -> {
      Permissions.with(this, permissionLauncher)
                 .request(Manifest.permission.READ_SMS)
                 .ifNecessary()
                 .withPermanentDenialDialog(getString(R.string.ImportExportFragment_silence_needs_the_sms_permission_in_order_to_import_sms_messages))
                 .onAllGranted(() -> {
                   Activity currentActivity = getActivity();
                   if (!isAdded() || currentActivity == null) return;

                   Intent intent = new Intent(currentActivity, ApplicationMigrationService.class);
                   intent.setAction(ApplicationMigrationService.MIGRATE_DATABASE);
                   intent.putExtra("master_secret", masterSecret);
                   currentActivity.startService(intent);

                   Intent nextIntent = new Intent(currentActivity, ConversationListActivity.class);

                   Intent activityIntent = new Intent(currentActivity, DatabaseMigrationActivity.class);
                   activityIntent.putExtra("next_intent", nextIntent);
                   currentActivity.startActivity(activityIntent);
                 })
                 .onAnyDenied(() -> showToast(R.string.ImportExportFragment_silence_needs_the_sms_permission_in_order_to_import_sms_messages_toast))
                 .execute();
    });
    builder.setNegativeButton(activity.getString(R.string.ImportFragment_cancel), null);
    builder.show();
  }

  @SuppressWarnings("CodeBlock2Expr")
  @SuppressLint("InlinedApi")
  private void handleImportEncryptedBackup() {
    Activity activity = getActivity();
    if (!isAdded() || activity == null) return;

    AlertDialog.Builder builder = new AlertDialog.Builder(activity);
    builder.setIconAttribute(R.attr.dialog_alert_icon);
    builder.setTitle(activity.getString(R.string.ImportFragment_restore_encrypted_backup));
    builder.setMessage(activity.getString(R.string.ImportFragment_restoring_an_encrypted_backup_will_completely_replace_your_existing_keys));
    builder.setPositiveButton(activity.getString(R.string.ImportFragment_import), (dialog, which) -> {
      String[] permissions = getReadStoragePermissions();

      if (permissions.length == 0) {
        startImportEncryptedBackup();
      } else {
        Permissions.with(this, permissionLauncher)
                   .request(permissions)
                   .ifNecessary()
                   .withPermanentDenialDialog(getString(R.string.ImportExportFragment_silence_needs_the_storage_permission_in_order_to_read_from_external_storage_but_it_has_been_permanently_denied))
                   .onAllGranted(this::startImportEncryptedBackup)
                   .onAnyDenied(() -> showToast(R.string.ImportExportFragment_silence_needs_the_storage_permission_in_order_to_read_from_external_storage))
                   .execute();
      }
    });
    builder.setNegativeButton(activity.getString(R.string.ImportFragment_cancel), null);
    builder.show();
  }

  @SuppressWarnings("CodeBlock2Expr")
  @SuppressLint("InlinedApi")
  private void handleImportPlaintextBackup() {
    Activity activity = getActivity();
    if (!isAdded() || activity == null) return;

    AlertDialog.Builder builder = new AlertDialog.Builder(activity);
    builder.setIconAttribute(R.attr.dialog_alert_icon);
    builder.setTitle(activity.getString(R.string.ImportFragment_import_plaintext_backup));
    builder.setMessage(activity.getString(R.string.ImportFragment_this_will_import_messages_from_a_plaintext_backup));
    builder.setPositiveButton(activity.getString(R.string.ImportFragment_import), (dialog, which) -> {
      String[] permissions = getReadStoragePermissions();

      Runnable onGranted = () -> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          launchPlaintextBackupPicker();
        } else {
          startImportPlaintextBackup(null);
        }
      };

      if (permissions.length == 0) {
        onGranted.run();
      } else {
        Permissions.with(ImportExportFragment.this, permissionLauncher)
                   .request(permissions)
                   .ifNecessary()
                   .withPermanentDenialDialog(getString(R.string.ImportExportFragment_silence_needs_the_storage_permission_in_order_to_read_from_external_storage_but_it_has_been_permanently_denied))
                   .onAllGranted(onGranted)
                   .onAnyDenied(() -> showToast(R.string.ImportExportFragment_silence_needs_the_storage_permission_in_order_to_read_from_external_storage))
                   .execute();
      }
    });
    builder.setNegativeButton(activity.getString(R.string.ImportFragment_cancel), null);
    builder.show();
  }

  private void handleExportEncryptedBackup() {
    Activity activity = getActivity();
    if (!isAdded() || activity == null) return;

    AlertDialog.Builder builder = new AlertDialog.Builder(activity);
    builder.setIconAttribute(R.attr.dialog_info_icon);
    builder.setTitle(activity.getString(R.string.ExportFragment_export_encrypted_backup));
    builder.setMessage(activity.getString(R.string.ExportFragment_this_will_export_your_encrypted_keys_settings_and_messages));
    builder.setPositiveButton(activity.getString(R.string.ExportFragment_export), (dialog, which) -> {
      String[] permissions = getWriteStoragePermissions();

      if (permissions.length == 0) {
        startExportEncryptedBackup();
      } else {
        Permissions.with(ImportExportFragment.this, permissionLauncher)
                   .request(permissions)
                   .ifNecessary()
                   .withPermanentDenialDialog(getString(R.string.ImportExportFragment_silence_needs_the_storage_permission_in_order_to_write_to_external_storage_but_it_has_been_permanently_denied))
                   .onAllGranted(this::startExportEncryptedBackup)
                   .onAnyDenied(() -> showToast(R.string.ImportExportFragment_silence_needs_the_storage_permission_in_order_to_write_to_external_storage))
                   .execute();
      }
    });
    builder.setNegativeButton(activity.getString(R.string.ExportFragment_cancel), null);
    builder.show();
  }

  private void launchPlaintextBackupPicker() {
    if (!isAdded() || getActivity() == null) return;

    plaintextBackupPicker.launch(PLAINTEXT_BACKUP_MIME_TYPES);
  }

  private void launchPlaintextBackupSaver() {
    if (!isAdded() || getActivity() == null) return;

    plaintextBackupSaver.launch("SilencePlaintextBackup.xml");
  }

  @SuppressWarnings("CodeBlock2Expr")
  @SuppressLint("InlinedApi")
  private void handleExportPlaintextBackup() {
    Activity activity = getActivity();
    if (!isAdded() || activity == null) return;

    AlertDialog.Builder builder = new AlertDialog.Builder(activity);
    builder.setIconAttribute(R.attr.dialog_alert_icon);
    builder.setTitle(activity.getString(R.string.ExportFragment_export_plaintext_to_storage));
    builder.setMessage(activity.getString(R.string.ExportFragment_warning_this_will_export_the_contents_of_your_messages_to_storage_in_plaintext));
    builder.setPositiveButton(activity.getString(R.string.ExportFragment_export), (dialog, which) -> {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        launchPlaintextBackupSaver();
        return;
      }

      String[] permissions = getWriteStoragePermissions();

      if (permissions.length == 0) {
        startExportPlaintextBackup(null);
      } else {
        Permissions.with(ImportExportFragment.this, permissionLauncher)
                   .request(permissions)
                   .ifNecessary()
                   .withPermanentDenialDialog(getString(R.string.ImportExportFragment_silence_needs_the_storage_permission_in_order_to_write_to_external_storage_but_it_has_been_permanently_denied))
                   .onAllGranted(() -> startExportPlaintextBackup(null))
                   .onAnyDenied(() -> showToast(R.string.ImportExportFragment_silence_needs_the_storage_permission_in_order_to_write_to_external_storage))
                   .execute();
      }
    });
    builder.setNegativeButton(activity.getString(R.string.ExportFragment_cancel), null);
    builder.show();
  }

  private String[] getReadStoragePermissions() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      return new String[0];
    }

    return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
  }

  private String[] getWriteStoragePermissions() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      return getReadStoragePermissions();
    }

    Set<String> permissions = new LinkedHashSet<>();
    permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
    for (String permission : getReadStoragePermissions()) {
      permissions.add(permission);
    }

    return permissions.toArray(new String[0]);
  }

  private void handlePlaintextBackupDocument(@Nullable Uri uri) {
    if (uri == null) return;

    Context context = getContext();
    if (!isAdded() || context == null) return;

    try {
      context.getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
    } catch (SecurityException e) {
      Log.w(TAG, "Unable to persist uri permission", e);
    }

    startImportPlaintextBackup(uri);
  }

  private void handlePlaintextBackupDestination(@Nullable Uri uri) {
    if (uri == null) return;

    startExportPlaintextBackup(uri);
  }

  private void startImportPlaintextBackup(@Nullable Uri importUri) {
    Activity activity = getActivity();
    if (!isAdded() || activity == null) return;

    final Context      context              = activity.getApplicationContext();
    final MasterSecret masterSecretSnapshot = masterSecret;
    final Uri          importUriSnapshot    = importUri;

    startOperation(R.string.ImportFragment_importing,
                   R.string.ImportFragment_import_plaintext_backup_elipse,
                   () -> {
      try {
        if (importUriSnapshot != null) {
          PlaintextBackupImporter.importPlaintextFromUri(context, masterSecretSnapshot, importUriSnapshot);
        } else {
          PlaintextBackupImporter.importPlaintextFromSd(context, masterSecretSnapshot);
        }
        return SUCCESS;
      } catch (NoExternalStorageException e) {
        Log.w(TAG, "No plaintext backup available", e);
        return NO_SD_CARD;
      } catch (IOException e) {
        Log.w(TAG, "Unable to import plaintext backup", e);
        return ERROR_IO;
      }
                   },
                   this::handleImportPlaintextResult,
                   "Unexpected failure importing plaintext backup");
  }

  private void startExportPlaintextBackup(@Nullable Uri exportUri) {
    Activity activity = getActivity();
    if (!isAdded() || activity == null) return;

    final Context      context              = activity.getApplicationContext();
    final MasterSecret masterSecretSnapshot = masterSecret;
    final Uri          exportUriSnapshot    = exportUri;

    startOperation(R.string.ExportFragment_exporting,
                   R.string.ExportFragment_exporting_plaintext_to_storage,
                   () -> {
      try {
        if (exportUriSnapshot != null) {
          PlaintextBackupExporter.exportPlaintextToUri(context, masterSecretSnapshot, exportUriSnapshot);
        } else {
          PlaintextBackupExporter.exportPlaintextToSd(context, masterSecretSnapshot);
        }
        return SUCCESS;
      } catch (NoExternalStorageException e) {
        Log.w(TAG, "Unable to access storage for plaintext export", e);
        return NO_SD_CARD;
      } catch (IOException e) {
        Log.w(TAG, "Unable to export plaintext backup", e);
        return ERROR_IO;
      }
                   },
                   this::handleExportResult,
                   "Unexpected failure exporting plaintext backup");
  }

  private void startImportEncryptedBackup() {
    Activity activity = getActivity();
    if (!isAdded() || activity == null) return;

    final Context context = activity.getApplicationContext();

    startOperation(R.string.ImportFragment_importing,
                   R.string.ImportFragment_restoring_encrypted_backup,
                   () -> {
      try {
        EncryptedBackupExporter.importFromStorage(context);
        return SUCCESS;
      } catch (NoExternalStorageException e) {
        Log.w(TAG, "No encrypted backup available", e);
        return NO_SD_CARD;
      } catch (IOException e) {
        Log.w(TAG, "Unable to import encrypted backup", e);
        return ERROR_IO;
      }
                   },
                   this::handleImportEncryptedResult,
                   "Unexpected failure importing encrypted backup");
  }

  private void startExportEncryptedBackup() {
    Activity activity = getActivity();
    if (!isAdded() || activity == null) return;

    final Context context = activity.getApplicationContext();

    startOperation(R.string.ExportFragment_exporting,
                   R.string.ExportFragment_exporting_keys_settings_and_messages,
                   () -> {
      try {
        EncryptedBackupExporter.exportToStorage(context);
        return SUCCESS;
      } catch (NoExternalStorageException e) {
        Log.w(TAG, "Unable to access storage for encrypted export", e);
        return NO_SD_CARD;
      } catch (IOException e) {
        Log.w(TAG, "Unable to export encrypted backup", e);
        return ERROR_IO;
      }
                   },
                   this::handleExportResult,
                   "Unexpected failure exporting encrypted backup");
  }

  private void startOperation(int titleResource,
                              int messageResource,
                              Callable<Integer> work,
                              OperationResultHandler resultHandler,
                              String failureMessage)
  {
    Activity activity = getActivity();
    if (!isAdded() || activity == null) return;

    cancelCurrentOperation();
    final int generation = operationGeneration;
    showProgressDialog(activity, titleResource, messageResource);

    currentTask = AppTaskExecutor.getInstance().submitParallel(
        work,
        result -> completeOperation(generation, result, resultHandler),
        exception -> {
          Log.w(TAG, failureMessage, exception);
          completeOperation(generation, ERROR_IO, resultHandler);
        });
  }

  private void completeOperation(int generation, int result, OperationResultHandler resultHandler) {
    if (generation != operationGeneration || !isAdded()) return;

    Activity activity = getActivity();
    if (activity == null) return;

    currentTask = null;
    operationGeneration++;
    dismissProgressDialog();
    resultHandler.onResult(activity, result);
  }

  private void cancelCurrentOperation() {
    operationGeneration++;
    if (currentTask != null) currentTask.cancel();
    currentTask = null;
    dismissProgressDialog();
  }

  private void showProgressDialog(Activity activity, int titleResource, int messageResource) {
    progressDialog = new AlertDialog.Builder(activity)
        .setTitle(titleResource)
        .setMessage(messageResource)
        .setView(new ProgressBar(activity))
        .setCancelable(false)
        .create();
    progressDialog.show();
  }

  private void dismissProgressDialog() {
    if (progressDialog != null) progressDialog.dismiss();
    progressDialog = null;
  }

  private void handleImportPlaintextResult(Activity activity, int result) {
    switch (result) {
      case NO_SD_CARD:
        Toast.makeText(activity, R.string.ImportFragment_no_plaintext_backup_found, Toast.LENGTH_LONG).show();
        break;
      case ERROR_IO:
        Toast.makeText(activity, R.string.ImportFragment_error_importing_backup, Toast.LENGTH_LONG).show();
        break;
      case SUCCESS:
        Toast.makeText(activity, R.string.ImportFragment_import_complete, Toast.LENGTH_LONG).show();
        break;
    }
  }

  private void handleImportEncryptedResult(Activity activity, int result) {
    switch (result) {
      case NO_SD_CARD:
        Toast.makeText(activity, R.string.ImportFragment_no_encrypted_backup_found, Toast.LENGTH_LONG).show();
        break;
      case ERROR_IO:
        Toast.makeText(activity, R.string.ImportFragment_error_importing_backup, Toast.LENGTH_LONG).show();
        break;
      case SUCCESS:
        ExitActivity.exitAndRemoveFromRecentApps(activity);
        break;
    }
  }

  private void handleExportResult(Activity activity, int result) {
    switch (result) {
      case NO_SD_CARD:
        Toast.makeText(activity, R.string.ExportFragment_error_unable_to_write_to_storage, Toast.LENGTH_LONG).show();
        break;
      case ERROR_IO:
        Toast.makeText(activity, R.string.ExportFragment_error_while_writing_to_storage, Toast.LENGTH_LONG).show();
        break;
      case SUCCESS:
        Toast.makeText(activity, R.string.ExportFragment_export_successful, Toast.LENGTH_LONG).show();
        break;
    }
  }

  private void showToast(int messageResource) {
    if (!isAdded()) return;
    Context context = getContext();
    if (context != null) Toast.makeText(context, messageResource, Toast.LENGTH_LONG).show();
  }

  private interface OperationResultHandler {
    void onResult(Activity activity, int result);
  }

}
