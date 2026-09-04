package org.smssecure.smssecure;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;

public final class HostNavigationCommand {
  private static final String EXTRA_DESTINATION =
      "org.smssecure.smssecure.navigation.HOST_DESTINATION";

  private HostNavigationCommand() {}

  public static Intent createIntent(@NonNull Context context, @NonNull Destination destination) {
    return new Intent(context, ConversationListActivity.class)
        .putExtra(EXTRA_DESTINATION, Objects.requireNonNull(destination).name());
  }

  public static Intent createConversationIntent(@NonNull Context context, @NonNull Bundle arguments) {
    ConversationListDestination.CONVERSATION.requireAllowedArguments(arguments);
    return createIntent(context, Destination.CONVERSATION).putExtras(new Bundle(arguments));
  }

  public static Intent createNewConversationIntent(@NonNull Context context,
                                                   @NonNull String payloadToken) {
    Bundle arguments = NewConversationFragment.arguments(payloadToken);
    ConversationListDestination.NEW_CONVERSATION.requireAllowedArguments(arguments);
    return createIntent(context, Destination.NEW_CONVERSATION).putExtras(arguments);
  }

  public static Intent createRecipientPreferencesIntent(@NonNull Context context,
                                                         @NonNull long[] recipientIds) {
    return createIntent(context, Destination.RECIPIENT_PREFERENCES)
        .putExtras(RecipientPreferenceFragment.arguments(recipientIds));
  }

  public static Intent createVerifyIdentityIntent(@NonNull Context context,
                                                   long recipientId, int subscriptionId) {
    Bundle arguments = VerifyIdentityFragment.arguments(recipientId, subscriptionId);
    ConversationListDestination.VERIFY_IDENTITY.requireAllowedArguments(arguments);
    return createIntent(context, Destination.VERIFY_IDENTITY).putExtras(arguments);
  }

  public static Intent createConflictVerifyIdentityIntent(@NonNull Context context,
                                                           @NonNull String token) {
    Bundle arguments = VerifyIdentityFragment.conflictArguments(token);
    ConversationListDestination.VERIFY_IDENTITY.requireAllowedArguments(arguments);
    return createIntent(context, Destination.VERIFY_IDENTITY).putExtras(arguments);
  }

  public static Intent createMediaOverviewIntent(@NonNull Context context,
                                                 long threadId, long recipientId) {
    Bundle arguments = MediaOverviewFragment.arguments(threadId, recipientId);
    ConversationListDestination.MEDIA_OVERVIEW.requireAllowedArguments(arguments);
    return createIntent(context, Destination.MEDIA_OVERVIEW).putExtras(arguments);
  }

  public static Intent createConversationIntent(@NonNull Context context,
                                                @NonNull long[] recipientIds,
                                                long threadId,
                                                int distributionType,
                                                boolean archived,
                                                long timing,
                                                long lastSeen,
                                                @Nullable String payloadToken) {
    return createConversationIntent(context, ConversationScreenFragment.arguments(
        recipientIds, threadId, distributionType, archived, timing, lastSeen, payloadToken));
  }

  static Bundle consumeConversationArguments(@Nullable Intent intent) {
    if (intent == null) throw new SecurityException("Missing conversation command");
    Bundle arguments = new Bundle();
    copyAndRemove(intent, arguments, ConversationScreenFragment.RECIPIENTS_ARGUMENT);
    copyAndRemove(intent, arguments, ConversationScreenFragment.THREAD_ID_ARGUMENT);
    copyAndRemove(intent, arguments, ConversationScreenFragment.IS_ARCHIVED_ARGUMENT);
    copyAndRemove(intent, arguments, ConversationScreenFragment.DISTRIBUTION_TYPE_ARGUMENT);
    copyAndRemove(intent, arguments, ConversationScreenFragment.TIMING_ARGUMENT);
    copyAndRemove(intent, arguments, ConversationScreenFragment.LAST_SEEN_ARGUMENT);
    copyAndRemove(intent, arguments, ConversationScreenFragment.PAYLOAD_TOKEN_ARGUMENT);
    ConversationListDestination.CONVERSATION.requireAllowedArguments(arguments);
    return arguments;
  }

  static Bundle consumeNewConversationArguments(@Nullable Intent intent) {
    if (intent == null) throw new SecurityException("Missing new conversation command");
    Bundle arguments = new Bundle();
    copyAndRemove(intent, arguments, NewConversationFragment.PAYLOAD_TOKEN_ARGUMENT);
    ConversationListDestination.NEW_CONVERSATION.requireAllowedArguments(arguments);
    return arguments;
  }

  static Bundle consumeRecipientPreferencesArguments(@Nullable Intent intent) {
    if (intent == null) throw new SecurityException("Missing recipient preferences command");
    Bundle arguments = new Bundle();
    copyAndRemove(intent, arguments, RecipientPreferenceFragment.RECIPIENT_IDS_ARGUMENT);
    ConversationListDestination.RECIPIENT_PREFERENCES.requireAllowedArguments(arguments);
    return arguments;
  }

  static Bundle consumeMediaOverviewArguments(@Nullable Intent intent) {
    if (intent == null) throw new SecurityException("Missing media overview command");
    Bundle arguments = new Bundle();
    copyAndRemove(intent, arguments, MediaOverviewFragment.THREAD_ID_ARGUMENT);
    copyAndRemove(intent, arguments, MediaOverviewFragment.RECIPIENT_ID_ARGUMENT);
    ConversationListDestination.MEDIA_OVERVIEW.requireAllowedArguments(arguments);
    return arguments;
  }

  static Bundle consumeVerifyIdentityArguments(@Nullable Intent intent) {
    if (intent == null) throw new SecurityException("Missing identity verification command");
    Bundle arguments = new Bundle();
    copyAndRemove(intent, arguments, VerifyIdentityFragment.RECIPIENT_ID_ARGUMENT);
    copyAndRemove(intent, arguments, VerifyIdentityFragment.SUBSCRIPTION_ID_ARGUMENT);
    copyAndRemove(intent, arguments, VerifyIdentityFragment.CONFLICT_TOKEN_ARGUMENT);
    ConversationListDestination.VERIFY_IDENTITY.requireAllowedArguments(arguments);
    if (intent.getExtras() != null && !intent.getExtras().isEmpty()) {
      throw new SecurityException("Unexpected identity verification command fields");
    }
    return arguments;
  }

  private static void copyAndRemove(Intent intent, Bundle arguments, String key) {
    if (!intent.hasExtra(key)) return;
    Object value = intent.getExtras().get(key);
    intent.removeExtra(key);
    if (value instanceof long[]) arguments.putLongArray(key, ((long[]) value).clone());
    else if (value instanceof Long) arguments.putLong(key, (Long) value);
    else if (value instanceof Integer) arguments.putInt(key, (Integer) value);
    else if (value instanceof Boolean) arguments.putBoolean(key, (Boolean) value);
    else if (value instanceof String) arguments.putString(key, (String) value);
    else throw new SecurityException("Invalid host command field: " + key);
  }

  static Destination consume(@Nullable Intent intent) {
    if (intent == null) return Destination.INBOX;
    String value = intent.getStringExtra(EXTRA_DESTINATION);
    intent.removeExtra(EXTRA_DESTINATION);
    if (value == null) return Destination.INBOX;
    try {
      return Destination.valueOf(value);
    } catch (IllegalArgumentException exception) {
      return Destination.INBOX;
    }
  }

  public enum Destination {
    INBOX,
    ARCHIVE,
    NEW_CONVERSATION,
    BLOCKED_CONTACTS,
    MMS_PREFERENCES,
    APP_PROTECTION_PREFERENCES,
    RECIPIENT_PREFERENCES,
    MEDIA_OVERVIEW,
    VERIFY_IDENTITY,
    CONVERSATION
  }
}