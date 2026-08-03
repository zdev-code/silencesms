/**
 * Copyright (C) 2011 Whisper Systems
 * Copyright (C) 2013 Open Whisper Systems
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
package org.smssecure.smssecure.crypto;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import org.smssecure.smssecure.BuildConfig;
import org.smssecure.smssecure.util.Base64;
import org.smssecure.smssecure.util.Util;
import org.signal.libsignal.protocol.InvalidKeyException;
import org.signal.libsignal.protocol.ecc.Curve;
import org.signal.libsignal.protocol.ecc.ECKeyPair;
import org.signal.libsignal.protocol.ecc.ECPrivateKey;
import org.signal.libsignal.protocol.ecc.ECPublicKey;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.PBEParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Helper class for generating and securely storing a MasterSecret.
 *
 * @author Moxie Marlinspike
 */

public class MasterSecretUtil {

  public static final String UNENCRYPTED_PASSPHRASE  = "unencrypted";
  public static final String PREFERENCES_NAME        = "SecureSMS-Preferences";
  public static final String DEVICE_PREFERENCES_NAME = "SecureSMS-Device-Preferences";

  private static final String ASYMMETRIC_LOCAL_PUBLIC_DJB   = "asymmetric_master_secret_curve25519_public";
  private static final String ASYMMETRIC_LOCAL_PRIVATE_DJB  = "asymmetric_master_secret_curve25519_private";
  static final String MASTER_SECRET_V2                      = "master_secret_v2";
  static final String MASTER_SECRET_V2_PENDING              = "master_secret_v2_pending";
  static final String MASTER_SECRET_V2_NEXT_ATTEMPT         = "master_secret_v2_next_attempt";
  private static final String DEVICE_WRAPPED_MASTER_SECRET   = "device_wrapped_master_secret_v1";
  private static final String DEVICE_KEY_ALIAS               = "device_key_alias_v1";
  static final String ACTIVE_MASTER_SECRET                  = "active_master_secret";
  static final int ACTIVE_MASTER_SECRET_LEGACY              = 0;
  static final int ACTIVE_MASTER_SECRET_ARGON2              = 2;
  static final long AUTOMATIC_MIGRATION_RETRY_MILLIS        = TimeUnit.HOURS.toMillis(24);

  static final String LEGACY_MASTER_SECRET         = "master_secret";
  static final String LEGACY_ENCRYPTION_SALT       = "encryption_salt";
  static final String LEGACY_MAC_SALT              = "mac_salt";
  static final String LEGACY_ITERATIONS            = "passphrase_iterations";
  static final String LEGACY_WRAPPER_RETIRED       = "legacy_master_secret_retired";
  static final String ARGON2_CONFIRMED_UNLOCKS     = "master_secret_v2_confirmed_unlocks";

  /**
   * Successful Argon2 unlocks required before the PBKDF1 wrapper is deleted. Retaining it leaves
   * offline passphrase resistance at PBKDF1 strength, so it is a rollback window, not a permanent
   * fallback. Device protection retires it immediately because an unbound wrapper in plain
   * preferences would bypass the Keystore binding entirely.
   */
  static final int LEGACY_WRAPPER_RETIREMENT_UNLOCKS = 3;

  interface Argon2WrapperDecryptor {
    byte[] decrypt(byte[] serialized, String passphrase)
        throws GeneralSecurityException, InvalidPassphraseException;
  }

  public static MasterSecret changeMasterSecretPassphrase(Context context,
                                                          MasterSecret masterSecret,
                                                          String newPassphrase)
      throws MasterSecretStorageException
  {
    byte[] combinedSecrets = null;
    try {
      combinedSecrets = combineMasterSecret(masterSecret);
      MasterSecretMigration.LegacyWrapper legacyWrapper =
          createLegacyWrapper(combinedSecrets, newPassphrase);

      if (modernCryptoWritesAvailable()) {
        migrateMasterSecret(context, combinedSecrets, newPassphrase, legacyWrapper);
      } else if (!saveLegacyWrapper(context, legacyWrapper)) {
        throw new GeneralSecurityException("Unable to save legacy master-secret wrapper");
      }

      return masterSecret;
    } catch (GeneralSecurityException | InvalidPassphraseException error) {
      throw new MasterSecretStorageException("Unable to persist master-secret wrapper", error);
    } finally {
      if (combinedSecrets != null) Arrays.fill(combinedSecrets, (byte) 0);
    }
  }

  public static MasterSecret changeMasterSecretPassphrase(Context context,
                                                          String originalPassphrase,
                                                          String newPassphrase)
      throws InvalidPassphraseException, MasterSecretStorageException
  {
    MasterSecret masterSecret = getMasterSecret(context, originalPassphrase, false);
    changeMasterSecretPassphrase(context, masterSecret, newPassphrase);

    return masterSecret;
  }

  public static MasterSecret getMasterSecret(Context context, String passphrase)
      throws InvalidPassphraseException
  {
    return getMasterSecret(context, passphrase, true);
  }

  private static MasterSecret getMasterSecret(Context context, String passphrase,
                                              boolean migrateLegacy)
      throws InvalidPassphraseException
  {
    return getMasterSecret(context, passphrase, Argon2id.isAvailable(),
                           Argon2MasterSecretEnvelope::decrypt, migrateLegacy);
  }

  static MasterSecret getMasterSecret(Context context, String passphrase,
                                      boolean argon2Available,
                                      Argon2WrapperDecryptor argon2Decryptor)
      throws InvalidPassphraseException
  {
    return getMasterSecret(context, passphrase, argon2Available, argon2Decryptor, false);
  }

  private static MasterSecret getMasterSecret(Context context, String passphrase,
                                              boolean argon2Available,
                                              Argon2WrapperDecryptor argon2Decryptor,
                                              boolean migrateLegacy)
      throws InvalidPassphraseException
  {
    SharedPreferences preferences = context.getSharedPreferences(PREFERENCES_NAME, 0);
    int activeMasterSecret = preferences.getInt(ACTIVE_MASTER_SECRET,
                                                ACTIVE_MASTER_SECRET_LEGACY);

    if (activeMasterSecret == ACTIVE_MASTER_SECRET_ARGON2 && argon2Available) {
      return getArgon2MasterSecret(context, passphrase, argon2Decryptor);
    }
    if (activeMasterSecret != ACTIVE_MASTER_SECRET_LEGACY &&
        activeMasterSecret != ACTIVE_MASTER_SECRET_ARGON2) {
      throw new InvalidPassphraseException("Unsupported active master-secret version");
    }
    if (activeMasterSecret == ACTIVE_MASTER_SECRET_ARGON2 &&
        preferences.getBoolean(LEGACY_WRAPPER_RETIRED, false)) {
      throw new InvalidPassphraseException(
          "Argon2 is unavailable and the legacy master-secret wrapper has been retired");
    }

    MasterSecret masterSecret = getLegacyMasterSecret(context, passphrase);
    if (migrateLegacy && BuildConfig.MODERN_CRYPTO_WRITES && argon2Available &&
      claimAutomaticMigrationAttempt(preferences, System.currentTimeMillis()))
    {
      byte[] combinedSecrets = combineMasterSecret(masterSecret);
      try {
        migrateMasterSecret(context, combinedSecrets, passphrase, null);
      } catch (GeneralSecurityException | InvalidPassphraseException error) {
        Log.w("keyutil", "Unable to migrate legacy master-secret wrapper", error);
      } finally {
        Arrays.fill(combinedSecrets, (byte) 0);
      }
    }
    return masterSecret;
  }

  static boolean claimAutomaticMigrationAttempt(SharedPreferences preferences, long now) {
    long nextAttempt = preferences.getLong(MASTER_SECRET_V2_NEXT_ATTEMPT, 0);
    if (nextAttempt > now && nextAttempt - now <= AUTOMATIC_MIGRATION_RETRY_MILLIS) {
      return false;
    }

    return preferences.edit()
                      .putLong(MASTER_SECRET_V2_NEXT_ATTEMPT,
                               now + AUTOMATIC_MIGRATION_RETRY_MILLIS)
                      .commit();
  }

  private static MasterSecret getArgon2MasterSecret(Context context, String passphrase,
                                                     Argon2WrapperDecryptor argon2Decryptor)
      throws InvalidPassphraseException
  {
    byte[] combinedSecrets = null;
    try {
      byte[] serialized = getArgon2WrapperForUnlock(context);
      if (serialized == null) {
        throw new InvalidPassphraseException("Active Argon2 master-secret wrapper is missing");
      }
      combinedSecrets = argon2Decryptor.decrypt(serialized, passphrase);
      MasterSecret masterSecret = masterSecretFromCombinedBytes(combinedSecrets);
      noteConfirmedArgon2Unlock(context);
      return masterSecret;
    } catch (GeneralSecurityException | IOException error) {
      Log.w("keyutil", error);
      throw new InvalidPassphraseException(error);
    } finally {
      if (combinedSecrets != null) Arrays.fill(combinedSecrets, (byte) 0);
    }
  }

  /**
   * Counts a verified Argon2 unlock and, once the rollback window has elapsed, deletes the PBKDF1
   * wrapper so it can no longer be attacked offline.
   */
  static void noteConfirmedArgon2Unlock(Context context) {
    SharedPreferences preferences = context.getSharedPreferences(PREFERENCES_NAME, 0);
    if (preferences.getBoolean(LEGACY_WRAPPER_RETIRED, false)) return;

    int unlocks = preferences.getInt(ARGON2_CONFIRMED_UNLOCKS, 0) + 1;
    if (unlocks < LEGACY_WRAPPER_RETIREMENT_UNLOCKS && !isDeviceProtectionEnabled(context)) {
      preferences.edit().putInt(ARGON2_CONFIRMED_UNLOCKS, unlocks).apply();
      return;
    }

    retireLegacyWrapper(preferences.edit()).commit();
  }

  private static SharedPreferences.Editor retireLegacyWrapper(SharedPreferences.Editor editor) {
    return editor.remove(LEGACY_MASTER_SECRET)
                 .remove(LEGACY_ENCRYPTION_SALT)
                 .remove(LEGACY_MAC_SALT)
                 .remove(LEGACY_ITERATIONS)
                 .remove(ARGON2_CONFIRMED_UNLOCKS)
                 .putBoolean(LEGACY_WRAPPER_RETIRED, true);
  }

  private static byte[] getArgon2WrapperForUnlock(Context context)
      throws GeneralSecurityException, IOException
  {
    SharedPreferences devicePreferences =
        context.getSharedPreferences(DEVICE_PREFERENCES_NAME, 0);
    String deviceWrapped = devicePreferences.getString(DEVICE_WRAPPED_MASTER_SECRET, "");
    if (TextUtils.isEmpty(deviceWrapped)) return retrieve(context, MASTER_SECRET_V2);

    String alias = devicePreferences.getString(DEVICE_KEY_ALIAS,
                           AndroidKeystoreKeyManager.ALIAS_A);
    javax.crypto.SecretKey deviceKey = AndroidKeystoreKeyManager.getExisting(alias);
    if (deviceKey == null) throw new GeneralSecurityException("Device protection key is missing");
    return DeviceKeyEnvelope.decrypt(Base64.decode(deviceWrapped), deviceKey);
  }

  public static boolean isDeviceProtectionEnabled(Context context) {
    return context.getSharedPreferences(DEVICE_PREFERENCES_NAME, 0)
                  .contains(DEVICE_WRAPPED_MASTER_SECRET);
  }

  public static byte[] getPortableArgon2Wrapper(Context context)
      throws GeneralSecurityException
  {
    try {
      byte[] wrapper = getArgon2WrapperForUnlock(context);
      if (wrapper == null) throw new GeneralSecurityException("Argon2 wrapper is missing");
      return wrapper;
    } catch (IOException error) {
      throw new GeneralSecurityException("Unable to read Argon2 wrapper", error);
    }
  }

  public static RestoredDeviceProtection prepareRestoredDeviceProtection(
      Context context, byte[] argon2Wrapper) throws GeneralSecurityException {
    Argon2MasterSecretEnvelope.validate(argon2Wrapper);
    SharedPreferences preferences = context.getSharedPreferences(DEVICE_PREFERENCES_NAME, 0);
    boolean hadPreviousEnvelope = preferences.contains(DEVICE_WRAPPED_MASTER_SECRET);
    boolean hadPreviousAlias = preferences.contains(DEVICE_KEY_ALIAS);
    String previousEnvelope = preferences.getString(DEVICE_WRAPPED_MASTER_SECRET, "");
    String activeAlias = preferences.getString(DEVICE_KEY_ALIAS, AndroidKeystoreKeyManager.ALIAS_A);
    String candidateAlias = AndroidKeystoreKeyManager.ALIAS_A.equals(activeAlias)
        ? AndroidKeystoreKeyManager.ALIAS_B : AndroidKeystoreKeyManager.ALIAS_A;
    AndroidKeystoreKeyManager.delete(candidateAlias);
    byte[] envelope = DeviceKeyEnvelope.encrypt(
        argon2Wrapper, AndroidKeystoreKeyManager.getOrCreate(context, candidateAlias));
    return new RestoredDeviceProtection(activeAlias, candidateAlias, previousEnvelope,
                      hadPreviousEnvelope, hadPreviousAlias, envelope);
  }

  public static void activateRestoredDeviceProtection(Context context,
                                                       RestoredDeviceProtection candidate)
      throws GeneralSecurityException {
    if (!context.getSharedPreferences(DEVICE_PREFERENCES_NAME, 0).edit()
                .putString(DEVICE_WRAPPED_MASTER_SECRET, Base64.encodeBytes(candidate.envelope))
                .putString(DEVICE_KEY_ALIAS, candidate.candidateAlias)
                .commit()) {
      throw new GeneralSecurityException("Unable to activate restored device protection");
    }
    candidate.activated = true;
  }

  public static void finalizeRestoredDeviceProtection(Context context,
                                                       RestoredDeviceProtection candidate)
      throws GeneralSecurityException {
    try {
      if (!retireLegacyWrapper(context.getSharedPreferences(PREFERENCES_NAME, 0).edit()).commit()) {
        throw new GeneralSecurityException("Unable to retire restored legacy wrapper state");
      }
      AndroidKeystoreKeyManager.delete(candidate.previousAlias);
    } finally {
      candidate.destroy();
    }
  }

  public static void abortRestoredDeviceProtection(Context context,
                                                    RestoredDeviceProtection candidate)
      throws GeneralSecurityException {
    try {
      if (candidate.activated) {
        SharedPreferences.Editor editor = context.getSharedPreferences(
            DEVICE_PREFERENCES_NAME, 0).edit();
        if (candidate.hadPreviousEnvelope) {
          editor.putString(DEVICE_WRAPPED_MASTER_SECRET, candidate.previousEnvelope);
        } else {
          editor.remove(DEVICE_WRAPPED_MASTER_SECRET);
        }
        if (candidate.hadPreviousAlias) editor.putString(DEVICE_KEY_ALIAS, candidate.previousAlias);
        else editor.remove(DEVICE_KEY_ALIAS);
        if (!editor.commit()) {
          throw new GeneralSecurityException("Unable to restore previous device protection");
        }
      }
      AndroidKeystoreKeyManager.delete(candidate.candidateAlias);
    } finally {
      candidate.destroy();
    }
  }

  public static void reconcileDeviceProtection(Context context) throws GeneralSecurityException {
    SharedPreferences preferences = context.getSharedPreferences(DEVICE_PREFERENCES_NAME, 0);
    if (!preferences.contains(DEVICE_WRAPPED_MASTER_SECRET)) {
      AndroidKeystoreKeyManager.delete(AndroidKeystoreKeyManager.ALIAS_A);
      AndroidKeystoreKeyManager.delete(AndroidKeystoreKeyManager.ALIAS_B);
      return;
    }
    String activeAlias = preferences.getString(DEVICE_KEY_ALIAS, AndroidKeystoreKeyManager.ALIAS_A);
    String inactiveAlias = AndroidKeystoreKeyManager.ALIAS_A.equals(activeAlias)
        ? AndroidKeystoreKeyManager.ALIAS_B : AndroidKeystoreKeyManager.ALIAS_A;
    AndroidKeystoreKeyManager.delete(inactiveAlias);
    if (!retireLegacyWrapper(context.getSharedPreferences(PREFERENCES_NAME, 0).edit()).commit()) {
      throw new GeneralSecurityException("Unable to reconcile restored legacy wrapper state");
    }
  }

  public static final class RestoredDeviceProtection {
    private final String previousAlias;
    private final String candidateAlias;
    private final String previousEnvelope;
    private final boolean hadPreviousEnvelope;
    private final boolean hadPreviousAlias;
    private final byte[] envelope;
    private boolean activated;

    private RestoredDeviceProtection(String previousAlias, String candidateAlias,
                                     String previousEnvelope, boolean hadPreviousEnvelope,
                                     boolean hadPreviousAlias, byte[] envelope) {
      this.previousAlias = previousAlias;
      this.candidateAlias = candidateAlias;
      this.previousEnvelope = previousEnvelope;
      this.hadPreviousEnvelope = hadPreviousEnvelope;
      this.hadPreviousAlias = hadPreviousAlias;
      this.envelope = envelope;
    }

    private void destroy() {
      Arrays.fill(envelope, (byte) 0);
    }
  }

  public static void enableDeviceProtection(Context context) throws GeneralSecurityException {
    byte[] argon2Wrapper = null;
    try {
      argon2Wrapper = retrieve(context, MASTER_SECRET_V2);
      if (argon2Wrapper == null) {
        throw new GeneralSecurityException("Argon2 master-secret wrapper is required");
      }
      installDeviceProtection(context, argon2Wrapper, true);
    } catch (IOException error) {
      throw new GeneralSecurityException("Unable to read Argon2 wrapper", error);
    } finally {
      if (argon2Wrapper != null) Arrays.fill(argon2Wrapper, (byte) 0);
    }
  }

  public static void disableDeviceProtection(Context context) throws GeneralSecurityException {
    byte[] argon2Wrapper;
    try {
      argon2Wrapper = getArgon2WrapperForUnlock(context);
    } catch (IOException error) {
      throw new GeneralSecurityException("Unable to read device protection wrapper", error);
    }
    try {
      if (!context.getSharedPreferences(PREFERENCES_NAME, 0).edit()
                  .putString(MASTER_SECRET_V2, Base64.encodeBytes(argon2Wrapper)).commit()) {
        throw new GeneralSecurityException("Unable to restore passphrase wrapper");
      }
    } finally {
      Arrays.fill(argon2Wrapper, (byte) 0);
    }
    boolean removed = context.getSharedPreferences(DEVICE_PREFERENCES_NAME, 0)
                             .edit().remove(DEVICE_WRAPPED_MASTER_SECRET).commit();
    if (!removed) throw new GeneralSecurityException("Unable to remove device protection wrapper");
    AndroidKeystoreKeyManager.delete(AndroidKeystoreKeyManager.ALIAS_A);
    AndroidKeystoreKeyManager.delete(AndroidKeystoreKeyManager.ALIAS_B);
  }

  public static void rebindDeviceProtection(Context context) throws GeneralSecurityException {
    byte[] argon2Wrapper;
    try {
      argon2Wrapper = getArgon2WrapperForUnlock(context);
    } catch (IOException error) {
      throw new GeneralSecurityException("Unable to read device protection wrapper", error);
    }
    try {
      AndroidKeystoreKeyManager.delete();
      installDeviceProtection(context, argon2Wrapper, false);
    } finally {
      Arrays.fill(argon2Wrapper, (byte) 0);
    }
  }

  private static void installDeviceProtection(Context context, byte[] argon2Wrapper,
                                              boolean removeRawWrapper)
      throws GeneralSecurityException
  {
    String alias = AndroidKeystoreKeyManager.ALIAS_A;
    byte[] deviceWrapped = DeviceKeyEnvelope.encrypt(
      argon2Wrapper, AndroidKeystoreKeyManager.getOrCreate(context, alias));
    try {
      if (!context.getSharedPreferences(DEVICE_PREFERENCES_NAME, 0).edit()
                  .putString(DEVICE_WRAPPED_MASTER_SECRET,
                             Base64.encodeBytes(deviceWrapped))
                  .putString(DEVICE_KEY_ALIAS, alias).commit()) {
        throw new GeneralSecurityException("Unable to save device protection wrapper");
      }
    } finally {
      Arrays.fill(deviceWrapped, (byte) 0);
    }

    byte[] verified;
    try {
      verified = getArgon2WrapperForUnlock(context);
    } catch (IOException error) {
      throw new GeneralSecurityException("Unable to verify device protection wrapper", error);
    }
    try {
      if (!java.security.MessageDigest.isEqual(argon2Wrapper, verified)) {
        throw new GeneralSecurityException("Device protection wrapper verification failed");
      }
    } finally {
      Arrays.fill(verified, (byte) 0);
    }

    if (removeRawWrapper && !context.getSharedPreferences(PREFERENCES_NAME, 0)
                                    .edit().remove(MASTER_SECRET_V2).commit()) {
      throw new GeneralSecurityException("Unable to retire passphrase-only wrapper");
    }

    // An unbound PBKDF1 wrapper next to a Keystore-bound one bypasses the binding, so device
    // protection ends the rollback window immediately.
    if (!retireLegacyWrapper(context.getSharedPreferences(PREFERENCES_NAME, 0).edit()).commit()) {
      throw new GeneralSecurityException("Unable to retire legacy master-secret wrapper");
    }
  }

  public static byte[] wrapWithDeviceKey(Context context, byte[] plaintext)
      throws GeneralSecurityException
  {
    String alias = context.getSharedPreferences(DEVICE_PREFERENCES_NAME, 0)
                          .getString(DEVICE_KEY_ALIAS, AndroidKeystoreKeyManager.ALIAS_A);
    return DeviceKeyEnvelope.encrypt(plaintext,
                                     AndroidKeystoreKeyManager.getOrCreate(context, alias));
  }

  public static byte[] unwrapWithDeviceKey(Context context, byte[] serialized)
      throws GeneralSecurityException {
    String alias = context.getSharedPreferences(DEVICE_PREFERENCES_NAME, 0)
                          .getString(DEVICE_KEY_ALIAS, AndroidKeystoreKeyManager.ALIAS_A);
    javax.crypto.SecretKey key = AndroidKeystoreKeyManager.getExisting(alias);
    if (key == null) throw new GeneralSecurityException("Device protection key is missing");
    return DeviceKeyEnvelope.decrypt(serialized, key);
  }

  private static MasterSecret getLegacyMasterSecret(Context context, String passphrase)
      throws InvalidPassphraseException
  {
    try {
      byte[] encryptedAndMacdMasterSecret = retrieve(context, LEGACY_MASTER_SECRET);
      byte[] macSalt                      = retrieve(context, LEGACY_MAC_SALT);
      int    iterations                   = retrieve(context, LEGACY_ITERATIONS, 100);
      byte[] encryptionSalt               = retrieve(context, LEGACY_ENCRYPTION_SALT);

      if (encryptedAndMacdMasterSecret == null || macSalt == null || encryptionSalt == null) {
        throw new InvalidPassphraseException("Legacy master-secret wrapper is incomplete");
      }

      byte[] encryptedMasterSecret        = verifyMac(macSalt, iterations, encryptedAndMacdMasterSecret, passphrase);
      byte[] combinedSecrets              = decryptWithPassphrase(encryptionSalt, iterations, encryptedMasterSecret, passphrase);
      return masterSecretFromCombinedBytes(combinedSecrets);
    } catch (GeneralSecurityException e) {
      Log.w("keyutil", e);
      throw new InvalidPassphraseException(e);
    } catch (IOException e) {
      Log.w("keyutil", e);
      throw new InvalidPassphraseException(e);
    }
  }

  private static MasterSecret masterSecretFromCombinedBytes(byte[] combinedSecrets)
      throws GeneralSecurityException
  {
    if (combinedSecrets == null || combinedSecrets.length != 36) {
      throw new GeneralSecurityException("Invalid combined master-secret length");
    }
    byte[][] split = Util.split(combinedSecrets, 16, 20);
    return new MasterSecret(new SecretKeySpec(split[0], "AES"),
                            new SecretKeySpec(split[1], "HmacSHA1"));
  }

  private static byte[] combineMasterSecret(MasterSecret masterSecret) {
    return Util.combine(masterSecret.getEncryptionKey().getEncoded(),
                        masterSecret.getMacKey().getEncoded());
  }

  private static MasterSecretMigration.LegacyWrapper createLegacyWrapper(byte[] masterSecret,
                                                                           String passphrase)
      throws GeneralSecurityException
  {
    byte[] encryptionSalt = generateSalt();
    int iterations = generateIterationCount(passphrase, encryptionSalt);
    byte[] encryptedMasterSecret = encryptWithPassphrase(encryptionSalt, iterations,
                                                         masterSecret, passphrase);
    byte[] macSalt = generateSalt();
    byte[] encryptedAndMacdMasterSecret = macWithPassphrase(macSalt, iterations,
                                                            encryptedMasterSecret, passphrase);
    return new MasterSecretMigration.LegacyWrapper(encryptionSalt, macSalt, iterations,
                                                   encryptedAndMacdMasterSecret);
  }

  private static void migrateMasterSecret(Context context, byte[] masterSecret, String passphrase,
                                          MasterSecretMigration.LegacyWrapper legacyWrapper)
      throws GeneralSecurityException, InvalidPassphraseException
  {
    migrateMasterSecret(context, masterSecret, passphrase, legacyWrapper,
                        Argon2MasterSecretEnvelope::encrypt,
                        Argon2MasterSecretEnvelope::decrypt);
  }

  static void migrateMasterSecret(Context context, byte[] masterSecret, String passphrase,
                                  MasterSecretMigration.LegacyWrapper legacyWrapper,
                                  MasterSecretMigration.WrapperEncryptor encryptor,
                                  MasterSecretMigration.WrapperDecryptor decryptor)
      throws GeneralSecurityException, InvalidPassphraseException
  {
    SharedPreferences preferences = context.getSharedPreferences(PREFERENCES_NAME, 0);
    MasterSecretMigration.Storage storage = new MasterSecretMigration.Storage() {
      @Override
      public boolean writeCandidate(byte[] serialized) {
        return preferences.edit()
                          .putString(MASTER_SECRET_V2_PENDING, Base64.encodeBytes(serialized))
                          .commit();
      }

      @Override
      public byte[] readCandidate() throws GeneralSecurityException {
        String encoded = preferences.getString(MASTER_SECRET_V2_PENDING, "");
        if (TextUtils.isEmpty(encoded)) return null;
        try {
          return Base64.decode(encoded);
        } catch (IOException error) {
          throw new GeneralSecurityException(error);
        }
      }

      @Override
      public boolean activate(byte[] serialized,
                              MasterSecretMigration.LegacyWrapper updatedLegacyWrapper) {
        boolean deviceProtected = isDeviceProtectionEnabled(context);
        if (deviceProtected) {
          try {
            installDeviceProtection(context, serialized, false);
          } catch (GeneralSecurityException error) {
            Log.w("keyutil", "Unable to update device protection wrapper", error);
            return false;
          }
        }
        SharedPreferences.Editor editor = preferences.edit();
        if (deviceProtected) {
          retireLegacyWrapper(editor);
        } else if (updatedLegacyWrapper != null &&
                   !preferences.getBoolean(LEGACY_WRAPPER_RETIRED, false)) {
          putLegacyWrapper(editor, updatedLegacyWrapper);
        }
        if (deviceProtected) editor.remove(MASTER_SECRET_V2);
        else editor.putString(MASTER_SECRET_V2, Base64.encodeBytes(serialized));
        return editor.putInt(ACTIVE_MASTER_SECRET, ACTIVE_MASTER_SECRET_ARGON2)
                     .putBoolean("passphrase_initialized", true)
                     .remove(MASTER_SECRET_V2_PENDING)
                     .remove(MASTER_SECRET_V2_NEXT_ATTEMPT)
                     .commit();
      }
    };

    MasterSecretMigration.migrate(masterSecret, passphrase, legacyWrapper, storage,
                    encryptor, decryptor);
  }

  private static boolean saveLegacyWrapper(Context context,
                                           MasterSecretMigration.LegacyWrapper legacyWrapper) {
    SharedPreferences.Editor editor = context.getSharedPreferences(PREFERENCES_NAME, 0).edit();
    putLegacyWrapper(editor, legacyWrapper);
    return editor.putInt(ACTIVE_MASTER_SECRET, ACTIVE_MASTER_SECRET_LEGACY)
                 .putBoolean("passphrase_initialized", true)
                 .putBoolean(LEGACY_WRAPPER_RETIRED, false)
                 .remove(ARGON2_CONFIRMED_UNLOCKS)
                 .remove(MASTER_SECRET_V2)
                 .remove(MASTER_SECRET_V2_PENDING)
                 .remove(MASTER_SECRET_V2_NEXT_ATTEMPT)
                 .commit();
  }

  private static void putLegacyWrapper(SharedPreferences.Editor editor,
                                       MasterSecretMigration.LegacyWrapper legacyWrapper) {
    editor.putString(LEGACY_ENCRYPTION_SALT, Base64.encodeBytes(legacyWrapper.encryptionSalt))
          .putString(LEGACY_MAC_SALT, Base64.encodeBytes(legacyWrapper.macSalt))
          .putInt(LEGACY_ITERATIONS, legacyWrapper.iterations)
          .putString(LEGACY_MASTER_SECRET, Base64.encodeBytes(legacyWrapper.encryptedMasterSecret));
  }

  public static AsymmetricMasterSecret getAsymmetricMasterSecret(Context context,
                                                                 MasterSecret masterSecret)
  {
    try {
      byte[] djbPublicBytes   = retrieve(context, ASYMMETRIC_LOCAL_PUBLIC_DJB);
      byte[] djbPrivateBytes  = retrieve(context, ASYMMETRIC_LOCAL_PRIVATE_DJB);

      ECPublicKey  djbPublicKey  = null;
      ECPrivateKey djbPrivateKey = null;

      if (djbPublicBytes != null) {
        djbPublicKey = Curve.decodePoint(djbPublicBytes, 0);
      }

      if (masterSecret != null) {
        MasterCipher masterCipher = new MasterCipher(masterSecret);

        if (djbPrivateBytes != null) {
          djbPrivateKey = masterCipher.decryptKey(djbPrivateBytes);
        }
      }

      return new AsymmetricMasterSecret(djbPublicKey, djbPrivateKey);
    } catch (InvalidKeyException | IOException ike) {
      throw new AssertionError(ike);
    }
  }

  public static AsymmetricMasterSecret generateAsymmetricMasterSecret(Context context,
                                                                      MasterSecret masterSecret)
  {
    MasterCipher masterCipher = new MasterCipher(masterSecret);
    ECKeyPair    keyPair      = Curve.generateKeyPair();

    save(context, ASYMMETRIC_LOCAL_PUBLIC_DJB, keyPair.getPublicKey().serialize());
    save(context, ASYMMETRIC_LOCAL_PRIVATE_DJB, masterCipher.encryptKey(keyPair.getPrivateKey()));

    return new AsymmetricMasterSecret(keyPair.getPublicKey(), keyPair.getPrivateKey());
  }

  public static MasterSecret generateMasterSecret(Context context, String passphrase)
      throws MasterSecretStorageException
  {
    byte[] masterSecret = null;
    try {
      byte[] encryptionSecret             = generateEncryptionSecret();
      byte[] macSecret                    = generateMacSecret();
      masterSecret                        = Util.combine(encryptionSecret, macSecret);
      MasterSecretMigration.LegacyWrapper legacyWrapper = createLegacyWrapper(masterSecret, passphrase);

      if (modernCryptoWritesAvailable()) {
        migrateMasterSecret(context, masterSecret, passphrase, legacyWrapper);
      } else if (!saveLegacyWrapper(context, legacyWrapper)) {
        throw new GeneralSecurityException("Unable to save legacy master-secret wrapper");
      }

      return new MasterSecret(new SecretKeySpec(encryptionSecret, "AES"),
                              new SecretKeySpec(macSecret, "HmacSHA1"));
    } catch (GeneralSecurityException | InvalidPassphraseException error) {
      throw new MasterSecretStorageException("Unable to persist master-secret wrapper", error);
    } finally {
      if (masterSecret != null) Arrays.fill(masterSecret, (byte) 0);
    }
  }

  public static boolean hasAsymmericMasterSecret(Context context) {
    SharedPreferences settings = context.getSharedPreferences(PREFERENCES_NAME, 0);
    return settings.contains(ASYMMETRIC_LOCAL_PUBLIC_DJB);
  }

  private static boolean modernCryptoWritesAvailable() {
    return modernCryptoWritesAvailable(Argon2id.isAvailable());
  }

  static boolean modernCryptoWritesAvailable(boolean argon2Available) {
    return BuildConfig.MODERN_CRYPTO_WRITES && argon2Available;
  }

  public static boolean isPassphraseInitialized(Context context) {
    SharedPreferences preferences = context.getSharedPreferences(PREFERENCES_NAME, 0);
    return preferences.getBoolean("passphrase_initialized", false);
  }

  private static void save(Context context, String key, int value) {
    if (!context.getSharedPreferences(PREFERENCES_NAME, 0)
                .edit()
                .putInt(key, value)
                .commit())
    {
      throw new AssertionError("failed to save a shared pref in MasterSecretUtil");
    }
  }

  private static void save(Context context, String key, byte[] value) {
    if (!context.getSharedPreferences(PREFERENCES_NAME, 0)
                .edit()
                .putString(key, Base64.encodeBytes(value))
                .commit())
    {
      throw new AssertionError("failed to save a shared pref in MasterSecretUtil");
    }
  }

  private static void save(Context context, String key, boolean value) {
    if (!context.getSharedPreferences(PREFERENCES_NAME, 0)
                .edit()
                .putBoolean(key, value)
                .commit())
    {
      throw new AssertionError("failed to save a shared pref in MasterSecretUtil");
    }
  }

  private static byte[] retrieve(Context context, String key) throws IOException {
    SharedPreferences settings = context.getSharedPreferences(PREFERENCES_NAME, 0);
    String encodedValue        = settings.getString(key, "");

    if (TextUtils.isEmpty(encodedValue)) return null;
    else                                 return Base64.decode(encodedValue);
  }

  private static int retrieve(Context context, String key, int defaultValue) throws IOException {
    SharedPreferences settings = context.getSharedPreferences(PREFERENCES_NAME, 0);
    return settings.getInt(key, defaultValue);
  }

  private static byte[] generateEncryptionSecret() {
    try {
      KeyGenerator generator = KeyGenerator.getInstance("AES");
      generator.init(128);

      SecretKey key = generator.generateKey();
      return key.getEncoded();
    } catch (NoSuchAlgorithmException ex) {
      Log.w("keyutil", ex);
      return null;
    }
  }

  private static byte[] generateMacSecret() {
    try {
      KeyGenerator generator = KeyGenerator.getInstance("HmacSHA1");
      return generator.generateKey().getEncoded();
    } catch (NoSuchAlgorithmException e) {
      Log.w("keyutil", e);
      return null;
    }
  }

  private static byte[] generateSalt() throws NoSuchAlgorithmException {
    SecureRandom random = SecureRandom.getInstance("SHA1PRNG");
    byte[] salt         = new byte[16];
    random.nextBytes(salt);

    return salt;
  }

  private static int generateIterationCount(String passphrase, byte[] salt) {
    int TARGET_ITERATION_TIME     = 1000;   //ms
    int MINIMUM_ITERATION_COUNT   = 10000;  //default for low-end devices
    int BENCHMARK_ITERATION_COUNT = 100000; //baseline starting iteration count

    try {
      PBEKeySpec       keyspec = new PBEKeySpec(passphrase.toCharArray(), salt, BENCHMARK_ITERATION_COUNT);
      SecretKeyFactory skf     = SecretKeyFactory.getInstance("PBEWITHSHA1AND128BITAES-CBC-BC");

      long startTime = System.currentTimeMillis();
      skf.generateSecret(keyspec);
      long finishTime = System.currentTimeMillis();

      int scaledIterationTarget = (int) (((double)BENCHMARK_ITERATION_COUNT / (double)(finishTime - startTime)) * TARGET_ITERATION_TIME);

      if (scaledIterationTarget < MINIMUM_ITERATION_COUNT)        return MINIMUM_ITERATION_COUNT;
      else if (scaledIterationTarget > BENCHMARK_ITERATION_COUNT) return BENCHMARK_ITERATION_COUNT;
      else                                                        return scaledIterationTarget;
    } catch (NoSuchAlgorithmException e) {
      Log.w("MasterSecretUtil", e);
      return MINIMUM_ITERATION_COUNT;
    } catch (InvalidKeySpecException e) {
      Log.w("MasterSecretUtil", e);
      return MINIMUM_ITERATION_COUNT;
    }
  }

  private static SecretKey getKeyFromPassphrase(String passphrase, byte[] salt, int iterations)
      throws GeneralSecurityException
  {
    PBEKeySpec keyspec    = new PBEKeySpec(passphrase.toCharArray(), salt, iterations);
    SecretKeyFactory skf  = SecretKeyFactory.getInstance("PBEWITHSHA1AND128BITAES-CBC-BC");
    return skf.generateSecret(keyspec);
  }

  private static Cipher getCipherFromPassphrase(String passphrase, byte[] salt, int iterations, int opMode)
      throws GeneralSecurityException
  {
    SecretKey key    = getKeyFromPassphrase(passphrase, salt, iterations);
    Cipher    cipher = Cipher.getInstance(key.getAlgorithm());
    cipher.init(opMode, key, new PBEParameterSpec(salt, iterations));

    return cipher;
  }

  private static byte[] encryptWithPassphrase(byte[] encryptionSalt, int iterations, byte[] data, String passphrase)
      throws GeneralSecurityException
  {
    Cipher cipher = getCipherFromPassphrase(passphrase, encryptionSalt, iterations, Cipher.ENCRYPT_MODE);
    return cipher.doFinal(data);
  }

  private static byte[] decryptWithPassphrase(byte[] encryptionSalt, int iterations, byte[] data, String passphrase)
      throws GeneralSecurityException, IOException
  {
    Cipher cipher = getCipherFromPassphrase(passphrase, encryptionSalt, iterations, Cipher.DECRYPT_MODE);
    return cipher.doFinal(data);
  }

  private static Mac getMacForPassphrase(String passphrase, byte[] salt, int iterations)
      throws GeneralSecurityException
  {
    SecretKey     key     = getKeyFromPassphrase(passphrase, salt, iterations);
    byte[]        pbkdf2  = key.getEncoded();
    SecretKeySpec hmacKey = new SecretKeySpec(pbkdf2, "HmacSHA1");
    Mac           hmac    = Mac.getInstance("HmacSHA1");
    hmac.init(hmacKey);

    return hmac;
  }

  private static byte[] verifyMac(byte[] macSalt, int iterations, byte[] encryptedAndMacdData, String passphrase) throws InvalidPassphraseException, GeneralSecurityException, IOException {
    Mac hmac        = getMacForPassphrase(passphrase, macSalt, iterations);

    byte[] encryptedData = new byte[encryptedAndMacdData.length - hmac.getMacLength()];
    System.arraycopy(encryptedAndMacdData, 0, encryptedData, 0, encryptedData.length);

    byte[] givenMac      = new byte[hmac.getMacLength()];
    System.arraycopy(encryptedAndMacdData, encryptedAndMacdData.length-hmac.getMacLength(), givenMac, 0, givenMac.length);

    byte[] localMac      = hmac.doFinal(encryptedData);

    if (Arrays.equals(givenMac, localMac)) return encryptedData;
    else                                   throw new InvalidPassphraseException("MAC Error");
  }

  private static byte[] macWithPassphrase(byte[] macSalt, int iterations, byte[] data, String passphrase) throws GeneralSecurityException {
    Mac hmac       = getMacForPassphrase(passphrase, macSalt, iterations);
    byte[] mac     = hmac.doFinal(data);
    byte[] result  = new byte[data.length + mac.length];

    System.arraycopy(data, 0, result, 0, data.length);
    System.arraycopy(mac,  0, result, data.length, mac.length);

    return result;
  }
}
