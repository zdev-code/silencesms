package org.smssecure.smssecure.crypto;

import android.content.SharedPreferences;

import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.smssecure.smssecure.BaseUnitTest;

import java.util.Arrays;
import java.security.GeneralSecurityException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class MasterSecretUtilArgon2Test extends BaseUnitTest {
  private final byte[] combinedSecret = sequence(36);

  @Before
  @Override
  public void setUp() throws Exception {
    super.setUp();
    when(sharedPreferences.getString(anyString(), anyString())).thenReturn("");
    SharedPreferences.Editor defaultEditor = editorReturningSelf();
    when(sharedPreferences.edit()).thenReturn(defaultEditor);
  }

  @Test
  public void modernWrapperWritesWhenArgon2IsAvailable() {
    assertTrue(MasterSecretUtil.modernCryptoWritesAvailable(true));
    assertFalse(MasterSecretUtil.modernCryptoWritesAvailable(false));
  }

  @Test
  public void readsActiveArgon2WrapperWhenNativeIsAvailable() throws Exception {
    byte[] serialized = new byte[] {1, 2, 3};
    activateArgon2(serialized);

    MasterSecret result = MasterSecretUtil.getMasterSecret(
        context, "passphrase", true,
        (wrapper, passphrase) -> {
          assertArrayEquals(serialized, wrapper);
          return Arrays.copyOf(combinedSecret, combinedSecret.length);
        });

    assertArrayEquals(Arrays.copyOfRange(combinedSecret, 0, 16),
                      result.getEncryptionKey().getEncoded());
    assertArrayEquals(Arrays.copyOfRange(combinedSecret, 16, 36),
                      result.getMacKey().getEncoded());
  }

  @Test(expected = InvalidPassphraseException.class)
  public void activeArgon2WrapperMissingFailsClosed() throws Exception {
    when(sharedPreferences.getInt(MasterSecretUtil.ACTIVE_MASTER_SECRET,
                                  MasterSecretUtil.ACTIVE_MASTER_SECRET_LEGACY))
        .thenReturn(MasterSecretUtil.ACTIVE_MASTER_SECRET_ARGON2);

    MasterSecretUtil.getMasterSecret(context, "passphrase", true,
                                     (wrapper, passphrase) -> combinedSecret);
  }

  @Test(expected = InvalidPassphraseException.class)
  public void activeArgon2CorruptionDoesNotFallback() throws Exception {
    activateArgon2(new byte[] {1, 2, 3});

    MasterSecretUtil.getMasterSecret(context, "passphrase", true,
        (wrapper, passphrase) -> {
          throw new InvalidPassphraseException("tampered");
        });
  }

  @Test(expected = InvalidPassphraseException.class)
  public void unknownActiveVersionFailsClosed() throws Exception {
    MasterSecretUtil.Argon2WrapperDecryptor decryptor = mock(MasterSecretUtil.Argon2WrapperDecryptor.class);
    when(sharedPreferences.getInt(MasterSecretUtil.ACTIVE_MASTER_SECRET,
                                  MasterSecretUtil.ACTIVE_MASTER_SECRET_LEGACY))
        .thenReturn(99);

    try {
      MasterSecretUtil.getMasterSecret(context, "passphrase", true, decryptor);
    } finally {
      verifyNoInteractions(decryptor);
    }
  }

  @Test(expected = InvalidPassphraseException.class)
  public void unavailableArgon2UsesLegacyReaderWithoutTouchingV2() throws Exception {
    MasterSecretUtil.Argon2WrapperDecryptor decryptor = mock(MasterSecretUtil.Argon2WrapperDecryptor.class);
    activateArgon2(new byte[] {1, 2, 3});

    try {
      MasterSecretUtil.getMasterSecret(context, "passphrase", false, decryptor);
    } finally {
      verifyNoInteractions(decryptor);
    }
  }

  @Test(expected = InvalidPassphraseException.class)
  public void unavailableArgon2FailsClosedOnceLegacyWrapperIsRetired() throws Exception {
    MasterSecretUtil.Argon2WrapperDecryptor decryptor = mock(MasterSecretUtil.Argon2WrapperDecryptor.class);
    activateArgon2(new byte[] {1, 2, 3});
    when(sharedPreferences.getBoolean(MasterSecretUtil.LEGACY_WRAPPER_RETIRED, false))
        .thenReturn(true);

    try {
      MasterSecretUtil.getMasterSecret(context, "passphrase", false, decryptor);
    } finally {
      verifyNoInteractions(decryptor);
    }
  }

  @Test
  public void earlyArgon2UnlocksOnlyCountTowardsRetirement() throws Exception {
    SharedPreferences.Editor editor = editorReturningSelf();
    when(sharedPreferences.edit()).thenReturn(editor);
    when(sharedPreferences.getInt(MasterSecretUtil.ARGON2_CONFIRMED_UNLOCKS, 0)).thenReturn(0);

    MasterSecretUtil.noteConfirmedArgon2Unlock(context);

    verify(editor).putInt(MasterSecretUtil.ARGON2_CONFIRMED_UNLOCKS, 1);
    verify(editor, never()).remove(MasterSecretUtil.LEGACY_MASTER_SECRET);
  }

  @Test
  public void legacyWrapperIsRetiredAfterConfirmedArgon2Unlocks() throws Exception {
    SharedPreferences.Editor editor = editorReturningSelf();
    when(sharedPreferences.edit()).thenReturn(editor);
    when(editor.commit()).thenReturn(true);
    when(sharedPreferences.getInt(MasterSecretUtil.ARGON2_CONFIRMED_UNLOCKS, 0))
        .thenReturn(MasterSecretUtil.LEGACY_WRAPPER_RETIREMENT_UNLOCKS - 1);

    MasterSecretUtil.noteConfirmedArgon2Unlock(context);

    verify(editor).remove(MasterSecretUtil.LEGACY_MASTER_SECRET);
    verify(editor).remove(MasterSecretUtil.LEGACY_ENCRYPTION_SALT);
    verify(editor).remove(MasterSecretUtil.LEGACY_MAC_SALT);
    verify(editor).remove(MasterSecretUtil.LEGACY_ITERATIONS);
    verify(editor).putBoolean(MasterSecretUtil.LEGACY_WRAPPER_RETIRED, true);
    verify(editor).commit();
  }

  @Test
  public void retiredLegacyWrapperIsNeverRewritten() throws Exception {
    when(sharedPreferences.getBoolean(MasterSecretUtil.LEGACY_WRAPPER_RETIRED, false))
        .thenReturn(true);
    byte[] serialized = new byte[] {4, 5, 6};
    MasterSecretMigration.LegacyWrapper legacyWrapper =
        new MasterSecretMigration.LegacyWrapper(new byte[] {7}, new byte[] {8}, 9,
                                                new byte[] {10});
    SharedPreferences.Editor stagingEditor = editorReturningSelf();
    SharedPreferences.Editor activationEditor = editorReturningSelf();
    when(sharedPreferences.edit()).thenReturn(stagingEditor, activationEditor);
    when(stagingEditor.commit()).thenReturn(true);
    when(activationEditor.commit()).thenReturn(true);
    when(sharedPreferences.getString(MasterSecretUtil.MASTER_SECRET_V2_PENDING, ""))
        .thenReturn(org.smssecure.smssecure.util.Base64.encodeBytes(serialized));

    MasterSecretUtil.migrateMasterSecret(context, combinedSecret, "passphrase", legacyWrapper,
        (secret, passphrase) -> Arrays.copyOf(serialized, serialized.length),
        (wrapper, passphrase) -> Arrays.copyOf(combinedSecret, combinedSecret.length));

    verify(activationEditor, never()).putString(org.mockito.ArgumentMatchers.eq(
        MasterSecretUtil.LEGACY_MASTER_SECRET), anyString());
    verify(activationEditor).putString(MasterSecretUtil.MASTER_SECRET_V2,
                                       org.smssecure.smssecure.util.Base64.encodeBytes(serialized));
  }

  @Test
  public void migrationStagesThenAtomicallyActivatesAllWrappers() throws Exception {
    byte[] serialized = new byte[] {4, 5, 6};
    MasterSecretMigration.LegacyWrapper legacyWrapper =
        new MasterSecretMigration.LegacyWrapper(new byte[] {7}, new byte[] {8}, 9,
                                                new byte[] {10});
    SharedPreferences.Editor stagingEditor = editorReturningSelf();
    SharedPreferences.Editor activationEditor = editorReturningSelf();
    when(sharedPreferences.edit()).thenReturn(stagingEditor, activationEditor);
    when(stagingEditor.commit()).thenReturn(true);
    when(activationEditor.commit()).thenReturn(true);
    when(sharedPreferences.getString(MasterSecretUtil.MASTER_SECRET_V2_PENDING, ""))
        .thenReturn(org.smssecure.smssecure.util.Base64.encodeBytes(serialized));

    MasterSecretUtil.migrateMasterSecret(context, combinedSecret, "passphrase", legacyWrapper,
        (secret, passphrase) -> Arrays.copyOf(serialized, serialized.length),
        (wrapper, passphrase) -> Arrays.copyOf(combinedSecret, combinedSecret.length));

    org.mockito.InOrder order = inOrder(stagingEditor, activationEditor);
    order.verify(stagingEditor).putString(MasterSecretUtil.MASTER_SECRET_V2_PENDING,
                                          org.smssecure.smssecure.util.Base64.encodeBytes(serialized));
    order.verify(stagingEditor).commit();
    order.verify(activationEditor).putString("encryption_salt",
                                             org.smssecure.smssecure.util.Base64.encodeBytes(new byte[] {7}));
    order.verify(activationEditor).putString("mac_salt",
                                             org.smssecure.smssecure.util.Base64.encodeBytes(new byte[] {8}));
    order.verify(activationEditor).putInt("passphrase_iterations", 9);
    order.verify(activationEditor).putString("master_secret",
                                             org.smssecure.smssecure.util.Base64.encodeBytes(new byte[] {10}));
    order.verify(activationEditor).putString(MasterSecretUtil.MASTER_SECRET_V2,
                                             org.smssecure.smssecure.util.Base64.encodeBytes(serialized));
    order.verify(activationEditor).putInt(MasterSecretUtil.ACTIVE_MASTER_SECRET,
                                          MasterSecretUtil.ACTIVE_MASTER_SECRET_ARGON2);
    order.verify(activationEditor).putBoolean("passphrase_initialized", true);
    order.verify(activationEditor).remove(MasterSecretUtil.MASTER_SECRET_V2_PENDING);
    order.verify(activationEditor).remove(MasterSecretUtil.MASTER_SECRET_V2_NEXT_ATTEMPT);
    order.verify(activationEditor).commit();
  }

  @Test
  public void automaticMigrationAttemptIsClaimedBeforeWork() {
    long now = 1_000_000L;
    SharedPreferences.Editor editor = editorReturningSelf();
    when(sharedPreferences.edit()).thenReturn(editor);
    when(editor.commit()).thenReturn(true);

    assertTrue(MasterSecretUtil.claimAutomaticMigrationAttempt(sharedPreferences, now));
    verify(editor).putLong(MasterSecretUtil.MASTER_SECRET_V2_NEXT_ATTEMPT,
                           now + MasterSecretUtil.AUTOMATIC_MIGRATION_RETRY_MILLIS);
    verify(editor).commit();
  }

  @Test
  public void automaticMigrationIsSkippedDuringRetryWindow() {
    long now = 1_000_000L;
    when(sharedPreferences.getLong(MasterSecretUtil.MASTER_SECRET_V2_NEXT_ATTEMPT, 0))
        .thenReturn(now + MasterSecretUtil.AUTOMATIC_MIGRATION_RETRY_MILLIS);

    assertFalse(MasterSecretUtil.claimAutomaticMigrationAttempt(sharedPreferences, now));
    verify(sharedPreferences, never()).edit();
  }

  @Test
  public void automaticMigrationIsSkippedWhenClaimCannotBeSaved() {
    SharedPreferences.Editor editor = editorReturningSelf();
    when(sharedPreferences.edit()).thenReturn(editor);
    when(editor.commit()).thenReturn(false);

    assertFalse(MasterSecretUtil.claimAutomaticMigrationAttempt(sharedPreferences, 1_000_000L));
  }

  @Test
  public void implausibleFutureRetryTimestampCanBeReclaimed() {
    long now = 1_000_000L;
    SharedPreferences.Editor editor = editorReturningSelf();
    when(sharedPreferences.getLong(MasterSecretUtil.MASTER_SECRET_V2_NEXT_ATTEMPT, 0))
        .thenReturn(now + MasterSecretUtil.AUTOMATIC_MIGRATION_RETRY_MILLIS + 1);
    when(sharedPreferences.edit()).thenReturn(editor);
    when(editor.commit()).thenReturn(true);

    assertTrue(MasterSecretUtil.claimAutomaticMigrationAttempt(sharedPreferences, now));
  }

  @Test(expected = GeneralSecurityException.class)
  public void failedCandidateCommitNeverCreatesActivationEditor() throws Exception {
    byte[] serialized = new byte[] {4, 5, 6};
    SharedPreferences.Editor stagingEditor = editorReturningSelf();
    when(sharedPreferences.edit()).thenReturn(stagingEditor);
    when(stagingEditor.commit()).thenReturn(false);

    try {
      MasterSecretUtil.migrateMasterSecret(context, combinedSecret, "passphrase", null,
          (secret, passphrase) -> Arrays.copyOf(serialized, serialized.length),
          (wrapper, passphrase) -> Arrays.copyOf(combinedSecret, combinedSecret.length));
    } finally {
      verify(sharedPreferences, times(1)).edit();
      verify(stagingEditor, never()).putInt(MasterSecretUtil.ACTIVE_MASTER_SECRET,
                                            MasterSecretUtil.ACTIVE_MASTER_SECRET_ARGON2);
    }
  }

  @Test(expected = MasterSecretStorageException.class)
  public void createStorageFailureIsRecoverable() throws Exception {
    SharedPreferences.Editor editor = editorReturningSelf();
    when(sharedPreferences.edit()).thenReturn(editor);
    when(editor.commit()).thenReturn(false);

    try (MockedStatic<Argon2id> argon2 = mockStatic(Argon2id.class)) {
      argon2.when(Argon2id::isAvailable).thenReturn(false);
      MasterSecretUtil.generateMasterSecret(context, "passphrase");
    }
  }

  @Test(expected = MasterSecretStorageException.class)
  public void passphraseChangeStorageFailureIsRecoverable() throws Exception {
    SharedPreferences.Editor editor = editorReturningSelf();
    when(sharedPreferences.edit()).thenReturn(editor);
    when(editor.commit()).thenReturn(false);

    try (MockedStatic<Argon2id> argon2 = mockStatic(Argon2id.class)) {
      argon2.when(Argon2id::isAvailable).thenReturn(false);
      MasterSecretUtil.changeMasterSecretPassphrase(context, masterSecret, "passphrase");
    }
  }

  @Test(expected = MasterSecretStorageException.class)
  public void staleGenerationRejectsCharacterPassphraseBeforeDerivationOrStorage() throws Exception {
    char[] replacement = "passphrase".toCharArray();
    AtomicInteger checks = new AtomicInteger();
    clearInvocations(sharedPreferences);

    try {
      MasterSecretUtil.changeMasterSecretPassphrase(context, masterSecret, replacement, () -> {
        checks.incrementAndGet();
        throw new GeneralSecurityException("stale generation");
      });
    } finally {
      assertEquals(1, checks.get());
      verify(sharedPreferences, never()).edit();
      assertArrayEquals("passphrase".toCharArray(), replacement);
      Arrays.fill(replacement, '\0');
    }
  }

  private SharedPreferences.Editor editorReturningSelf() {
    SharedPreferences.Editor editor = mock(SharedPreferences.Editor.class);
    when(editor.putString(anyString(), anyString())).thenReturn(editor);
    when(editor.putInt(anyString(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(editor);
    when(editor.putLong(anyString(), anyLong())).thenReturn(editor);
    when(editor.putBoolean(anyString(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(editor);
    when(editor.remove(anyString())).thenReturn(editor);
    return editor;
  }

  private void activateArgon2(byte[] serialized) {
    when(sharedPreferences.getInt(MasterSecretUtil.ACTIVE_MASTER_SECRET,
                                  MasterSecretUtil.ACTIVE_MASTER_SECRET_LEGACY))
        .thenReturn(MasterSecretUtil.ACTIVE_MASTER_SECRET_ARGON2);
    when(sharedPreferences.getString(MasterSecretUtil.MASTER_SECRET_V2, ""))
        .thenReturn(org.smssecure.smssecure.util.Base64.encodeBytes(serialized));
  }

  private static byte[] sequence(int length) {
    byte[] result = new byte[length];
    for (int i = 0; i < length; i++) result[i] = (byte) i;
    return result;
  }
}