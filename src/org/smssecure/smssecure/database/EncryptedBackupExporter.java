/**
 * Copyright (C) 2011 Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.smssecure.smssecure.database;

import android.content.Context;

import org.smssecure.smssecure.backup.RestoredPreferences;
import org.smssecure.smssecure.backup.SecureBackupArchive;
import org.smssecure.smssecure.backup.SecureBackupRestoreCoordinator;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.crypto.MasterSecretUtil;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class EncryptedBackupExporter {

  public static void exportToStorage(Context context) throws NoExternalStorageException, IOException {
    throw new IOException("Secure backup export requires an OutputStream, recovery key, and MasterSecret");
  }

  public static void importFromStorage(Context context) throws NoExternalStorageException, IOException {
    throw new IOException("Secure backup restore requires an InputStream and recovery key");
  }

  public static void exportToStream(Context context, MasterSecret masterSecret, byte[] recoveryKey,
                                    OutputStream output)
      throws IOException
  {
    byte[] argon2Wrapper;
    try {
      argon2Wrapper = MasterSecretUtil.getPortableArgon2Wrapper(context);
    } catch (java.security.GeneralSecurityException error) {
      throw new IOException("Unable to read portable master-secret wrapper", error);
    }
    try {
      DatabaseFactory.getInstance(context).runWithClosedDatabase(context,
          () -> SecureBackupArchive.write(output, appRoot(context), masterSecret, argon2Wrapper,
                                          recoveryKey, System.currentTimeMillis()));
    } finally {
      java.util.Arrays.fill(argon2Wrapper, (byte) 0);
    }
  }

  public static MasterSecret importFromStream(Context context, byte[] recoveryKey, InputStream input)
      throws IOException
  {
    File stagingDirectory = new File(context.getCacheDir(), "secure-backup-restore");
    File root = appRoot(context);
    return SecureBackupRestoreCoordinator.restore(input, stagingDirectory, recoveryKey,
        new SecureBackupRestoreCoordinator.Operations() {
          @Override
          public void verify(MasterSecret masterSecret, byte[] argon2Wrapper) throws Exception {
            if (argon2Wrapper.length == 0) throw new IOException("Argon2 wrapper is empty");
          }

          @Override
          public Object prepareDeviceProtection(byte[] argon2Wrapper) throws Exception {
            return MasterSecretUtil.prepareRestoredDeviceProtection(context, argon2Wrapper);
          }

          @Override
          public void commit(File staged, Object prepared) throws Exception {
            MasterSecretUtil.RestoredDeviceProtection candidate =
                (MasterSecretUtil.RestoredDeviceProtection) prepared;
            RestoredPreferences.Snapshot previousPreferences =
                RestoredPreferences.captureMainPreferences(context);
            DatabaseFactory.getInstance(context).runWithClosedDatabase(context, () -> {
              SecureBackupArchive.RestoreTransaction transaction =
                  SecureBackupArchive.beginStaging(staged, root);
              try {
                RestoredPreferences.resyncMainPreferences(context);
                MasterSecretUtil.activateRestoredDeviceProtection(context, candidate);
                transaction.commit();
              } catch (Exception error) {
                try {
                  transaction.rollback();
                } catch (IOException rollbackError) {
                  error.addSuppressed(rollbackError);
                }
                try {
                  previousPreferences.restore();
                } catch (IOException preferenceError) {
                  error.addSuppressed(preferenceError);
                }
                if (error instanceof IOException) throw (IOException) error;
                throw new IOException("Unable to commit secure backup restore", error);
              }
            });
            try {
              MasterSecretUtil.finalizeRestoredDeviceProtection(context, candidate);
            } catch (java.security.GeneralSecurityException error) {
              android.util.Log.w("EncryptedBackupExporter",
                                 "Restore committed; deferred device-key cleanup", error);
              try {
                MasterSecretUtil.reconcileDeviceProtection(context);
              } catch (java.security.GeneralSecurityException cleanupError) {
                error.addSuppressed(cleanupError);
              }
            }
            try {
              org.smssecure.smssecure.backup.AutomaticBackupManager.disable(context);
            } catch (RuntimeException error) {
              android.util.Log.w("EncryptedBackupExporter",
                                 "Restore committed; unable to disable prior backup schedule", error);
            }
          }

          @Override
          public void abortDeviceProtection(Object candidate) throws Exception {
            MasterSecretUtil.abortRestoredDeviceProtection(
              context, (MasterSecretUtil.RestoredDeviceProtection) candidate);
          }
        });
  }

  private static File appRoot(Context context) throws IOException {
    File filesDirectory = context.getFilesDir();
    File root = filesDirectory == null ? null : filesDirectory.getParentFile();
    if (root == null || !root.isDirectory()) throw new IOException("App data root is unavailable");
    return root;
  }
}
