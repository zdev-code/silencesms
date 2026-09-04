package org.smssecure.smssecure;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.smssecure.smssecure.domain.security.UnlockSession;
import org.smssecure.smssecure.util.Base64;

import java.security.MessageDigest;
import java.util.Arrays;

final class IdentityScanBinding {
  enum Result { VERIFIED, NOT_VERIFIED, EMPTY, REJECTED }

  private final UnlockSession unlockSession;
  private final long recipientId;
  private final int subscriptionId;
  private byte[] expectedIdentity;
  private boolean consumed;

  IdentityScanBinding(@NonNull UnlockSession unlockSession, long recipientId, int subscriptionId,
                      @NonNull byte[] expectedIdentity) {
    this.unlockSession = unlockSession;
    this.recipientId = recipientId;
    this.subscriptionId = subscriptionId;
    this.expectedIdentity = expectedIdentity.clone();
  }

  Result consume(long currentRecipientId, int currentSubscriptionId,
                 @Nullable byte[] currentIdentity, @Nullable String scannedIdentity) {
    if (consumed) return Result.REJECTED;
    consumed = true;
    try {
      if (recipientId != currentRecipientId || subscriptionId != currentSubscriptionId ||
          currentIdentity == null) return Result.REJECTED;
      unlockSession.use(masterSecret -> null);
      if (!MessageDigest.isEqual(expectedIdentity, currentIdentity)) return Result.REJECTED;
      if (scannedIdentity == null) return Result.EMPTY;
      return scannedIdentity.equals(Base64.encodeBytes(expectedIdentity))
          ? Result.VERIFIED : Result.NOT_VERIFIED;
    } catch (Exception exception) {
      return Result.REJECTED;
    } finally {
      if (currentIdentity != null) Arrays.fill(currentIdentity, (byte) 0);
      clear();
    }
  }

  void clear() {
    if (expectedIdentity != null) Arrays.fill(expectedIdentity, (byte) 0);
    expectedIdentity = null;
    consumed = true;
  }
}