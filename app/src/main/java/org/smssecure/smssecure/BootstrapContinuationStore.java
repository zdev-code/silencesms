package org.smssecure.smssecure;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.smssecure.smssecure.domain.security.UnlockSession;
import org.smssecure.smssecure.service.KeyCachingService;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class BootstrapContinuationStore {
  static final String EXTRA_DESTINATION =
      "org.smssecure.smssecure.bootstrap.DESTINATION";
  static final String EXTRA_TOKEN =
      "org.smssecure.smssecure.bootstrap.TOKEN";
  static final long DEFAULT_TTL_MILLIS = TimeUnit.MINUTES.toMillis(15);
  private static final int MAX_RECIPIENT_IDS = 64;
  private static final int MAX_TOKEN_LENGTH = 128;

  private static final BootstrapContinuationStore INSTANCE =
      new BootstrapContinuationStore(SystemClock::elapsedRealtime,
                                     KeyCachingService::getSecretSnapshot,
                                     DEFAULT_TTL_MILLIS);

  private final Map<String, Entry> entries = new HashMap<>();
  private final TimeSource timeSource;
  private final UnlockSession.SnapshotProvider snapshotProvider;
  private final long ttlMillis;

  public static BootstrapContinuationStore getInstance() {
    return INSTANCE;
  }

  BootstrapContinuationStore(@NonNull TimeSource timeSource,
                             @NonNull UnlockSession.SnapshotProvider snapshotProvider,
                             long ttlMillis) {
    this.timeSource = Objects.requireNonNull(timeSource);
    this.snapshotProvider = Objects.requireNonNull(snapshotProvider);
    if (ttlMillis <= 0L) throw new IllegalArgumentException("TTL must be positive");
    this.ttlMillis = ttlMillis;
  }

  public synchronized Intent createBootstrapIntent(@NonNull Context context,
                                                   @NonNull Class<?> gate,
                                                   @NonNull Intent target) {
    return createBootstrapIntent(context, gate, target, owner(gate));
  }

  synchronized Intent createBootstrapIntent(@NonNull Context context,
                                             @NonNull Class<?> gate,
                                             @NonNull Intent target,
                                             @NonNull String owner) {
    return createIntent(context, gate, target, owner, false);
  }

  synchronized Intent createUnlockedIntent(@NonNull Context context,
                                            @NonNull Class<?> gate,
                                            @NonNull Intent target,
                                            @NonNull String owner) {
    return createIntent(context, gate, target, owner, true);
  }

  private Intent createIntent(Context context, Class<?> gate, Intent target, String owner,
                              boolean requireUnlocked) {
    Objects.requireNonNull(context);
    Objects.requireNonNull(owner);
    Destination destination = Destination.from(target);
    Intent sanitizedTarget = sanitize(target, destination);
    UnlockSession.Snapshot snapshot = snapshotProvider.current();
    if (requireUnlocked && snapshot.getSecret() == null) {
      throw new SecurityException("Unlocked continuation required");
    }
    Long unlockGeneration = snapshot.getSecret() == null ? null : snapshot.getGeneration();
    String token = UUID.randomUUID().toString();
    removeExpired();
    entries.put(token, new Entry(owner, destination, sanitizedTarget, unlockGeneration,
                                 timeSource.now() + ttlMillis));
    return new Intent(context, gate)
        .putExtra(EXTRA_DESTINATION, destination.name())
        .putExtra(EXTRA_TOKEN, token);
  }

  synchronized void requireLive(@NonNull Intent intent, @NonNull String owner,
                                boolean requireUnlocked)
      throws InvalidContinuationException {
    removeExpired();
    String token = intent.getStringExtra(EXTRA_TOKEN);
    String destinationName = intent.getStringExtra(EXTRA_DESTINATION);
    Entry entry = token == null ? null : entries.get(token);
    if (token == null || token.length() > MAX_TOKEN_LENGTH || destinationName == null ||
        entry == null || !entry.owner.equals(owner) ||
        !entry.destination.name().equals(destinationName) ||
        requireUnlocked && entry.unlockGeneration == null || !hasCurrentGeneration(entry)) {
      if (token != null) entries.remove(token);
      throw new InvalidContinuationException();
    }
  }

  synchronized void discard(@NonNull Intent intent) {
    String token = intent.getStringExtra(EXTRA_TOKEN);
    if (token != null) entries.remove(token);
    intent.removeExtra(EXTRA_TOKEN);
    intent.removeExtra(EXTRA_DESTINATION);
  }

  synchronized void rebindToCurrentGeneration(@NonNull Intent intent, @NonNull String owner)
      throws InvalidContinuationException {
    removeExpired();
    String token = intent.getStringExtra(EXTRA_TOKEN);
    String destinationName = intent.getStringExtra(EXTRA_DESTINATION);
    Entry entry = token == null ? null : entries.get(token);
    UnlockSession.Snapshot snapshot = snapshotProvider.current();
    if (entry == null || !entry.owner.equals(owner) || destinationName == null ||
        !entry.destination.name().equals(destinationName) || snapshot.getSecret() == null) {
      if (token != null) entries.remove(token);
      throw new InvalidContinuationException();
    }
    entry.unlockGeneration = snapshot.getGeneration();
  }

  public synchronized Intent consume(@NonNull Intent bootstrapIntent,
                                     @NonNull Class<?> owner)
      throws InvalidContinuationException {
    return consume(bootstrapIntent, owner(owner));
  }

  synchronized Intent consume(@NonNull Intent bootstrapIntent,
                              @NonNull String owner)
      throws InvalidContinuationException {
    Objects.requireNonNull(bootstrapIntent);
    Objects.requireNonNull(owner);
    String token = bootstrapIntent.getStringExtra(EXTRA_TOKEN);
    String destinationName = bootstrapIntent.getStringExtra(EXTRA_DESTINATION);
    bootstrapIntent.removeExtra(EXTRA_TOKEN);
    bootstrapIntent.removeExtra(EXTRA_DESTINATION);
    if (token == null || destinationName == null || token.length() > MAX_TOKEN_LENGTH) {
      throw new InvalidContinuationException();
    }

    Entry entry = entries.remove(token);
    if (entry == null || !entry.owner.equals(owner) || entry.expiresAt < timeSource.now() ||
        !entry.destination.name().equals(destinationName)) {
      throw new InvalidContinuationException();
    }
    if (entry.unlockGeneration != null) {
      UnlockSession.Snapshot snapshot = snapshotProvider.current();
      if (snapshot.getSecret() == null ||
          snapshot.getGeneration() != entry.unlockGeneration.longValue()) {
        throw new InvalidContinuationException();
      }
    }
    return new Intent(entry.target);
  }

  public synchronized void clear() {
    entries.clear();
  }

  static boolean hasValidRouteFields(@Nullable String destination, @Nullable String token) {
    if (destination == null || destination.isEmpty() || token == null || token.isEmpty() ||
        token.length() > MAX_TOKEN_LENGTH) {
      return false;
    }
    try {
      Destination.valueOf(destination);
      return true;
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  private void removeExpired() {
    long now = timeSource.now();
    entries.entrySet().removeIf(item -> item.getValue().expiresAt < now);
  }

  private static String owner(Class<?> owner) {
    return Objects.requireNonNull(owner).getName();
  }

  private Intent sanitize(Intent source, Destination destination) {
    if (source.getComponent() == null || source.getComponent().getClassName() == null) {
      throw new SecurityException("Bootstrap continuations must be explicit");
    }
    if (!source.getComponent().getClassName().equals(destination.activityClass.getName())) {
      throw new SecurityException("Bootstrap continuation component mismatch");
    }
    if (source.hasExtra("next_intent") || source.hasExtra("master_secret")) {
      throw new SecurityException("Nested intents and secrets are forbidden");
    }

    switch (destination) {
      case CONVERSATION_LIST: return sanitizeConversationList(source);
      case CONVERSATION:
      case CONVERSATION_POPUP: return sanitizeConversation(source, destination);
      case DATABASE_MIGRATION: return sanitizeDatabaseMigration(source);
      default: throw new AssertionError("Unhandled bootstrap destination: " + destination);
    }
  }

  private static Intent sanitizeConversationList(Intent source) {
    Intent sanitized = explicitCopy(source, ConversationListActivity.class);
    copyPrimitiveExtras(source, sanitized, Set.of(
        "org.smssecure.smssecure.navigation.HOST_DESTINATION",
        NewConversationFragment.PAYLOAD_TOKEN_ARGUMENT,
        ConversationScreenFragment.RECIPIENTS_ARGUMENT,
        ConversationScreenFragment.THREAD_ID_ARGUMENT,
        ConversationScreenFragment.IS_ARCHIVED_ARGUMENT,
        ConversationScreenFragment.DISTRIBUTION_TYPE_ARGUMENT,
        ConversationScreenFragment.TIMING_ARGUMENT,
        ConversationScreenFragment.LAST_SEEN_ARGUMENT,
        ConversationScreenFragment.PAYLOAD_TOKEN_ARGUMENT,
        RecipientPreferenceFragment.RECIPIENT_IDS_ARGUMENT,
        MediaOverviewFragment.THREAD_ID_ARGUMENT,
        MediaOverviewFragment.RECIPIENT_ID_ARGUMENT,
        VerifyIdentityFragment.RECIPIENT_ID_ARGUMENT,
        VerifyIdentityFragment.SUBSCRIPTION_ID_ARGUMENT,
        VerifyIdentityFragment.CONFLICT_TOKEN_ARGUMENT));
    Intent validationCopy = new Intent(sanitized);
    HostNavigationCommand.Destination command = HostNavigationCommand.consume(validationCopy);
    switch (command) {
      case INBOX:
      case ARCHIVE:
      case BLOCKED_CONTACTS:
      case MMS_PREFERENCES:
      case APP_PROTECTION_PREFERENCES:
        requireNoExtras(validationCopy);
        break;
      case NEW_CONVERSATION:
        HostNavigationCommand.consumeNewConversationArguments(validationCopy);
        requireNoExtras(validationCopy);
        break;
      case CONVERSATION:
        HostNavigationCommand.consumeConversationArguments(validationCopy);
        requireNoExtras(validationCopy);
        break;
      case RECIPIENT_PREFERENCES:
        HostNavigationCommand.consumeRecipientPreferencesArguments(validationCopy);
        requireNoExtras(validationCopy);
        break;
      case MEDIA_OVERVIEW:
        HostNavigationCommand.consumeMediaOverviewArguments(validationCopy);
        requireNoExtras(validationCopy);
        break;
      case VERIFY_IDENTITY:
        HostNavigationCommand.consumeVerifyIdentityArguments(validationCopy);
        requireNoExtras(validationCopy);
        break;
      default:
        throw new SecurityException("Unsupported host continuation");
    }
    return sanitized;
  }

  private static Intent sanitizeConversation(Intent source, Destination destination) {
    Class<?> target = destination == Destination.CONVERSATION_POPUP
        ? ConversationPopupActivity.class : ConversationActivity.class;
    Intent sanitized = explicitCopy(source, target);
    Bundle arguments = ConversationScreenFragment.arguments(source, null);
    ConversationScreenFragment.requireValidArguments(arguments);
    sanitized.putExtra(ConversationActivity.RECIPIENTS_EXTRA,
                       arguments.getLongArray(ConversationScreenFragment.RECIPIENTS_ARGUMENT));
    sanitized.putExtra(ConversationActivity.THREAD_ID_EXTRA,
                       arguments.getLong(ConversationScreenFragment.THREAD_ID_ARGUMENT));
    sanitized.putExtra(ConversationActivity.IS_ARCHIVED_EXTRA,
                       arguments.getBoolean(ConversationScreenFragment.IS_ARCHIVED_ARGUMENT));
    sanitized.putExtra(ConversationActivity.DISTRIBUTION_TYPE_EXTRA,
                       arguments.getInt(ConversationScreenFragment.DISTRIBUTION_TYPE_ARGUMENT));
    sanitized.putExtra(ConversationActivity.TIMING_EXTRA,
                       arguments.getLong(ConversationScreenFragment.TIMING_ARGUMENT));
    sanitized.putExtra(ConversationActivity.LAST_SEEN_EXTRA,
                       arguments.getLong(ConversationScreenFragment.LAST_SEEN_ARGUMENT));
    return sanitized;
  }

  private Intent sanitizeDatabaseMigration(Intent source) {
    if (!AuthenticationActivity.isAllowedDatabaseMigrationIntent(source)) {
      throw new SecurityException("Invalid database migration surface");
    }
    String token = source.getStringExtra(EXTRA_TOKEN);
    String destination = source.getStringExtra(EXTRA_DESTINATION);
    Entry nested = entries.get(token);
    if (nested == null ||
        !nested.owner.equals(AuthenticationActivity.owner(
            AuthenticationActivity.Surface.DATABASE_MIGRATION)) ||
        !nested.destination.name().equals(destination) ||
      nested.expiresAt < timeSource.now() || !hasCurrentGeneration(nested)) {
      throw new SecurityException("Invalid migration continuation token");
    }
    Intent sanitized = explicitCopy(source, AuthenticationActivity.class);
    sanitized.putExtra(AuthenticationActivity.EXTRA_SURFACE,
                       AuthenticationActivity.Surface.DATABASE_MIGRATION.name());
    sanitized.putExtra(EXTRA_TOKEN, token);
    sanitized.putExtra(EXTRA_DESTINATION, destination);
    return sanitized;
  }

  private static Intent explicitCopy(Intent source, Class<?> target) {
    Intent result = new Intent().setComponent(source.getComponent());
    if (!result.getComponent().getClassName().equals(target.getName())) {
      throw new SecurityException("Unexpected continuation component");
    }
    result.setFlags(source.getFlags());
    return result;
  }

  private static void copyPrimitiveExtras(Intent source, Intent target, Set<String> allowed) {
    Bundle extras = source.getExtras();
    if (extras == null) return;
    Set<String> unexpected = new HashSet<>(extras.keySet());
    unexpected.removeAll(allowed);
    if (!unexpected.isEmpty()) throw new SecurityException("Unexpected continuation fields: " + unexpected);
    for (String key : extras.keySet()) {
      Object value = extras.get(key);
      if (value instanceof String) {
        if (((String) value).isEmpty() || ((String) value).length() > MAX_TOKEN_LENGTH) {
          throw new SecurityException("Invalid continuation string");
        }
        target.putExtra(key, (String) value);
      } else if (value instanceof Long) target.putExtra(key, (Long) value);
      else if (value instanceof Integer) target.putExtra(key, (Integer) value);
      else if (value instanceof Boolean) target.putExtra(key, (Boolean) value);
      else if (value instanceof long[]) {
        long[] values = (long[]) value;
        if (values.length == 0 || values.length > MAX_RECIPIENT_IDS) {
          throw new SecurityException("Invalid continuation ID count");
        }
        target.putExtra(key, values.clone());
      } else throw new SecurityException("Non-primitive continuation field: " + key);
    }
  }

  private static void copyLong(Intent source, Intent target, String key, long minimum,
                               boolean required) {
    if (!source.hasExtra(key)) {
      if (required) throw new SecurityException("Missing continuation field: " + key);
      return;
    }
    long value = source.getLongExtra(key, minimum - 1L);
    if (value < minimum) throw new SecurityException("Invalid continuation field: " + key);
    target.putExtra(key, value);
  }

  private static void copyInt(Intent source, Intent target, String key, int minimum,
                              boolean required) {
    if (!source.hasExtra(key)) {
      if (required) throw new SecurityException("Missing continuation field: " + key);
      return;
    }
    int value = source.getIntExtra(key, minimum - 1);
    if (value < minimum) throw new SecurityException("Invalid continuation field: " + key);
    target.putExtra(key, value);
  }

  private static void copyLongArray(Intent source, Intent target, String key, boolean required) {
    long[] values = source.getLongArrayExtra(key);
    if (values == null) {
      if (required) throw new SecurityException("Missing continuation field: " + key);
      return;
    }
    if (values.length == 0 || values.length > MAX_RECIPIENT_IDS) {
      throw new SecurityException("Invalid continuation ID count");
    }
    for (long value : values) {
      if (value <= 0L) throw new SecurityException("Invalid continuation ID");
    }
    target.putExtra(key, values.clone());
  }

  private static void requireNoExtras(Intent intent) {
    if (intent.getExtras() != null && !intent.getExtras().isEmpty()) {
      throw new SecurityException("Unexpected host continuation fields");
    }
  }

  private boolean hasCurrentGeneration(Entry entry) {
    if (entry.unlockGeneration == null) return true;
    UnlockSession.Snapshot snapshot = snapshotProvider.current();
    return snapshot.getSecret() != null &&
        snapshot.getGeneration() == entry.unlockGeneration.longValue();
  }

  interface TimeSource { long now(); }

  public static final class InvalidContinuationException extends Exception {}

  enum Destination {
    CONVERSATION_LIST(ConversationListActivity.class),
    CONVERSATION(ConversationActivity.class),
    CONVERSATION_POPUP(ConversationPopupActivity.class),
    DATABASE_MIGRATION(AuthenticationActivity.class);

    private final Class<?> activityClass;

    Destination(Class<?> activityClass) {
      this.activityClass = activityClass;
    }

    private static Destination from(Intent intent) {
      if (intent.getComponent() == null) {
        throw new SecurityException("Bootstrap continuations must be explicit");
      }
      String className = intent.getComponent().getClassName();
      if (AuthenticationActivity.class.getName().equals(className)) {
        if (AuthenticationActivity.isAllowedDatabaseMigrationIntent(intent)) {
          return DATABASE_MIGRATION;
        }
        throw new SecurityException("Unsupported authentication continuation surface");
      }
      for (Destination destination : values()) {
        if (destination.activityClass.getName().equals(className)) return destination;
      }
      throw new SecurityException("Unsupported bootstrap continuation: " + className);
    }
  }

  private static final class Entry {
    private final String owner;
    private final Destination destination;
    private final Intent target;
    private Long unlockGeneration;
    private final long expiresAt;

    private Entry(String owner, Destination destination, Intent target,
                  @Nullable Long unlockGeneration, long expiresAt) {
      this.owner = owner;
      this.destination = destination;
      this.target = target;
      this.unlockGeneration = unlockGeneration;
      this.expiresAt = expiresAt;
    }
  }
}