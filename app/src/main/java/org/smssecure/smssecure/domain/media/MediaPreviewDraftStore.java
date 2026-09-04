package org.smssecure.smssecure.domain.media;

import android.net.Uri;
import android.os.SystemClock;

import androidx.annotation.NonNull;

import org.smssecure.smssecure.domain.security.UnlockSession;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public final class MediaPreviewDraftStore {
  private static final long TTL_MILLIS = 60_000L;

  private final Map<String, Entry> entries = new HashMap<>();
  private final TimeSource timeSource;
  private final SessionProvider sessionProvider;
  private final long ttlMillis;

  @Inject
  public MediaPreviewDraftStore() {
    this(SystemClock::elapsedRealtime, UnlockSession::capture, TTL_MILLIS);
  }

  MediaPreviewDraftStore(@NonNull TimeSource timeSource,
                         @NonNull SessionProvider sessionProvider,
                         long ttlMillis) {
    this.timeSource = Objects.requireNonNull(timeSource);
    this.sessionProvider = Objects.requireNonNull(sessionProvider);
    if (ttlMillis <= 0L) throw new IllegalArgumentException("TTL must be positive");
    this.ttlMillis = ttlMillis;
  }

  public synchronized void put(@NonNull String owner, @NonNull Uri uri,
                               @NonNull String contentType, long size) {
    discard(owner);
    entries.put(owner, new Entry(new Payload(uri, contentType, size), sessionProvider.capture(),
                   timeSource.now() + ttlMillis));
  }

  public synchronized Payload consume(@NonNull String owner) throws InvalidPayloadException {
    Entry entry = entries.remove(Objects.requireNonNull(owner));
    if (entry == null || entry.expiresAt < timeSource.now()) {
      throw new InvalidPayloadException();
    }
    try {
      return entry.unlockSession.use(ignored -> entry.payload);
    } catch (Exception error) {
      throw new InvalidPayloadException();
    }
  }

  public synchronized void discard(@NonNull String owner) {
    entries.remove(Objects.requireNonNull(owner));
  }

  public synchronized void clear() {
    entries.clear();
  }

  public static final class Payload {
    private final Uri uri;
    private final String contentType;
    private final long size;

    private Payload(Uri uri, String contentType, long size) {
      this.uri = uri;
      this.contentType = contentType;
      this.size = size;
    }

    public Uri getUri() { return uri; }
    public String getContentType() { return contentType; }
    public long getSize() { return size; }
  }

  public static final class InvalidPayloadException extends Exception {}

  interface TimeSource { long now(); }

  interface SessionProvider { UnlockSession capture(); }

  private static final class Entry {
    private final Payload payload;
    private final UnlockSession unlockSession;
    private final long expiresAt;

    private Entry(Payload payload, UnlockSession unlockSession, long expiresAt) {
      this.payload = payload;
      this.unlockSession = unlockSession;
      this.expiresAt = expiresAt;
    }
  }
}