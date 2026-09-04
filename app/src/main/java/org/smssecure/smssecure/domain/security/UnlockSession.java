package org.smssecure.smssecure.domain.security;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.service.KeyCachingService;

import java.util.Objects;

public final class UnlockSession {
  private final long generation;
  private final SnapshotProvider snapshotProvider;

  public static UnlockSession capture() {
    Snapshot snapshot = KeyCachingService.getSecretSnapshot();
    return new UnlockSession(snapshot.getGeneration(), KeyCachingService::getSecretSnapshot);
  }

  public UnlockSession(long generation, @NonNull SnapshotProvider snapshotProvider) {
    this.generation = generation;
    this.snapshotProvider = Objects.requireNonNull(snapshotProvider);
  }

  public long getGeneration() {
    return generation;
  }

  public boolean isCurrent() {
    Snapshot snapshot = snapshotProvider.current();
    return snapshot.getSecret() != null && snapshot.getGeneration() == generation;
  }

  public void requireCurrent() throws LockedException {
    if (!isCurrent()) throw new LockedException();
  }

  public <T> T use(@NonNull Operation<T> operation) throws LockedException, Exception {
    Snapshot snapshot = snapshotProvider.current();
    if (snapshot.getSecret() == null || snapshot.getGeneration() != generation) {
      throw new LockedException();
    }
    return operation.run(snapshot.getSecret());
  }

  public interface Operation<T> {
    T run(MasterSecret masterSecret) throws Exception;
  }

  public interface SnapshotProvider {
    Snapshot current();
  }

  public static final class Snapshot {
    private final long generation;
    private final MasterSecret secret;

    public Snapshot(long generation, @Nullable MasterSecret secret) {
      this.generation = generation;
      this.secret = secret;
    }

    public long getGeneration() {
      return generation;
    }

    public @Nullable MasterSecret getSecret() {
      return secret;
    }
  }

  public static final class LockedException extends Exception {}
}