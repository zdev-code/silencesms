package org.smssecure.smssecure.domain.conversation;

import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.service.KeyCachingService;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;

public final class ConversationUnlockCapability {
  private final MasterSecret masterSecret;
  private final SecretProvider secretProvider;

  public ConversationUnlockCapability(MasterSecret masterSecret) {
    this(masterSecret, KeyCachingService::getCachedMasterSecret);
  }

  public ConversationUnlockCapability(MasterSecret masterSecret, SecretProvider secretProvider) {
    this.masterSecret = Objects.requireNonNull(masterSecret);
    this.secretProvider = Objects.requireNonNull(secretProvider);
  }

  public <T> T use(SecretOperation<T> operation) throws Exception {
    MasterSecret current = secretProvider.getCurrentSecret();
    if (current == null || !sameKeyMaterial(masterSecret, current)) {
      throw new LockedException();
    }
    return operation.run(current);
  }

  private static boolean sameKeyMaterial(MasterSecret expected, MasterSecret current) {
    byte[] expectedEncryption = expected.getEncryptionKey().getEncoded();
    byte[] currentEncryption = current.getEncryptionKey().getEncoded();
    byte[] expectedMac = expected.getMacKey().getEncoded();
    byte[] currentMac = current.getMacKey().getEncoded();
    try {
      return MessageDigest.isEqual(expectedEncryption, currentEncryption) &&
             MessageDigest.isEqual(expectedMac, currentMac);
    } finally {
      Arrays.fill(expectedEncryption, (byte) 0);
      Arrays.fill(currentEncryption, (byte) 0);
      Arrays.fill(expectedMac, (byte) 0);
      Arrays.fill(currentMac, (byte) 0);
    }
  }

  public interface SecretOperation<T> {
    T run(MasterSecret masterSecret) throws Exception;
  }

  public interface SecretProvider {
    MasterSecret getCurrentSecret();
  }

  public static final class LockedException extends Exception {}
}