package org.smssecure.smssecure;

import android.os.Bundle;

import androidx.annotation.IdRes;
import androidx.annotation.Nullable;
import androidx.navigation.NavController;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

enum ConversationListDestination {
  INBOX(R.id.conversation_list_inbox, false,
        Set.of(PassphraseRequiredActionBarActivity.LOCALE_EXTRA, ConversationListFragment.ARCHIVE)),
  ARCHIVE(R.id.conversation_list_archive, true,
          Set.of(PassphraseRequiredActionBarActivity.LOCALE_EXTRA, ConversationListFragment.ARCHIVE)),
  GROUP_CREATE(R.id.group_create, false,
               Set.of(PassphraseRequiredActionBarActivity.LOCALE_EXTRA)),
  PUSH_CONTACT_SELECTION(R.id.push_contact_selection, false, Set.of()),
  NEW_CONVERSATION(R.id.new_conversation, false,
           Set.of(PassphraseRequiredActionBarActivity.LOCALE_EXTRA,
             NewConversationFragment.PAYLOAD_TOKEN_ARGUMENT)),
  CONVERSATION(R.id.conversation_screen, false,
               Set.of(PassphraseRequiredActionBarActivity.LOCALE_EXTRA,
                      ConversationScreenFragment.RECIPIENTS_ARGUMENT,
                      ConversationScreenFragment.THREAD_ID_ARGUMENT,
                      ConversationScreenFragment.IS_ARCHIVED_ARGUMENT,
                      ConversationScreenFragment.DISTRIBUTION_TYPE_ARGUMENT,
                      ConversationScreenFragment.TIMING_ARGUMENT,
                      ConversationScreenFragment.LAST_SEEN_ARGUMENT,
                      ConversationScreenFragment.PAYLOAD_TOKEN_ARGUMENT)),
    MESSAGE_DETAILS(R.id.message_details, false,
          Set.of(MessageDetailsFragment.MESSAGE_ID_ARGUMENT,
            MessageDetailsFragment.THREAD_ID_ARGUMENT,
            MessageDetailsFragment.TRANSPORT_ARGUMENT,
            MessageDetailsFragment.RECIPIENT_IDS_ARGUMENT)),
              MEDIA_OVERVIEW(R.id.media_overview, false,
                   Set.of(MediaOverviewFragment.THREAD_ID_ARGUMENT,
                     MediaOverviewFragment.RECIPIENT_ID_ARGUMENT)),
              MEDIA_PREVIEW(R.id.media_preview, false,
                  Set.of(MediaPreviewFragment.MODE_ARGUMENT,
                    MediaPreviewFragment.PART_ROW_ID_ARGUMENT,
                    MediaPreviewFragment.PART_UNIQUE_ID_ARGUMENT,
                    MediaPreviewFragment.MESSAGE_ID_ARGUMENT,
                    MediaPreviewFragment.THREAD_ID_ARGUMENT,
                    MediaPreviewFragment.RECIPIENT_ID_ARGUMENT,
                    MediaPreviewFragment.DATE_ARGUMENT,
                    MediaPreviewFragment.SIZE_ARGUMENT)),
    VIEW_IDENTITY(R.id.view_identity, false,
                  Set.of(ViewIdentityFragment.SUBSCRIPTION_ID_ARGUMENT)),
    VERIFY_IDENTITY(R.id.verify_identity, false,
                    Set.of(VerifyIdentityFragment.RECIPIENT_ID_ARGUMENT,
                VerifyIdentityFragment.SUBSCRIPTION_ID_ARGUMENT,
                VerifyIdentityFragment.CONFLICT_TOKEN_ARGUMENT)),
    RECIPIENT_PREFERENCES(R.id.recipient_preferences, false,
                          Set.of(RecipientPreferenceFragment.RECIPIENT_IDS_ARGUMENT)),
  BLOCKED_CONTACTS(R.id.blocked_contacts, false,
                   Set.of(PassphraseRequiredActionBarActivity.LOCALE_EXTRA)),
  MMS_PREFERENCES(R.id.mms_preferences, false,
                  Set.of(PassphraseRequiredActionBarActivity.LOCALE_EXTRA)),
  IMPORT_EXPORT(R.id.import_export, false,
                Set.of(PassphraseRequiredActionBarActivity.LOCALE_EXTRA)),
  APPLICATION_PREFERENCES(R.id.application_preferences, false,
                  Set.of(PassphraseRequiredActionBarActivity.LOCALE_EXTRA)),
  SMS_MMS_PREFERENCES(R.id.sms_mms_preferences, false,
                  Set.of(PassphraseRequiredActionBarActivity.LOCALE_EXTRA)),
  NOTIFICATION_PREFERENCES(R.id.notification_preferences, false,
                  Set.of(PassphraseRequiredActionBarActivity.LOCALE_EXTRA)),
  APP_PROTECTION_PREFERENCES(R.id.app_protection_preferences, false,
                  Set.of(PassphraseRequiredActionBarActivity.LOCALE_EXTRA)),
  APPEARANCE_PREFERENCES(R.id.appearance_preferences, false,
                  Set.of(PassphraseRequiredActionBarActivity.LOCALE_EXTRA)),
  CHAT_PREFERENCES(R.id.chat_preferences, false,
                  Set.of(PassphraseRequiredActionBarActivity.LOCALE_EXTRA)),
  ADVANCED_PREFERENCES(R.id.advanced_preferences, false,
                  Set.of(PassphraseRequiredActionBarActivity.LOCALE_EXTRA));

  static final String HOST_TAG = "conversation-list-nav-host";
  static final String EXTRA_ARCHIVE = "org.smssecure.smssecure.navigation.ARCHIVE";

  private final int id;
  private final boolean archived;
  private final Set<String> allowedArgumentKeys;

  ConversationListDestination(@IdRes int id, boolean archived, Set<String> allowedArgumentKeys) {
    this.id = id;
    this.archived = archived;
    this.allowedArgumentKeys = Set.copyOf(allowedArgumentKeys);
  }

  @IdRes int getId() {
    return id;
  }

  boolean isAlwaysSecure() {
    return this == APP_PROTECTION_PREFERENCES;
  }

  Bundle arguments(@Nullable Locale locale) {
    if (this == CONVERSATION || this == MESSAGE_DETAILS || this == MEDIA_OVERVIEW ||
      this == MEDIA_PREVIEW || this == VIEW_IDENTITY ||
      this == VERIFY_IDENTITY || this == RECIPIENT_PREFERENCES) {
      throw new IllegalStateException(name() + " arguments require opaque IDs");
    }
    Bundle arguments = new Bundle();
    if (locale != null) {
      arguments.putSerializable(PassphraseRequiredActionBarActivity.LOCALE_EXTRA, locale);
    }
    if (this == INBOX || this == ARCHIVE) {
      arguments.putBoolean(ConversationListFragment.ARCHIVE, archived);
    }
    requireAllowedArguments(arguments);
    return arguments;
  }

  void requireAllowedArguments(Bundle arguments) {
    Set<String> unexpected = new HashSet<>(arguments.keySet());
    unexpected.removeAll(allowedArgumentKeys);
    if (!unexpected.isEmpty()) {
      throw new SecurityException("Unexpected navigation arguments for " + name() + ": " + unexpected);
    }
    if (arguments.containsKey(PassphraseRequiredActionBarActivity.LOCALE_EXTRA) &&
        androidx.core.os.BundleCompat.getSerializable(
            arguments, PassphraseRequiredActionBarActivity.LOCALE_EXTRA, Locale.class) == null) {
      throw new SecurityException("Invalid locale navigation argument");
    }
    if (arguments.containsKey(ConversationListFragment.ARCHIVE) &&
        androidx.core.os.BundleCompat.getSerializable(
            arguments, ConversationListFragment.ARCHIVE, Boolean.class) == null) {
      throw new SecurityException("Invalid archive navigation argument");
    }
    if (this == CONVERSATION) {
      ConversationScreenFragment.requireValidArguments(arguments);
    } else if (this == NEW_CONVERSATION &&
               arguments.containsKey(NewConversationFragment.PAYLOAD_TOKEN_ARGUMENT)) {
      NewConversationFragment.requireValidArguments(arguments);
    } else if (this == MESSAGE_DETAILS) {
      MessageDetailsFragment.requireValidArguments(arguments);
    } else if (this == MEDIA_OVERVIEW) {
      MediaOverviewFragment.requireValidArguments(arguments);
    } else if (this == MEDIA_PREVIEW) {
      MediaPreviewFragment.requireValidArguments(arguments);
    } else if (this == VIEW_IDENTITY) {
      ViewIdentityFragment.requireValidArguments(arguments);
    } else if (this == VERIFY_IDENTITY) {
      VerifyIdentityFragment.requireValidArguments(arguments);
    } else if (this == RECIPIENT_PREFERENCES) {
      RecipientPreferenceFragment.requireValidArguments(arguments);
    }
  }

  static ConversationListDestination from(@Nullable NavController controller) {
    if (controller != null && controller.getCurrentDestination() != null) {
      int destinationId = controller.getCurrentDestination().getId();
      for (ConversationListDestination destination : values()) {
        if (destination.id == destinationId) return destination;
      }
    }
    return INBOX;
  }
}