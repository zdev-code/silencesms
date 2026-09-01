package org.smssecure.smssecure.database;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BackupMessageDeduplicatorTest {

  @Test
  public void preservesMultiplicityWithinNewBackup() {
    BackupMessageDeduplicator deduplicator = new BackupMessageDeduplicator();

    assertTrue(deduplicator.shouldImport("same-message"));
    assertTrue(deduplicator.shouldImport("same-message"));
  }

  @Test
  public void skipsOnlyExistingMultiplicity() {
    BackupMessageDeduplicator deduplicator = new BackupMessageDeduplicator();
    deduplicator.addExisting("same-message");
    deduplicator.addExisting("same-message");

    assertFalse(deduplicator.shouldImport("same-message"));
    assertFalse(deduplicator.shouldImport("same-message"));
    assertTrue(deduplicator.shouldImport("same-message"));
  }
}