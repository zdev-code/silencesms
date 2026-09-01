package org.smssecure.smssecure.database;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class BackupMessageFingerprintTest {

  @Test
  public void identicalMessagesHaveIdenticalFingerprints() {
    assertEquals(fingerprint("+642100000", 1234, 1, 0, null, "hello", null),
                 fingerprint("+642100000", 1234, 1, 0, null, "hello", null));
  }

  @Test
  public void changedStableFieldHasDifferentFingerprint() {
    assertNotEquals(fingerprint("+642100000", 1234, 1, 0, null, "hello", null),
                    fingerprint("+642100000", 1235, 1, 0, null, "hello", null));
  }

  @Test
  public void nullAndEmptyFieldsHaveDifferentFingerprints() {
    assertNotEquals(fingerprint("+642100000", 1234, 1, 0, null, "hello", null),
                    fingerprint("+642100000", 1234, 1, 0, "", "hello", null));
  }

  @Test
  public void backupLiteralNullMatchesStoredNull() {
    XmlBackup.XmlBackupItem item = new XmlBackup.XmlBackupItem(
        0, "+642100000", 1234, 1, "null", "hello", "null", 1, 0);

    assertEquals(fingerprint("+642100000", 1234, 1, 0, null, "hello", null),
                 BackupMessageFingerprint.fromBackup(item));
  }

  private static String fingerprint(String address, long date, int type, int protocol,
                                    String subject, String body, String serviceCenter)
  {
    return BackupMessageFingerprint.create(address, date, type, protocol, subject, body,
                                           serviceCenter);
  }
}