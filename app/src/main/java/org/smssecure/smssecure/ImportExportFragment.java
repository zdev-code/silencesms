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
import androidx.lifecycle.ViewModelProvider;
import androidx.appcompat.app.AlertDialog;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import org.smssecure.smssecure.backup.BackupRecoveryKey;
import org.smssecure.smssecure.backup.AutomaticBackupManager;
import org.smssecure.smssecure.database.EncryptedBackupExporter;
import org.smssecure.smssecure.database.NoExternalStorageException;
import org.smssecure.smssecure.database.PlaintextBackupExporter;
import org.smssecure.smssecure.database.PlaintextBackupImporter;
import org.smssecure.smssecure.domain.conversation.ConversationUnlockCapability;
import org.smssecure.smssecure.domain.security.UnlockSession;
import org.smssecure.smssecure.permissions.Permissions;
import org.smssecure.smssecure.service.ApplicationMigrationService;
import org.smssecure.smssecure.ui.LifecycleStateCollector;
import org.smssecure.smssecure.ui.importexport.ImportExportUiState;
import org.smssecure.smssecure.ui.importexport.ImportExportViewModel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Callable;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class ImportExportFragment extends Fragment {

  @SuppressWarnings("unused")
  private static final String TAG = ImportExportFragment.class.getSimpleName();

  private static final int SUCCESS                             = 0;
  private static final int NO_SD_CARD                          = 1;
  private static final int ERROR_IO                            = 2;
  private static final int ALREADY_IMPORTED                    = 3;
  private static final String[] PLAINTEXT_BACKUP_MIME_TYPES    = new String[] {
      "application/xml",
      "text/xml",
      "text/plain",
      "application/octet-stream"
  };

  private final Permissions.FragmentPermissionLauncher permissionLauncher = Permissions.registerForResult(this);
  private final ActivityResultLauncher<String[]> plaintextBackupPicker =
      registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::handlePlaintextBackupDocument);
  private final ActivityResultLauncher<String> plaintextBackupSaver =
      registerForActivityResult(new ActivityResultContracts.CreateDocument("application/xml"),
                                this::handlePlaintextBackupDestination);
                  private final ActivityResultLauncher<String[]> encryptedBackupPicker =
                    registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::handleEncryptedBackupDocument);
                  private final ActivityResultLauncher<String> encryptedBackupSaver =
                    registerForActivityResult(new ActivityResultContracts.CreateDocument("application/octet-stream"),
                                this::handleEncryptedBackupDestination);
  private final ActivityResultLauncher<Uri> automaticBackupDirectoryPicker =
      registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(),
                                this::handleAutomaticBackupDirectory);
  private byte[]                     pendingRecoveryKey;
  private UnlockSession              pendingPlaintextImportSession;
  private UnlockSession              pendingPlaintextExportSession;
  private UnlockSession              pendingEncryptedImportSession;
  private UnlockSession              pendingEncryptedExportSession;
  private UnlockSession              pendingAutomaticBackupSession;
  private AlertDialog                sensitiveDialog;
  private AlertDialog                 progressDialog;
  private ImportExportViewModel       viewModel;

  @Override
  public void onCreate(Bundle bundle) {
    super.onCreate(bundle);
    viewModel = new ViewModelProvider(this).get(ImportExportViewModel.class);
    LifecycleStateCollector.collect(this, viewModel.getState(), this::renderOperation);
  }

  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle bundle) {
    View layout              = inflater.inflate(R.layout.import_export_fragment, container, false);
    View importSmsView       = layout.findViewById(R.id.import_sms             );
    View importEncryptedView = layout.findViewById(R.id.import_encrypted_backup);
    View importPlaintextView = layout.findViewById(R.id.import_plaintext_backup);
    View exportEncryptedView = layout.findViewById(R.id.export_encrypted_backup);
    View exportPlaintextView = layout.findViewById(R.id.export_plaintext_backup);
    View automaticBackupView = layout.findViewById(R.id.automatic_encrypted_backup);
    TextView automaticBackupTitle = layout.findViewById(R.id.automatic_encrypted_backup_title);

    importSmsView.setOnClickListener(v -> handleImportSms());
    importEncryptedView.setOnClickListener(v -> handleImportEncryptedBackup());
    importPlaintextView.setOnClickListener(v -> handleImportPlaintextBackup());
    exportEncryptedView.setOnClickListener(v -> handleExportEncryptedBackup());
    exportPlaintextView.setOnClickListener(v -> handleExportPlaintextBackup());
    automaticBackupTitle.setText(AutomaticBackupManager.isEnabled(requireContext())
      ? R.string.AutomaticBackup_disable : R.string.AutomaticBackup_enable);
    automaticBackupView.setOnClickListener(v -> handleAutomaticBackup());

    return layout;
  }

  @Override
  public void onDestroyView() {
    clearSensitiveState();
    super.onDestroyView();
  }

  public void clearSensitiveState() {
    pendingPlaintextImportSession = null;
    pendingPlaintextExportSession = null;
    pendingEncryptedImportSession = null;
    pendingEncryptedExportSession = null;
    pendingAutomaticBackupSession = null;
    clearPendingRecoveryKey();
    if (sensitiveDialog != null) sensitiveDialog.dismiss();
    sensitiveDialog = null;
    dismissProgressDialog();
    if (viewModel != null) viewModel.clearSensitiveState();
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
      UnlockSession unlockSession = UnlockSession.capture();
      Permissions.with(this, permissionLauncher)
                 .request(Manifest.permission.READ_SMS)
                 .ifNecessary()
                 .withPermanentDenialDialog(getString(R.string.ImportExportFragment_silence_needs_the_sms_permission_in_order_to_import_sms_messages))
                 .onAllGranted(() -> {
                   Activity currentActivity = getActivity();
                   if (!isAdded() || currentActivity == null) return;

                   try {
                     new ConversationUnlockCapability(unlockSession).use(masterSecret -> {
                       currentActivity.startService(ApplicationMigrationService.createMigrationIntent(
                           currentActivity, unlockSession));
                       return null;
                     });
                   } catch (ConversationUnlockCapability.LockedException ignored) {
                     return;
                   } catch (Exception error) {
                     Log.w(TAG, "Unable to start SMS import", error);
                     return;
                   }

                   Intent nextIntent = new Intent(currentActivity, ConversationListActivity.class);
                   Intent activityIntent = AuthenticationActivity.createDatabaseMigrationIntent(
                       currentActivity, nextIntent);
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
      promptForRecoveryKey();
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
      UnlockSession unlockSession = UnlockSession.capture();
      String[] permissions = getReadStoragePermissions();

      Runnable onGranted = () -> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          launchPlaintextBackupPicker(unlockSession);
        } else {
          startImportPlaintextBackup(unlockSession, null);
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

    if (AutomaticBackupManager.hasRecoveryKey(activity)) {
      new AlertDialog.Builder(activity)
          .setTitle(R.string.ExportFragment_choose_recovery_key)
          .setMessage(R.string.ExportFragment_choose_recovery_key_message)
          .setPositiveButton(R.string.ExportFragment_use_automatic_key,
                             (dialog, which) -> exportWithAutomaticRecoveryKey())
          .setNegativeButton(R.string.ExportFragment_use_new_key,
                             (dialog, which) -> showEncryptedExportRecoveryKey(BackupRecoveryKey.generate()))
          .setNeutralButton(R.string.ExportFragment_cancel, null)
          .show();
      return;
    }

    showEncryptedExportRecoveryKey(BackupRecoveryKey.generate());
  }

  private void exportWithAutomaticRecoveryKey() {
    Activity activity = getActivity();
    if (!isAdded() || activity == null) return;

    try {
      showEncryptedExportRecoveryKey(AutomaticBackupManager.getRecoveryKey(activity));
    } catch (IOException | java.security.GeneralSecurityException error) {
      Log.w(TAG, "Unable to read automatic backup recovery key", error);
      showToast(R.string.ExportFragment_automatic_key_unavailable);
    }
  }

  private void showEncryptedExportRecoveryKey(byte[] recoveryKey) {
    Activity activity = getActivity();
    if (!isAdded() || activity == null) {
      Arrays.fill(recoveryKey, (byte) 0);
      return;
    }

    TextView keyView = new TextView(activity);
    keyView.setTextIsSelectable(true);
    keyView.setPadding(48, 24, 48, 24);
    keyView.setText(BackupRecoveryKey.encode(recoveryKey));
    AlertDialog.Builder builder = new AlertDialog.Builder(activity);
    builder.setIconAttribute(R.attr.dialog_info_icon);
    builder.setTitle(activity.getString(R.string.ExportFragment_export_encrypted_backup));
    builder.setMessage(activity.getString(R.string.ExportFragment_store_recovery_key));
    builder.setView(keyView);
    builder.setPositiveButton(activity.getString(R.string.ExportFragment_export), (dialog, which) -> {
      clearPendingRecoveryKey();
      pendingRecoveryKey = Arrays.copyOf(recoveryKey, recoveryKey.length);
      pendingEncryptedExportSession = UnlockSession.capture();
      encryptedBackupSaver.launch("Silence-" + System.currentTimeMillis() + ".silencebackup");
    });
    builder.setNegativeButton(activity.getString(R.string.ExportFragment_cancel), null);
    showSensitiveDialog(builder.create(), recoveryKey, null);
  }

  private void handleAutomaticBackup() {
    Activity activity = getActivity();
    if (!isAdded() || activity == null) return;
    if (AutomaticBackupManager.isEnabled(activity)) {
      new AlertDialog.Builder(activity)
          .setTitle(R.string.AutomaticBackup_disable)
          .setMessage(R.string.AutomaticBackup_disable_message)
          .setPositiveButton(R.string.AutomaticBackup_disable, (dialog, which) -> {
            AutomaticBackupManager.disable(activity);
            View view = getView();
            if (view != null) ((TextView) view.findViewById(R.id.automatic_encrypted_backup_title))
                .setText(R.string.AutomaticBackup_enable);
          })
          .setNegativeButton(R.string.ImportFragment_cancel, null)
          .show();
      return;
    }

    boolean reusingRecoveryKey = AutomaticBackupManager.hasRecoveryKey(activity);
    byte[] recoveryKey;
    try {
      recoveryKey = reusingRecoveryKey
          ? AutomaticBackupManager.getRecoveryKey(activity)
          : BackupRecoveryKey.generate();
    } catch (IOException | java.security.GeneralSecurityException error) {
      Log.w(TAG, "Unable to read automatic backup recovery key", error);
      showToast(R.string.AutomaticBackup_enable_failed);
      return;
    }
    TextView keyView = new TextView(activity);
    keyView.setTextIsSelectable(true);
    keyView.setPadding(48, 24, 48, 24);
    keyView.setText(BackupRecoveryKey.encode(recoveryKey));
    AlertDialog dialog = new AlertDialog.Builder(activity)
        .setTitle(R.string.AutomaticBackup_enable)
        .setMessage(reusingRecoveryKey
              ? R.string.AutomaticBackup_reuse_recovery_key
              : R.string.AutomaticBackup_store_recovery_key)
        .setView(keyView)
        .setPositiveButton(R.string.AutomaticBackup_select_directory, (dialogInterface, which) -> {
          clearPendingRecoveryKey();
          pendingRecoveryKey = Arrays.copyOf(recoveryKey, recoveryKey.length);
          pendingAutomaticBackupSession = UnlockSession.capture();
          automaticBackupDirectoryPicker.launch(null);
        })
        .setNegativeButton(R.string.ExportFragment_cancel, null)
        .create();
    showSensitiveDialog(dialog, recoveryKey, null);
  }

  private void handleAutomaticBackupDirectory(@Nullable Uri uri) {
    UnlockSession unlockSession = pendingAutomaticBackupSession;
    pendingAutomaticBackupSession = null;
    if (uri == null || unlockSession == null) { clearPendingRecoveryKey(); return; }
    Context context = getContext();
    byte[] recoveryKey = takePendingRecoveryKey();
    if (context == null || recoveryKey == null) return;
    try {
      new ConversationUnlockCapability(unlockSession).use(masterSecret -> {
        context.getContentResolver().takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        AutomaticBackupManager.enable(context, uri, recoveryKey);
        return null;
      });
      View view = getView();
      if (view != null) ((TextView) view.findViewById(R.id.automatic_encrypted_backup_title))
          .setText(R.string.AutomaticBackup_disable);
    } catch (ConversationUnlockCapability.LockedException ignored) {
      return;
    } catch (Exception error) {
      Log.w(TAG, "Unable to enable automatic backups", error);
      showToast(R.string.AutomaticBackup_enable_failed);
    } finally {
      Arrays.fill(recoveryKey, (byte) 0);
    }
  }

  private void launchPlaintextBackupPicker(UnlockSession unlockSession) {
    if (!isAdded() || getActivity() == null) return;

    pendingPlaintextImportSession = unlockSession;
    plaintextBackupPicker.launch(PLAINTEXT_BACKUP_MIME_TYPES);
  }

  private void launchPlaintextBackupSaver(UnlockSession unlockSession) {
    if (!isAdded() || getActivity() == null) return;

    pendingPlaintextExportSession = unlockSession;
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
      UnlockSession unlockSession = UnlockSession.capture();
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        launchPlaintextBackupSaver(unlockSession);
        return;
      }

      String[] permissions = getWriteStoragePermissions();

      if (permissions.length == 0) {
        startExportPlaintextBackup(unlockSession, null);
      } else {
        Permissions.with(ImportExportFragment.this, permissionLauncher)
                   .request(permissions)
                   .ifNecessary()
                   .withPermanentDenialDialog(getString(R.string.ImportExportFragment_silence_needs_the_storage_permission_in_order_to_write_to_external_storage_but_it_has_been_permanently_denied))
                   .onAllGranted(() -> startExportPlaintextBackup(unlockSession, null))
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
    UnlockSession unlockSession = pendingPlaintextImportSession;
    pendingPlaintextImportSession = null;
    if (uri == null || unlockSession == null) return;

    Context context = getContext();
    if (!isAdded() || context == null) return;

    try {
      new ConversationUnlockCapability(unlockSession).use(masterSecret -> {
        context.getContentResolver().takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return null;
      });
    } catch (ConversationUnlockCapability.LockedException ignored) {
      return;
    } catch (SecurityException e) {
      Log.w(TAG, "Unable to persist uri permission", e);
    } catch (Exception e) {
      Log.w(TAG, "Unable to validate plaintext import", e);
      return;
    }

    startImportPlaintextBackup(unlockSession, uri);
  }

  private void handlePlaintextBackupDestination(@Nullable Uri uri) {
    UnlockSession unlockSession = pendingPlaintextExportSession;
    pendingPlaintextExportSession = null;
    if (uri == null || unlockSession == null) return;

    startExportPlaintextBackup(unlockSession, uri);
  }

  private void startImportPlaintextBackup(UnlockSession unlockSession, @Nullable Uri importUri) {
    Activity activity = getActivity();
    if (!isAdded() || activity == null) return;

    final Context      context              = activity.getApplicationContext();
    final Uri          importUriSnapshot    = importUri;
    final ConversationUnlockCapability capability = new ConversationUnlockCapability(unlockSession);

    startOperation(R.string.ImportFragment_importing,
                   R.string.ImportFragment_import_plaintext_backup_elipse,
                   () -> {
      try {
        PlaintextBackupImporter.ImportResult result;
        result = capability.use(masterSecret -> importUriSnapshot != null
            ? PlaintextBackupImporter.importPlaintextFromUri(context, masterSecret, importUriSnapshot)
            : PlaintextBackupImporter.importPlaintextFromSd(context, masterSecret));
        return result == PlaintextBackupImporter.ImportResult.IMPORTED ? SUCCESS : ALREADY_IMPORTED;
      } catch (ConversationUnlockCapability.LockedException e) {
        return ERROR_IO;
      } catch (NoExternalStorageException e) {
        Log.w(TAG, "No plaintext backup available", e);
        return NO_SD_CARD;
      } catch (IOException e) {
        Log.w(TAG, "Unable to import plaintext backup", e);
        return ERROR_IO;
      } catch (Exception e) {
        Log.w(TAG, "Unable to run plaintext import", e);
        return ERROR_IO;
      }
                   },
                   ImportExportUiState.Operation.IMPORT_PLAINTEXT);
  }

  private void startExportPlaintextBackup(UnlockSession unlockSession, @Nullable Uri exportUri) {
    Activity activity = getActivity();
    if (!isAdded() || activity == null) return;

    final Context      context              = activity.getApplicationContext();
    final Uri          exportUriSnapshot    = exportUri;
    final ConversationUnlockCapability capability = new ConversationUnlockCapability(unlockSession);

    startOperation(R.string.ExportFragment_exporting,
                   R.string.ExportFragment_exporting_plaintext_to_storage,
                   () -> {
      try {
        capability.use(masterSecret -> {
          if (exportUriSnapshot != null) {
            PlaintextBackupExporter.exportPlaintextToUri(context, masterSecret, exportUriSnapshot);
          } else {
            PlaintextBackupExporter.exportPlaintextToSd(context, masterSecret);
          }
          return null;
        });
        return SUCCESS;
      } catch (ConversationUnlockCapability.LockedException e) {
        return ERROR_IO;
      } catch (NoExternalStorageException e) {
        Log.w(TAG, "Unable to access storage for plaintext export", e);
        return NO_SD_CARD;
      } catch (IOException e) {
        Log.w(TAG, "Unable to export plaintext backup", e);
        return ERROR_IO;
      } catch (Exception e) {
        Log.w(TAG, "Unable to run plaintext export", e);
        return ERROR_IO;
      }
                   },
                   ImportExportUiState.Operation.EXPORT);
  }

  private void promptForRecoveryKey() {
    Activity activity = getActivity();
    if (!isAdded() || activity == null) return;
    EditText input = new EditText(activity);
    input.setSingleLine(false);
    input.setHint(R.string.ImportFragment_recovery_key);
    AlertDialog dialog = new AlertDialog.Builder(activity)
        .setTitle(R.string.ImportFragment_recovery_key)
        .setView(input)
        .setPositiveButton(R.string.ImportFragment_import, (dialogInterface, which) -> {
          try {
            clearPendingRecoveryKey();
            pendingRecoveryKey = BackupRecoveryKey.decode(input.getText().toString());
            pendingEncryptedImportSession = UnlockSession.capture();
            encryptedBackupPicker.launch(new String[] {"application/octet-stream", "*/*"});
          } catch (IOException error) {
            showToast(R.string.ImportFragment_invalid_recovery_key);
          }
        })
        .setNegativeButton(R.string.ImportFragment_cancel, null)
        .create();
      showSensitiveDialog(dialog, null, input);
  }

  private void handleEncryptedBackupDocument(@Nullable Uri uri) {
    UnlockSession unlockSession = pendingEncryptedImportSession;
    pendingEncryptedImportSession = null;
    if (uri == null || unlockSession == null) { clearPendingRecoveryKey(); return; }
    startImportEncryptedBackup(unlockSession, uri);
  }

  private void handleEncryptedBackupDestination(@Nullable Uri uri) {
    UnlockSession unlockSession = pendingEncryptedExportSession;
    pendingEncryptedExportSession = null;
    if (uri == null || unlockSession == null) { clearPendingRecoveryKey(); return; }
    startExportEncryptedBackup(unlockSession, uri);
  }

  private void startImportEncryptedBackup(UnlockSession unlockSession, Uri uri) {
    Activity activity = getActivity();
    if (!isAdded() || activity == null) return;

    final Context context = activity.getApplicationContext();
    final byte[] recoveryKey = takePendingRecoveryKey();
    final ConversationUnlockCapability capability = new ConversationUnlockCapability(unlockSession);
    if (recoveryKey == null) return;

    startOperation(R.string.ImportFragment_importing,
                   R.string.ImportFragment_restoring_encrypted_backup,
                   () -> {
      try {
        capability.use(masterSecret -> {
          try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("Unable to open backup");
            EncryptedBackupExporter.importFromStream(context, recoveryKey, input);
          }
          return null;
        });
        return SUCCESS;
      } catch (ConversationUnlockCapability.LockedException e) {
        return ERROR_IO;
      } catch (IOException e) {
        Log.w(TAG, "Unable to import encrypted backup", e);
        return ERROR_IO;
      } catch (Exception e) {
        Log.w(TAG, "Unable to run encrypted restore", e);
        return ERROR_IO;
      } finally {
        Arrays.fill(recoveryKey, (byte) 0);
      }
                   },
                   ImportExportUiState.Operation.IMPORT_ENCRYPTED);
  }

  private void startExportEncryptedBackup(UnlockSession unlockSession, Uri uri) {
    Activity activity = getActivity();
    if (!isAdded() || activity == null) return;

    final Context context = activity.getApplicationContext();
    final byte[] recoveryKey = takePendingRecoveryKey();
    final ConversationUnlockCapability capability = new ConversationUnlockCapability(unlockSession);
    if (recoveryKey == null) return;

    startOperation(R.string.ExportFragment_exporting,
                   R.string.ExportFragment_exporting_keys_settings_and_messages,
                   () -> {
      try {
        capability.use(masterSecret -> {
          try (OutputStream output = context.getContentResolver().openOutputStream(uri, "w")) {
            if (output == null) throw new IOException("Unable to open backup destination");
            EncryptedBackupExporter.exportToStream(context, masterSecret, recoveryKey, output);
          }
          return null;
        });
        if (!org.smssecure.smssecure.crypto.MasterSecretUtil.isDeviceProtectionEnabled(context)) {
          try {
            org.smssecure.smssecure.crypto.MasterSecretUtil.enableDeviceProtection(context);
          } catch (java.security.GeneralSecurityException error) {
            throw new IOException("Backup was written but device protection could not be enabled", error);
          }
        }
        return SUCCESS;
      } catch (ConversationUnlockCapability.LockedException e) {
        return ERROR_IO;
      } catch (IOException e) {
        Log.w(TAG, "Unable to export encrypted backup", e);
        return ERROR_IO;
      } catch (Exception e) {
        Log.w(TAG, "Unable to run encrypted export", e);
        return ERROR_IO;
      } finally {
        Arrays.fill(recoveryKey, (byte) 0);
      }
                   },
                   ImportExportUiState.Operation.EXPORT);
  }

  private byte[] takePendingRecoveryKey() {
    byte[] key = pendingRecoveryKey;
    pendingRecoveryKey = null;
    return key;
  }

  private void clearPendingRecoveryKey() {
    if (pendingRecoveryKey != null) Arrays.fill(pendingRecoveryKey, (byte) 0);
    pendingRecoveryKey = null;
  }

  private void showSensitiveDialog(AlertDialog dialog, @Nullable byte[] bytes, @Nullable EditText input) {
    if (sensitiveDialog != null) sensitiveDialog.dismiss();
    sensitiveDialog = dialog;
    dialog.setOnDismissListener(ignored -> {
      if (bytes != null) Arrays.fill(bytes, (byte) 0);
      if (input != null) input.getText().clear();
      if (sensitiveDialog == dialog) sensitiveDialog = null;
    });
    dialog.show();
  }

  private void startOperation(int titleResource,
                              int messageResource,
                              Callable<Integer> work,
                              ImportExportUiState.Operation operation)
  {
    viewModel.start(operation, titleResource, messageResource, work);
  }

  private void renderOperation(ImportExportUiState state) {
    Activity activity = getActivity();
    if (activity == null) return;
    if (state.isRunning()) {
      showProgressDialog(activity, state.getTitleResource(), state.getMessageResource());
      return;
    }
    dismissProgressDialog();
    if (state.getResult() == null || state.getCompletedOperation() == null) return;
    switch (state.getCompletedOperation()) {
      case IMPORT_PLAINTEXT: handleImportPlaintextResult(activity, state.getResult()); break;
      case IMPORT_ENCRYPTED: handleImportEncryptedResult(activity, state.getResult()); break;
      case EXPORT: handleExportResult(activity, state.getResult()); break;
    }
    viewModel.acknowledgeResult();
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
      case ALREADY_IMPORTED:
        Toast.makeText(activity, R.string.ImportFragment_backup_already_imported, Toast.LENGTH_LONG).show();
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


}
