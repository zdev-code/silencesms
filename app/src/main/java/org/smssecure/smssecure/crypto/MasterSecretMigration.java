package org.smssecure.smssecure.crypto;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;

final class MasterSecretMigration {
  interface WrapperEncryptor {
    byte[] encrypt(byte[] masterSecret, String passphrase) throws GeneralSecurityException;
  }

  interface WrapperDecryptor {
    byte[] decrypt(byte[] serialized, String passphrase)
        throws GeneralSecurityException, InvalidPassphraseException;
  }

  interface Storage {
    boolean writeCandidate(byte[] serialized);
    byte[] readCandidate() throws GeneralSecurityException;
    boolean activate(byte[] serialized, LegacyWrapper legacyWrapper);
  }

  static final class LegacyWrapper {
    final byte[] encryptionSalt;
    final byte[] macSalt;
    final int iterations;
    final byte[] encryptedMasterSecret;

    LegacyWrapper(byte[] encryptionSalt, byte[] macSalt, int iterations,
                  byte[] encryptedMasterSecret) {
      this.encryptionSalt = encryptionSalt;
      this.macSalt = macSalt;
      this.iterations = iterations;
      this.encryptedMasterSecret = encryptedMasterSecret;
    }
  }

  private MasterSecretMigration() {}

  static void migrate(byte[] masterSecret, String passphrase, LegacyWrapper legacyWrapper,
                      Storage storage, WrapperEncryptor encryptor,
                      WrapperDecryptor decryptor)
      throws GeneralSecurityException, InvalidPassphraseException
  {
    byte[] serialized = encryptor.encrypt(masterSecret, passphrase);
    byte[] candidate = null;
    byte[] verifiedSecret = null;
    try {
      if (!storage.writeCandidate(serialized)) {
        throw new GeneralSecurityException("Unable to stage Argon2 master-secret wrapper");
      }

      candidate = storage.readCandidate();
      if (candidate == null) {
        throw new GeneralSecurityException("Staged Argon2 master-secret wrapper is missing");
      }
      verifiedSecret = decryptor.decrypt(candidate, passphrase);
      if (!MessageDigest.isEqual(masterSecret, verifiedSecret)) {
        throw new GeneralSecurityException("Argon2 master-secret verification failed");
      }
      if (!storage.activate(candidate, legacyWrapper)) {
        throw new GeneralSecurityException("Unable to activate Argon2 master-secret wrapper");
      }
    } finally {
      Arrays.fill(serialized, (byte) 0);
      if (candidate != null) Arrays.fill(candidate, (byte) 0);
      if (verifiedSecret != null) Arrays.fill(verifiedSecret, (byte) 0);
    }
  }
}