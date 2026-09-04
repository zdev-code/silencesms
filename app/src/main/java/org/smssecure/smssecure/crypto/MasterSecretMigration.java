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

  interface CharacterWrapperEncryptor {
    byte[] encrypt(byte[] masterSecret, char[] passphrase) throws GeneralSecurityException;
  }

  interface CharacterWrapperDecryptor {
    byte[] decrypt(byte[] serialized, char[] passphrase)
        throws GeneralSecurityException, InvalidPassphraseException;
  }

  interface Storage {
    boolean writeCandidate(byte[] serialized);
    byte[] readCandidate() throws GeneralSecurityException;
    boolean activate(byte[] serialized, LegacyWrapper legacyWrapper);
  }

  interface ActivationGuard {
    void check() throws GeneralSecurityException;
  }

  private interface WrapperOperations {
    byte[] encrypt(byte[] masterSecret) throws GeneralSecurityException;
    byte[] decrypt(byte[] serialized)
        throws GeneralSecurityException, InvalidPassphraseException;
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
    migrate(masterSecret, passphrase, legacyWrapper, storage, encryptor, decryptor, () -> {});
  }

  static void migrate(byte[] masterSecret, String passphrase, LegacyWrapper legacyWrapper,
                      Storage storage, WrapperEncryptor encryptor,
                      WrapperDecryptor decryptor, ActivationGuard activationGuard)
      throws GeneralSecurityException, InvalidPassphraseException
  {
    migrate(masterSecret, legacyWrapper, storage, new WrapperOperations() {
      @Override public byte[] encrypt(byte[] secret) throws GeneralSecurityException {
        return encryptor.encrypt(secret, passphrase);
      }

      @Override public byte[] decrypt(byte[] serialized)
          throws GeneralSecurityException, InvalidPassphraseException
      {
        return decryptor.decrypt(serialized, passphrase);
      }
    }, activationGuard);
  }

  static void migrate(byte[] masterSecret, char[] passphrase, LegacyWrapper legacyWrapper,
                      Storage storage, CharacterWrapperEncryptor encryptor,
                      CharacterWrapperDecryptor decryptor, ActivationGuard activationGuard)
      throws GeneralSecurityException, InvalidPassphraseException
  {
    migrate(masterSecret, legacyWrapper, storage, new WrapperOperations() {
      @Override public byte[] encrypt(byte[] secret) throws GeneralSecurityException {
        return encryptor.encrypt(secret, passphrase);
      }

      @Override public byte[] decrypt(byte[] serialized)
          throws GeneralSecurityException, InvalidPassphraseException
      {
        return decryptor.decrypt(serialized, passphrase);
      }
    }, activationGuard);
  }

  private static void migrate(byte[] masterSecret, LegacyWrapper legacyWrapper, Storage storage,
                              WrapperOperations operations, ActivationGuard activationGuard)
      throws GeneralSecurityException, InvalidPassphraseException
  {
    activationGuard.check();
    byte[] serialized = operations.encrypt(masterSecret);
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
      verifiedSecret = operations.decrypt(candidate);
      if (!MessageDigest.isEqual(masterSecret, verifiedSecret)) {
        throw new GeneralSecurityException("Argon2 master-secret verification failed");
      }
      activationGuard.check();
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