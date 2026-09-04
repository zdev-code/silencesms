package org.smssecure.smssecure.domain.conversation;

import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.smssecure.smssecure.domain.security.UnlockSession;
import org.smssecure.smssecure.service.KeyCachingService;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;


@Singleton
public final class ConversationPayloadStore {
  static final long DEFAULT_TTL_MILLIS = 60_000L;

  private final Map<String, Entry> entries = new HashMap<>();
  private final TimeSource timeSource;
  private final UnlockSession.SnapshotProvider snapshotProvider;
  private final long ttlMillis;

  @Inject
  public ConversationPayloadStore() {
    this(SystemClock::elapsedRealtime, KeyCachingService::getSecretSnapshot, DEFAULT_TTL_MILLIS);
  }

  ConversationPayloadStore(@NonNull TimeSource timeSource, long ttlMillis) {
    this(timeSource, KeyCachingService::getSecretSnapshot, ttlMillis);
  }

  ConversationPayloadStore(@NonNull TimeSource timeSource,
                           @NonNull UnlockSession.SnapshotProvider snapshotProvider,
                           long ttlMillis) {
    this.timeSource = Objects.requireNonNull(timeSource);
    this.snapshotProvider = Objects.requireNonNull(snapshotProvider);
    this.ttlMillis = ttlMillis;
  }

  public synchronized String put(@NonNull String owner,
                                 @NonNull ConversationPayload payload,
                                 @NonNull Runnable cleanup) {
    Objects.requireNonNull(owner);
    Objects.requireNonNull(payload);
    Objects.requireNonNull(cleanup);
    removeExpired();
    String token = UUID.randomUUID().toString();
    UnlockSession.Snapshot snapshot = snapshotProvider.current();
    Long unlockGeneration = snapshot.getSecret() == null ? null : snapshot.getGeneration();
    entries.put(token, new Entry(owner, payload, cleanup, unlockGeneration,
                                 timeSource.now() + ttlMillis));
    return token;
  }

  public synchronized ConversationPayload consume(@NonNull String token, @NonNull String owner)
      throws InvalidPayloadException {
    Objects.requireNonNull(token);
    Objects.requireNonNull(owner);
    Entry entry = entries.remove(token);
    if (entry == null) throw new InvalidPayloadException();
    if (!entry.owner.equals(owner) || entry.expiresAt < timeSource.now() ||
        !bindOrValidateGeneration(entry)) {
      entry.cleanup.run();
      throw new InvalidPayloadException();
    }
    return entry.payload;
  }

  public synchronized String retarget(@NonNull String token,
                                      @NonNull String currentOwner,
                                      @NonNull String newOwner,
                                      long threadId,
                                      @NonNull long[] recipientIds,
                                      int distributionType) throws InvalidPayloadException {
    Objects.requireNonNull(token);
    Objects.requireNonNull(currentOwner);
    Objects.requireNonNull(newOwner);
    Objects.requireNonNull(recipientIds);
    Entry entry = entries.remove(token);
    if (entry == null) throw new InvalidPayloadException();
    if (!entry.owner.equals(currentOwner) || entry.expiresAt < timeSource.now() ||
        !bindOrValidateGeneration(entry)) {
      entry.cleanup.run();
      throw new InvalidPayloadException();
    }
    ConversationPayload payload = new ConversationPayload(
        threadId, recipientIds, distributionType, entry.payload.getText(),
        entry.payload.getMedia(), entry.payload.getMediaType());
    entries.put(token, new Entry(newOwner, payload, entry.cleanup,
                                 entry.unlockGeneration, entry.expiresAt));
    return token;
  }

  public synchronized ConversationPayload inspect(@NonNull String token, @NonNull String owner)
      throws InvalidPayloadException {
    Entry entry = requireEntry(token, owner);
    return entry.payload;
  }

  public synchronized void replaceMedia(@NonNull String token,
                                        @NonNull String owner,
                                        @NonNull android.net.Uri media,
                                        @NonNull String mediaType,
                                        @NonNull Runnable cleanup)
      throws InvalidPayloadException {
    Objects.requireNonNull(media);
    Objects.requireNonNull(mediaType);
    Objects.requireNonNull(cleanup);
    Entry entry = entries.remove(Objects.requireNonNull(token));
    if (entry == null || !entry.owner.equals(Objects.requireNonNull(owner)) ||
        entry.expiresAt < timeSource.now() || !bindOrValidateGeneration(entry)) {
      if (entry != null) entry.cleanup.run();
      cleanup.run();
      throw new InvalidPayloadException();
    }
    ConversationPayload replacement = new ConversationPayload(
        entry.payload.getThreadId(), entry.payload.getRecipientIds(),
        entry.payload.getDistributionType(), entry.payload.getText(), media, mediaType);
    entry.cleanup.run();
    entries.put(token, new Entry(owner, replacement, cleanup,
                                 entry.unlockGeneration, entry.expiresAt));
  }

  public synchronized void requireValid(@NonNull String token, @NonNull String owner)
      throws InvalidPayloadException {
    requireEntry(token, owner);
  }

  public synchronized void discard(@NonNull String token) {
    Entry entry = entries.remove(Objects.requireNonNull(token));
    if (entry != null) entry.cleanup.run();
  }

  public synchronized void clear() {
    for (Entry entry : entries.values()) entry.cleanup.run();
    entries.clear();
  }

  private void removeExpired() {
    long now = timeSource.now();
    entries.entrySet().removeIf(item -> {
      if (item.getValue().expiresAt >= now) return false;
      item.getValue().cleanup.run();
      return true;
    });
  }

  private Entry requireEntry(String token, String owner) throws InvalidPayloadException {
    Objects.requireNonNull(token);
    Objects.requireNonNull(owner);
    Entry entry = entries.get(token);
    if (entry == null) throw new InvalidPayloadException();
    if (!entry.owner.equals(owner) || entry.expiresAt < timeSource.now() ||
        !bindOrValidateGeneration(entry)) {
      entries.remove(token);
      entry.cleanup.run();
      throw new InvalidPayloadException();
    }
    return entry;
  }

  private boolean bindOrValidateGeneration(Entry entry) {
    UnlockSession.Snapshot snapshot = snapshotProvider.current();
    if (snapshot.getSecret() == null) return false;
    if (entry.unlockGeneration == null) {
      entry.unlockGeneration = snapshot.getGeneration();
      return true;
    }
    return entry.unlockGeneration.longValue() == snapshot.getGeneration();
  }

  interface TimeSource { long now(); }

  public static final class InvalidPayloadException extends Exception {}

  private static final class Entry {
    private final String owner;
    private final ConversationPayload payload;
    private final Runnable cleanup;
    private @Nullable Long unlockGeneration;
    private final long expiresAt;

    private Entry(String owner, ConversationPayload payload, Runnable cleanup,
                  @Nullable Long unlockGeneration, long expiresAt) {
      this.owner = owner;
      this.payload = payload;
      this.cleanup = cleanup;
      this.unlockGeneration = unlockGeneration;
      this.expiresAt = expiresAt;
    }
  }
}