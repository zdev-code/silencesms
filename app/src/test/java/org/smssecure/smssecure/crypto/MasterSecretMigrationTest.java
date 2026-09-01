package org.smssecure.smssecure.crypto;

import org.junit.Test;

import java.security.GeneralSecurityException;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MasterSecretMigrationTest {
  private final byte[] masterSecret = sequence(36);
  private final byte[] serialized   = sequence(64);
  private final MasterSecretMigration.LegacyWrapper legacyWrapper =
      new MasterSecretMigration.LegacyWrapper(new byte[16], new byte[16], 10000, new byte[64]);

  @Test
  public void activatesOnlyAfterCandidateVerification() throws Exception {
    RecordingStorage storage = new RecordingStorage();

    migrate(storage, Arrays.copyOf(masterSecret, masterSecret.length));

    assertTrue(storage.candidateWritten);
    assertTrue(storage.candidateRead);
    assertTrue(storage.activated);
    assertArrayEquals(serialized, storage.activatedWrapper);
    assertTrue(storage.activatedLegacyWrapper == legacyWrapper);
  }

  @Test(expected = GeneralSecurityException.class)
  public void candidateWriteFailureLeavesPreviousStateAuthoritative() throws Exception {
    RecordingStorage storage = new RecordingStorage();
    storage.writeSucceeds = false;

    try {
      migrate(storage, Arrays.copyOf(masterSecret, masterSecret.length));
    } finally {
      assertFalse(storage.activated);
      assertFalse(storage.candidateRead);
    }
  }

  @Test(expected = GeneralSecurityException.class)
  public void missingCandidateLeavesPreviousStateAuthoritative() throws Exception {
    RecordingStorage storage = new RecordingStorage();
    storage.returnMissingCandidate = true;

    try {
      migrate(storage, Arrays.copyOf(masterSecret, masterSecret.length));
    } finally {
      assertFalse(storage.activated);
    }
  }

  @Test(expected = GeneralSecurityException.class)
  public void mismatchedReadBackLeavesPreviousStateAuthoritative() throws Exception {
    RecordingStorage storage = new RecordingStorage();

    try {
      migrate(storage, new byte[masterSecret.length]);
    } finally {
      assertFalse(storage.activated);
    }
  }

  @Test(expected = GeneralSecurityException.class)
  public void finalCommitFailureIsReported() throws Exception {
    RecordingStorage storage = new RecordingStorage();
    storage.activateSucceeds = false;

    try {
      migrate(storage, Arrays.copyOf(masterSecret, masterSecret.length));
    } finally {
      assertFalse(storage.activated);
    }
  }

  @Test(expected = InvalidPassphraseException.class)
  public void candidateAuthenticationFailureLeavesPreviousStateAuthoritative() throws Exception {
    RecordingStorage storage = new RecordingStorage();

    try {
      MasterSecretMigration.migrate(masterSecret, "passphrase", legacyWrapper, storage,
          (secret, passphrase) -> Arrays.copyOf(serialized, serialized.length),
          (wrapper, passphrase) -> { throw new InvalidPassphraseException("tampered"); });
    } finally {
      assertFalse(storage.activated);
    }
  }

  private void migrate(RecordingStorage storage, byte[] verifiedSecret) throws Exception {
    MasterSecretMigration.migrate(masterSecret, "passphrase", legacyWrapper, storage,
        (secret, passphrase) -> Arrays.copyOf(serialized, serialized.length),
        (wrapper, passphrase) -> verifiedSecret);
  }

  private final class RecordingStorage implements MasterSecretMigration.Storage {
    boolean writeSucceeds = true;
    boolean activateSucceeds = true;
    boolean returnMissingCandidate;
    boolean candidateWritten;
    boolean candidateRead;
    boolean activated;
    byte[] activatedWrapper;
    MasterSecretMigration.LegacyWrapper activatedLegacyWrapper;

    @Override
    public boolean writeCandidate(byte[] value) {
      candidateWritten = true;
      return writeSucceeds;
    }

    @Override
    public byte[] readCandidate() {
      candidateRead = true;
      return returnMissingCandidate ? null : Arrays.copyOf(serialized, serialized.length);
    }

    @Override
    public boolean activate(byte[] wrapper, MasterSecretMigration.LegacyWrapper legacy) {
      if (!activateSucceeds) return false;
      activated = true;
      activatedWrapper = Arrays.copyOf(wrapper, wrapper.length);
      activatedLegacyWrapper = legacy;
      return true;
    }
  }

  private static byte[] sequence(int length) {
    byte[] result = new byte[length];
    for (int i = 0; i < length; i++) result[i] = (byte) i;
    return result;
  }
}