package org.smssecure.smssecure.domain.identity;

import android.os.SystemClock;

import androidx.annotation.NonNull;

import org.signal.libsignal.protocol.IdentityKey;
import org.smssecure.smssecure.domain.security.UnlockSession;
import org.smssecure.smssecure.service.KeyCachingService;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class ConflictIdentityStore {
  static final long DEFAULT_TTL_MILLIS = 60_000L;
  private static final int MAX_TOKEN_LENGTH = 128;
  private static final ConflictIdentityStore INSTANCE =
      new ConflictIdentityStore(SystemClock::elapsedRealtime,
                                KeyCachingService::getSecretSnapshot,
                                DEFAULT_TTL_MILLIS);

  private final Map<String, Entry> entries = new HashMap<>();
  private final TimeSource timeSource;
  private final UnlockSession.SnapshotProvider snapshotProvider;
  private final long ttlMillis;

  public static ConflictIdentityStore getInstance() {
    return INSTANCE;
  }

  ConflictIdentityStore(@NonNull TimeSource timeSource,
                        @NonNull UnlockSession.SnapshotProvider snapshotProvider,
                        long ttlMillis) {
    this.timeSource = Objects.requireNonNull(timeSource);
    this.snapshotProvider = Objects.requireNonNull(snapshotProvider);
    if (ttlMillis <= 0L) throw new IllegalArgumentException("TTL must be positive");
    this.ttlMillis = ttlMillis;
  }

  public synchronized String put(@NonNull String owner, long recipientId, int subscriptionId,
                                 @NonNull IdentityKey identityKey)
      throws InvalidPayloadException {
    Objects.requireNonNull(owner);
    Objects.requireNonNull(identityKey);
    if (owner.isEmpty() || recipientId < 0L || subscriptionId < 0) {
      throw new InvalidPayloadException();
    }
    UnlockSession.Snapshot snapshot = snapshotProvider.current();
    if (snapshot.getSecret() == null) throw new InvalidPayloadException();
    removeExpired();
    String token = UUID.randomUUID().toString();
    entries.put(token, new Entry(owner, recipientId, subscriptionId, identityKey,
                                 snapshot.getGeneration(), timeSource.now() + ttlMillis));
    return token;
  }

  public synchronized Payload consume(@NonNull String token, @NonNull String owner)
      throws InvalidPayloadException {
    Objects.requireNonNull(token);
    Objects.requireNonNull(owner);
    if (token.isEmpty() || token.length() > MAX_TOKEN_LENGTH) throw new InvalidPayloadException();
    Entry entry = entries.remove(token);
    UnlockSession.Snapshot snapshot = snapshotProvider.current();
    if (entry == null || !entry.owner.equals(owner) || entry.expiresAt <= timeSource.now() ||
        snapshot.getSecret() == null || snapshot.getGeneration() != entry.unlockGeneration) {
      throw new InvalidPayloadException();
    }
    return new Payload(entry.recipientId, entry.subscriptionId, entry.identityKey);
  }

  public synchronized void clear() {
    entries.clear();
  }

  private void removeExpired() {
    long now = timeSource.now();
    entries.entrySet().removeIf(item -> item.getValue().expiresAt <= now);
  }

  interface TimeSource {
    long now();
  }

  public static final class Payload {
    private final long recipientId;
    private final int subscriptionId;
    private final IdentityKey identityKey;

    private Payload(long recipientId, int subscriptionId, IdentityKey identityKey) {
      this.recipientId = recipientId;
      this.subscriptionId = subscriptionId;
      this.identityKey = identityKey;
    }

    public long getRecipientId() {
      return recipientId;
    }

    public int getSubscriptionId() {
      return subscriptionId;
    }

    public IdentityKey getIdentityKey() {
      return identityKey;
    }
  }

  public static final class InvalidPayloadException extends Exception {}

  private static final class Entry {
    private final String owner;
    private final long recipientId;
    private final int subscriptionId;
    private final IdentityKey identityKey;
    private final long unlockGeneration;
    private final long expiresAt;

    private Entry(String owner, long recipientId, int subscriptionId, IdentityKey identityKey,
                  long unlockGeneration, long expiresAt) {
      this.owner = owner;
      this.recipientId = recipientId;
      this.subscriptionId = subscriptionId;
      this.identityKey = identityKey;
      this.unlockGeneration = unlockGeneration;
      this.expiresAt = expiresAt;
    }
  }
}