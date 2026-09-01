package org.smssecure.smssecure.backup;

import org.smssecure.smssecure.crypto.MasterSecret;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

public final class SecureBackupRestoreCoordinator {
  public interface Operations {
    void verify(MasterSecret masterSecret, byte[] argon2Wrapper) throws Exception;
    Object prepareDeviceProtection(byte[] argon2Wrapper) throws Exception;
    void commit(File stagingDirectory, Object candidate) throws Exception;
    void abortDeviceProtection(Object candidate) throws Exception;
  }

  private SecureBackupRestoreCoordinator() {}

  public static MasterSecret restore(InputStream input, File stagingDirectory, byte[] recoveryKey,
                                     Operations operations)
      throws IOException
  {
    SecureBackupArchive.RestoreResult restored = null;
    byte[] argon2Wrapper = null;
    boolean committed = false;
    Object candidate = null;
    try {
      restored = SecureBackupArchive.readToStaging(input, stagingDirectory, recoveryKey);
      argon2Wrapper = restored.getArgon2Wrapper();
      operations.verify(restored.getMasterSecret(), argon2Wrapper);
      candidate = operations.prepareDeviceProtection(argon2Wrapper);
      operations.commit(restored.getStagingDirectory(), candidate);
      committed = true;
      return restored.getMasterSecret();
    } catch (Exception error) {
      if (error instanceof IOException) throw (IOException) error;
      throw new IOException("Unable to restore secure backup", error);
    } finally {
      if (argon2Wrapper != null) Arrays.fill(argon2Wrapper, (byte) 0);
      if (candidate != null && !committed) {
        try {
          operations.abortDeviceProtection(candidate);
        } catch (Exception ignored) {}
      }
      if (!committed) SecureBackupArchive.deleteStaging(stagingDirectory);
    }
  }
}