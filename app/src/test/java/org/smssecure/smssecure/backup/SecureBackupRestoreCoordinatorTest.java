package org.smssecure.smssecure.backup;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.smssecure.smssecure.crypto.MasterSecret;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SecureBackupRestoreCoordinatorTest {
  @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void verificationFailureDoesNotInstallOrCommit() throws Exception {
    RecordingOperations operations = new RecordingOperations();
    operations.failVerification = true;
    assertRestoreFails(operations);
    assertFalse(operations.prepared);
    assertFalse(operations.committed);
  }

  @Test
  public void deviceBindingFailureDoesNotCommit() throws Exception {
    RecordingOperations operations = new RecordingOperations();
    operations.failInstall = true;
    assertRestoreFails(operations);
    assertTrue(operations.verified);
    assertFalse(operations.committed);
  }

  @Test
  public void commitRunsAfterVerificationAndDeviceBinding() throws Exception {
    RecordingOperations operations = new RecordingOperations();
    File staging = restore(operations);
    assertTrue(operations.verified);
    assertTrue(operations.prepared);
    assertTrue(operations.committed);
    assertTrue(staging.exists());
  }

  @Test
  public void commitFailureAbortsPreparedDeviceProtection() throws Exception {
    RecordingOperations operations = new RecordingOperations();
    operations.failCommit = true;

    assertRestoreFails(operations);

    assertTrue(operations.prepared);
    assertTrue(operations.commitAttempted);
    assertFalse(operations.committed);
    assertTrue(operations.aborted);
  }

  private void assertRestoreFails(RecordingOperations operations) throws Exception {
    File staging = new File(temporaryFolder.getRoot(), "staging-" + System.nanoTime());
    try {
      restore(staging, operations);
      fail("Expected restore failure");
    } catch (IOException expected) {
      assertFalse(staging.exists());
    }
  }

  private File restore(RecordingOperations operations) throws Exception {
    File staging = new File(temporaryFolder.getRoot(), "staging-" + System.nanoTime());
    restore(staging, operations);
    return staging;
  }

  private void restore(File staging, RecordingOperations operations) throws Exception {
    File root = temporaryFolder.newFolder("source-" + System.nanoTime());
    File data = new File(root, "files/data");
    assertTrue(data.getParentFile().mkdirs());
    try (FileOutputStream output = new FileOutputStream(data)) {
      output.write(1);
    }
    byte[] key = BackupRecoveryKey.generate();
    byte[] wrapper = new byte[] {1, 2, 3};
    ByteArrayOutputStream archive = new ByteArrayOutputStream();
    SecureBackupArchive.write(archive, root, masterSecret(), wrapper, key, 1);
    SecureBackupRestoreCoordinator.restore(new ByteArrayInputStream(archive.toByteArray()),
                                           staging, key, operations);
  }

  private MasterSecret masterSecret() {
    return new MasterSecret(new SecretKeySpec(new byte[16], "AES"),
                            new SecretKeySpec(new byte[20], "HmacSHA1"));
  }

  private static final class RecordingOperations
      implements SecureBackupRestoreCoordinator.Operations {
    boolean failVerification;
    boolean failInstall;
    boolean failCommit;
    boolean verified;
    boolean prepared;
    boolean commitAttempted;
    boolean committed;
    boolean aborted;

    @Override
    public void verify(MasterSecret masterSecret, byte[] argon2Wrapper) throws Exception {
      verified = true;
      if (failVerification) throw new Exception("verification failed");
    }

    @Override
    public Object prepareDeviceProtection(byte[] argon2Wrapper) throws Exception {
      prepared = true;
      if (failInstall) throw new Exception("install failed");
      return new Object();
    }

    @Override
    public void commit(File stagingDirectory, Object candidate) throws Exception {
      commitAttempted = true;
      if (failCommit) throw new Exception("commit failed");
      committed = true;
    }

    @Override
    public void abortDeviceProtection(Object candidate) {
      aborted = true;
    }
  }
}