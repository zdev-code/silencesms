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
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SecureBackupArchiveTest {
  @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void archiveRoundTripsDurableStateAndMasterSecret() throws Exception {
    File appRoot = temporaryFolder.newFolder("app");
    write(appRoot, "databases/messages.db", "messages");
    write(appRoot, "shared_prefs/settings.xml", "settings");
        write(appRoot, "shared_prefs/SecureSMS-Preferences.xml",
          "<map><string name=\"master_secret_v2\">raw-wrapper</string>" +
          "<string name=\"master_secret\">legacy-wrapper</string>" +
          "<string name=\"encryption_salt\">salt</string>" +
          "<string name=\"mac_salt\">macsalt</string>" +
          "<int name=\"passphrase_iterations\" value=\"100\" />" +
          "<boolean name=\"kept\" value=\"true\" /></map>");
    write(appRoot, "files/attachments/image", "attachment");
    write(appRoot, "cache/transient", "cache");
    write(appRoot, "code_cache/generated", "code");
    write(appRoot, "lib/native.so", "native");
    write(appRoot, "no_backup/device", "device");
    write(appRoot, "shared_prefs/SecureSMS-Device-Preferences.xml", "wrapper");

    byte[] key = BackupRecoveryKey.generate();
    MasterSecret masterSecret = masterSecret();
    byte[] archive = archive(appRoot, masterSecret, key);
    File staging = new File(temporaryFolder.getRoot(), "staging");

    SecureBackupArchive.RestoreResult restored = SecureBackupArchive.readToStaging(
        new ByteArrayInputStream(archive), staging, key);

    assertEquals("messages", read(staging, "databases/messages.db"));
    assertEquals("settings", read(staging, "shared_prefs/settings.xml"));
    String restoredPreferences = read(staging, "shared_prefs/SecureSMS-Preferences.xml");
    assertFalse(restoredPreferences.contains("master_secret_v2"));
    assertFalse(restoredPreferences.contains("name=\"master_secret\""));
    assertFalse(restoredPreferences.contains("encryption_salt"));
    assertFalse(restoredPreferences.contains("mac_salt"));
    assertFalse(restoredPreferences.contains("passphrase_iterations"));
    assertTrue(restoredPreferences.contains("name=\"kept\""));
    assertEquals("attachment", read(staging, "files/attachments/image"));
    assertFalse(new File(staging, "cache").exists());
    assertFalse(new File(staging, "code_cache").exists());
    assertFalse(new File(staging, "lib").exists());
    assertFalse(new File(staging, "no_backup").exists());
    assertFalse(new File(staging, "shared_prefs/SecureSMS-Device-Preferences.xml").exists());
    assertArrayEquals(masterSecret.getEncryptionKey().getEncoded(),
                      restored.getMasterSecret().getEncryptionKey().getEncoded());
    assertArrayEquals(masterSecret.getMacKey().getEncoded(),
                      restored.getMasterSecret().getMacKey().getEncoded());
  }

  @Test
  public void wrongKeyAndTamperingLeaveNoStagingFiles() throws Exception {
    File appRoot = temporaryFolder.newFolder("source");
    write(appRoot, "databases/messages.db", "messages");
    byte[] key = BackupRecoveryKey.generate();
    byte[] archive = archive(appRoot, masterSecret(), key);

    File wrongKeyStaging = new File(temporaryFolder.getRoot(), "wrong-key");
    assertRejected(archive, BackupRecoveryKey.generate(), wrongKeyStaging);
    assertFalse(wrongKeyStaging.exists());

    archive[archive.length - 1] ^= 1;
    File tamperedStaging = new File(temporaryFolder.getRoot(), "tampered");
    assertRejected(archive, key, tamperedStaging);
    assertFalse(tamperedStaging.exists());
  }

  @Test
  public void tamperingOutsideTheZipEntryStreamIsRejected() throws Exception {
    File appRoot = temporaryFolder.newFolder("large-source");
    // Incompressible, so the zip parser genuinely stops before the end of the ciphertext.
    java.util.Random random = new java.util.Random(1);
    StringBuilder body = new StringBuilder();
    for (int i = 0; i < 64 * 1024; i++) {
      body.append((char) ('!' + random.nextInt(90)));
    }
    write(appRoot, "databases/messages.db", body.toString());

    byte[] key = BackupRecoveryKey.generate();
    byte[] archive = archive(appRoot, masterSecret(), key);
    // The zip central directory sits between the last entry and the GCM tag, and ZipInputStream
    // stops before reading it, so only whole-container authentication covers this region.
    archive[archive.length - 40] ^= 1;

    File staging = new File(temporaryFolder.getRoot(), "body-tampered");
    assertRejected(archive, key, staging);
    assertFalse(staging.exists());
  }

  @Test
  public void zipSlipEntryIsRejected() throws Exception {
    byte[] key = BackupRecoveryKey.generate();
    ByteArrayOutputStream archive = new ByteArrayOutputStream();
    try (OutputStream encrypted = SecureBackupContainer.encrypt(archive, key);
         ZipOutputStream zip = new ZipOutputStream(encrypted)) {
      zip.putNextEntry(new ZipEntry("manifest.properties"));
      zip.write("formatVersion=1\ncreatedAtMillis=1\n".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry("files/../escaped"));
      zip.write("escaped".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }

    File staging = new File(temporaryFolder.getRoot(), "zip-slip");
    assertRejected(archive.toByteArray(), key, staging);
    assertFalse(new File(temporaryFolder.getRoot(), "escaped").exists());
    assertFalse(staging.exists());
  }

  @Test
  public void commitOccursOnlyWhenExplicitlyRequested() throws Exception {
    File source = temporaryFolder.newFolder("commit-source");
    write(source, "databases/messages.db", "restored");
    File destination = temporaryFolder.newFolder("commit-destination");
    write(destination, "databases/messages.db", "current");
    byte[] key = BackupRecoveryKey.generate();
    File staging = new File(temporaryFolder.getRoot(), "commit-staging");

    SecureBackupArchive.RestoreResult restored = SecureBackupArchive.readToStaging(
        new ByteArrayInputStream(archive(source, masterSecret(), key)), staging, key);
    assertEquals("current", read(destination, "databases/messages.db"));

    SecureBackupArchive.commitStaging(restored.getStagingDirectory(), destination);
    assertEquals("restored", read(destination, "databases/messages.db"));
  }

  @Test
  public void commitReplacesManagedStateInsteadOfMergingOverIt() throws Exception {
    File source = temporaryFolder.newFolder("replace-source");
    write(source, "databases/messages.db", "restored");
    write(source, "files/sessions-v2/1", "old-session");

    File destination = temporaryFolder.newFolder("replace-destination");
    write(destination, "databases/messages.db", "current");
    write(destination, "databases/messages.db-wal", "stale-journal");
    write(destination, "files/sessions-v2/1", "current-session");
    write(destination, "files/sessions-v2/2", "newer-session");
    write(destination, "files/prekeys/7", "consumed-prekey");
    write(destination, "app_parts/part1.mms", "orphan-attachment");
    write(destination, "cache/transient", "cache");
    write(destination, "shared_prefs/SecureSMS-Device-Preferences.xml", "device-bound");

    byte[] key = BackupRecoveryKey.generate();
    File staging = new File(temporaryFolder.getRoot(), "replace-staging");
    SecureBackupArchive.RestoreResult restored = SecureBackupArchive.readToStaging(
        new ByteArrayInputStream(archive(source, masterSecret(), key)), staging, key);
    SecureBackupArchive.commitStaging(restored.getStagingDirectory(), destination);

    assertEquals("restored", read(destination, "databases/messages.db"));
    assertEquals("old-session", read(destination, "files/sessions-v2/1"));
    assertFalse(new File(destination, "databases/messages.db-wal").exists());
    assertFalse(new File(destination, "files/sessions-v2/2").exists());
    assertFalse(new File(destination, "files/prekeys").exists());
    assertFalse(new File(destination, "app_parts").exists());
    // Excluded from the archive, so it has to survive the directory swap.
    assertEquals("device-bound", read(destination, "shared_prefs/SecureSMS-Device-Preferences.xml"));
    assertEquals("cache", read(destination, "cache/transient"));
  }

  @Test
  public void commitInstallsAPreferenceReplicaForTheCacheResync() throws Exception {
    File source = temporaryFolder.newFolder("replica-source");
    write(source, "shared_prefs/SecureSMS-Preferences.xml",
          "<map><boolean name=\"kept\" value=\"true\" /></map>");
    File destination = temporaryFolder.newFolder("replica-destination");

    byte[] key = BackupRecoveryKey.generate();
    File staging = new File(temporaryFolder.getRoot(), "replica-staging");
    SecureBackupArchive.RestoreResult restored = SecureBackupArchive.readToStaging(
        new ByteArrayInputStream(archive(source, masterSecret(), key)), staging, key);
    SecureBackupArchive.commitStaging(restored.getStagingDirectory(), destination);

    String replica = "shared_prefs/" + SecureBackupArchive.RESTORED_PREFERENCES_NAME + ".xml";
    assertEquals(read(destination, "shared_prefs/SecureSMS-Preferences.xml"),
                 read(destination, replica));

    // The replica is process-local scratch, never archived and never left behind for a later run.
    File reexportStaging = new File(temporaryFolder.getRoot(), "replica-reexport-staging");
    SecureBackupArchive.readToStaging(
        new ByteArrayInputStream(archive(destination, masterSecret(), key)), reexportStaging, key);
    assertFalse(new File(reexportStaging, replica).exists());
    assertTrue(new File(reexportStaging, "shared_prefs/SecureSMS-Preferences.xml").exists());

    SecureBackupArchive.recoverInterruptedRestore(destination);
    assertFalse(new File(destination, replica).exists());
  }

  @Test
  public void interruptedCommitIsRolledBackOnNextStart() throws Exception {
    File appRoot = temporaryFolder.newFolder("interrupted");
    write(appRoot, "databases/messages.db", "original");
    write(appRoot, "files/sessions-v2/1", "original-session");
    File staging = temporaryFolder.newFolder("interrupted-staging");
    write(staging, "databases/messages.db", "half-restored");
    write(staging, "app_parts/new.mms", "new-only-state");

    SecureBackupArchive.beginStaging(staging, appRoot);

    SecureBackupArchive.recoverInterruptedRestore(appRoot);

    assertEquals("original", read(appRoot, "databases/messages.db"));
    assertEquals("original-session", read(appRoot, "files/sessions-v2/1"));
    assertFalse(new File(appRoot, "app_parts").exists());
    assertFalse(new File(appRoot, "databases.restore-replaced").exists());
    assertFalse(new File(appRoot, "no_backup/silence-restore-swap").exists());
  }

  @Test
  public void leftoverReplacedDirectoriesAreDiscardedAfterTheCommitPoint() throws Exception {
    File appRoot = temporaryFolder.newFolder("committed");
    write(appRoot, "databases/messages.db", "superseded");
    File staging = temporaryFolder.newFolder("committed-staging");
    write(staging, "databases/messages.db", "restored");
    SecureBackupArchive.RestoreTransaction transaction =
        SecureBackupArchive.beginStaging(staging, appRoot);
    transaction.markCommitted();

    SecureBackupArchive.recoverInterruptedRestore(appRoot);

    assertEquals("restored", read(appRoot, "databases/messages.db"));
    assertFalse(new File(appRoot, "databases.restore-replaced").exists());
  }

  @Test
  public void oversizedControlEntryIsRejectedBeforeItIsBuffered() throws Exception {
    byte[] key = BackupRecoveryKey.generate();
    ByteArrayOutputStream archive = new ByteArrayOutputStream();
    byte[] filler = new byte[1024 * 1024];
    try (OutputStream encrypted = SecureBackupContainer.encrypt(archive, key);
         ZipOutputStream zip = new ZipOutputStream(encrypted)) {
      zip.putNextEntry(new ZipEntry("manifest.properties"));
      for (int i = 0; i < 64; i++) zip.write(filler);
      zip.closeEntry();
    }

    File staging = new File(temporaryFolder.getRoot(), "oversized");
    assertRejected(archive.toByteArray(), key, staging);
    assertFalse(staging.exists());
  }

  private byte[] archive(File root, MasterSecret masterSecret, byte[] key) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    SecureBackupArchive.write(output, root, masterSecret, sequence(48, 37), key, 123456789L);
    return output.toByteArray();
  }

  private MasterSecret masterSecret() {
    return new MasterSecret(new SecretKeySpec(sequence(16, 1), "AES"),
                            new SecretKeySpec(sequence(20, 17), "HmacSHA1"));
  }

  private byte[] sequence(int length, int first) {
    byte[] value = new byte[length];
    for (int index = 0; index < length; index++) value[index] = (byte) (first + index);
    return value;
  }

  private void assertRejected(byte[] archive, byte[] key, File staging) throws Exception {
    try {
      SecureBackupArchive.readToStaging(new ByteArrayInputStream(archive), staging, key);
      fail("Expected backup to be rejected");
    } catch (IOException expected) {
      assertTrue(expected.getMessage() != null);
    }
  }

  private void write(File root, String relativePath, String value) throws Exception {
    File file = new File(root, relativePath);
    File parent = file.getParentFile();
    assertTrue(parent.mkdirs() || parent.isDirectory());
    try (FileOutputStream output = new FileOutputStream(file)) {
      output.write(value.getBytes(StandardCharsets.UTF_8));
    }
  }

  private String read(File root, String relativePath) throws Exception {
    return new String(Files.readAllBytes(new File(root, relativePath).toPath()),
                      StandardCharsets.UTF_8);
  }
}