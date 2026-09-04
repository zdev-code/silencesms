package org.smssecure.smssecure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import android.os.Bundle;
import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class ConversationListDestinationTest {
  @Test
  public void generatedArgumentsMatchDestinationPolicy() {
    for (ConversationListDestination destination : ConversationListDestination.values()) {
      if (destination == ConversationListDestination.CONVERSATION ||
          destination == ConversationListDestination.MESSAGE_DETAILS ||
          destination == ConversationListDestination.MEDIA_OVERVIEW ||
          destination == ConversationListDestination.MEDIA_PREVIEW ||
          destination == ConversationListDestination.VIEW_IDENTITY ||
          destination == ConversationListDestination.VERIFY_IDENTITY ||
          destination == ConversationListDestination.RECIPIENT_PREFERENCES) continue;
      assertThatCode(() -> destination.requireAllowedArguments(destination.arguments(null)))
          .doesNotThrowAnyException();
    }

    assertThatThrownBy(() -> ConversationListDestination.CONVERSATION.arguments(null))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> ConversationListDestination.MEDIA_OVERVIEW.arguments(null))
      .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> ConversationListDestination.MEDIA_PREVIEW.arguments(null))
      .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> ConversationListDestination.VIEW_IDENTITY.arguments(null))
      .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> ConversationListDestination.VERIFY_IDENTITY.arguments(null))
      .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> ConversationListDestination.RECIPIENT_PREFERENCES.arguments(null))
      .isInstanceOf(IllegalStateException.class);
  }

  @Test
  public void rejectsUnexpectedAndSensitiveArguments() {
    Bundle arguments = ConversationListDestination.INBOX.arguments(null);
    arguments.putString("message_body", "plaintext");

    assertThatThrownBy(() -> ConversationListDestination.INBOX.requireAllowedArguments(arguments))
        .isInstanceOf(SecurityException.class);
  }

  @Test
  public void rejectsPayloadSmuggledUnderAllowedKey() {
    Bundle arguments = ConversationListDestination.INBOX.arguments(null);
    arguments.putBundle(PassphraseRequiredActionBarActivity.LOCALE_EXTRA, new Bundle());

    assertThatThrownBy(() -> ConversationListDestination.INBOX.requireAllowedArguments(arguments))
        .isInstanceOf(SecurityException.class);
  }

  @Test
  public void appProtectionAlwaysStrengthensScreenshotSecurity() {
    assertThat(ConversationListDestination.APP_PROTECTION_PREFERENCES.isAlwaysSecure()).isTrue();
    assertThat(ConversationListDestination.APPLICATION_PREFERENCES.isAlwaysSecure()).isFalse();
  }

  @Test
  public void importExportAllowsOnlyLocaleNavigationState() {
    Bundle arguments = ConversationListDestination.IMPORT_EXPORT.arguments(null);

    assertThat(arguments.keySet()).isEmpty();
    arguments.putParcelable("backup_uri", Uri.parse("content://secret"));
    assertThatThrownBy(() -> ConversationListDestination.IMPORT_EXPORT.requireAllowedArguments(arguments))
        .isInstanceOf(SecurityException.class);
  }

  @Test
  public void conversationAllowsOnlyOpaqueIdsPrimitivesAndPayloadToken() {
    Bundle arguments = ConversationScreenFragment.arguments(
        new long[] {3L, 4L}, 7L, 1, true, 11L, 12L, "opaque-token");

    ConversationListDestination.CONVERSATION.requireAllowedArguments(arguments);

    arguments.putString(ConversationActivity.TEXT_EXTRA, "secret");
    assertThatThrownBy(() -> ConversationListDestination.CONVERSATION.requireAllowedArguments(arguments))
        .isInstanceOf(SecurityException.class);
  }

  @Test
  public void conversationRejectsUriPayloadArguments() {
    Bundle arguments = ConversationScreenFragment.arguments(
        new long[] {3L}, 7L, 1, false, 11L, 12L, null);
    arguments.putParcelable("uri", Uri.parse("content://secret"));

    assertThatThrownBy(() -> ConversationListDestination.CONVERSATION.requireAllowedArguments(arguments))
        .isInstanceOf(SecurityException.class);
  }

  @Test
  public void conversationRejectsWrongPrimitiveTypes() {
    Bundle arguments = ConversationScreenFragment.arguments(
        new long[] {3L}, 7L, 1, false, 11L, 12L, null);
    arguments.putString(ConversationScreenFragment.THREAD_ID_ARGUMENT, "7");

    assertThatThrownBy(() -> ConversationListDestination.CONVERSATION.requireAllowedArguments(arguments))
        .isInstanceOf(SecurityException.class);
  }

  @Test
  public void newConversationAllowsOnlyOpaquePayloadToken() {
    Bundle arguments = NewConversationFragment.arguments("opaque-token");
    ConversationListDestination.NEW_CONVERSATION.requireAllowedArguments(arguments);

    arguments.putParcelable("uri", Uri.parse("content://secret"));
    assertThatThrownBy(() ->
        ConversationListDestination.NEW_CONVERSATION.requireAllowedArguments(arguments))
        .isInstanceOf(SecurityException.class);
  }

  @Test
  public void pushContactSelectionAllowsNoRouteArguments() {
    Bundle arguments = ConversationListDestination.PUSH_CONTACT_SELECTION.arguments(null);
    assertThat(arguments.keySet()).isEmpty();

    arguments.putLongArray(PushContactSelectionFragment.RECIPIENT_IDS_KEY, new long[] {3L});
    assertThatThrownBy(() ->
        ConversationListDestination.PUSH_CONTACT_SELECTION.requireAllowedArguments(arguments))
        .isInstanceOf(SecurityException.class);
  }

  @Test
  public void identityAllowsOnlyPrimitiveSubscriptionId() {
    Bundle arguments = ViewIdentityFragment.arguments(3);
    ConversationListDestination.VIEW_IDENTITY.requireAllowedArguments(arguments);

    arguments.putString(ViewIdentityFragment.SUBSCRIPTION_ID_ARGUMENT, "3");
    assertThatThrownBy(() -> ConversationListDestination.VIEW_IDENTITY.requireAllowedArguments(arguments))
        .isInstanceOf(SecurityException.class);
  }

  @Test
  public void verificationRequiresExactlyIdsOrConflictToken() {
    Bundle normal = VerifyIdentityFragment.arguments(7L, 3);
    Bundle conflict = VerifyIdentityFragment.conflictArguments("opaque-token");

    ConversationListDestination.VERIFY_IDENTITY.requireAllowedArguments(normal);
    ConversationListDestination.VERIFY_IDENTITY.requireAllowedArguments(conflict);

    conflict.putLong(VerifyIdentityFragment.RECIPIENT_ID_ARGUMENT, 7L);
    assertThatThrownBy(() ->
        ConversationListDestination.VERIFY_IDENTITY.requireAllowedArguments(conflict))
        .isInstanceOf(SecurityException.class);

    normal.putString(VerifyIdentityFragment.SUBSCRIPTION_ID_ARGUMENT, "3");
    assertThatThrownBy(() ->
        ConversationListDestination.VERIFY_IDENTITY.requireAllowedArguments(normal))
        .isInstanceOf(SecurityException.class);

    Bundle unexpected = VerifyIdentityFragment.conflictArguments("opaque-token");
    unexpected.putParcelable("remote_identity", new Bundle());
    assertThatThrownBy(() ->
        ConversationListDestination.VERIFY_IDENTITY.requireAllowedArguments(unexpected))
        .isInstanceOf(SecurityException.class);
  }

  @Test
  public void recipientPreferencesAllowOnlyOpaqueRecipientIds() {
    long[] recipientIds = new long[] {7L, 8L};
    Bundle arguments = RecipientPreferenceFragment.arguments(recipientIds);
    recipientIds[0] = 99L;
    ConversationListDestination.RECIPIENT_PREFERENCES.requireAllowedArguments(arguments);
    assertThat(arguments.getLongArray(RecipientPreferenceFragment.RECIPIENT_IDS_ARGUMENT))
        .containsExactly(7L, 8L);

    arguments.putString(RecipientPreferenceFragment.RECIPIENT_IDS_ARGUMENT, "7");
    assertThatThrownBy(() ->
        ConversationListDestination.RECIPIENT_PREFERENCES.requireAllowedArguments(arguments))
        .isInstanceOf(SecurityException.class);
  }

    @Test
    public void mediaOverviewRequiresPositivePrimitiveIdsOnly() {
    Bundle arguments = MediaOverviewFragment.arguments(7L, 9L);
    ConversationListDestination.MEDIA_OVERVIEW.requireAllowedArguments(arguments);

    assertThatThrownBy(() -> MediaOverviewFragment.arguments(0L, 9L))
      .isInstanceOf(SecurityException.class);
    arguments.putString(MediaOverviewFragment.THREAD_ID_ARGUMENT, "7");
    assertThatThrownBy(() ->
      ConversationListDestination.MEDIA_OVERVIEW.requireAllowedArguments(arguments))
      .isInstanceOf(SecurityException.class);
    }

    @Test
    public void persistedMediaPreviewRequiresPositivePrimitiveIds() {
    Bundle arguments = MediaPreviewFragment.persistedArguments(3L, 4L, 5L, 6L, 7L, 8L, 9L);
    ConversationListDestination.MEDIA_PREVIEW.requireAllowedArguments(arguments);

    assertThatThrownBy(() ->
      MediaPreviewFragment.persistedArguments(0L, 4L, 5L, 6L, 7L, 8L, 9L))
      .isInstanceOf(SecurityException.class);
    arguments.putBundle(MediaPreviewFragment.MESSAGE_ID_ARGUMENT, new Bundle());
    assertThatThrownBy(() ->
      ConversationListDestination.MEDIA_PREVIEW.requireAllowedArguments(arguments))
      .isInstanceOf(SecurityException.class);
    }

    @Test
    public void draftMediaPreviewCarriesNoDatabaseIdsOrUriPayload() {
    Bundle databaseIdArguments = MediaPreviewFragment.draftArguments(9L);
    ConversationListDestination.MEDIA_PREVIEW.requireAllowedArguments(databaseIdArguments);

    databaseIdArguments.putLong(MediaPreviewFragment.PART_UNIQUE_ID_ARGUMENT, 4L);
    assertThatThrownBy(() ->
      ConversationListDestination.MEDIA_PREVIEW.requireAllowedArguments(databaseIdArguments))
      .isInstanceOf(SecurityException.class);

    Bundle uriArguments = MediaPreviewFragment.draftArguments(9L);
    uriArguments.putParcelable("media_uri", Uri.parse("content://secret"));
    assertThatThrownBy(() ->
      ConversationListDestination.MEDIA_PREVIEW.requireAllowedArguments(uriArguments))
      .isInstanceOf(SecurityException.class);
    }

      @Test
      public void messageDetailsAllowsOnlyOpaqueIdsAndPrimitiveTransport() {
        long[] recipientIds = new long[] {3L, 4L};
        Bundle arguments = MessageDetailsFragment.arguments(
          9L, 7L, MessageDetailsFragment.TRANSPORT_SMS, recipientIds);
        recipientIds[0] = 99L;

      ConversationListDestination.MESSAGE_DETAILS.requireAllowedArguments(arguments);
        assertThat(arguments.getLongArray(MessageDetailsFragment.RECIPIENT_IDS_ARGUMENT))
          .containsExactly(3L, 4L);

      arguments.putParcelable("message", new Bundle());
      assertThatThrownBy(() ->
        ConversationListDestination.MESSAGE_DETAILS.requireAllowedArguments(arguments))
        .isInstanceOf(SecurityException.class);
      }

      @Test
      public void messageDetailsRejectsInvalidIdsTransportAndTypes() {
      assertThatThrownBy(() -> MessageDetailsFragment.arguments(
        -1L, 7L, MessageDetailsFragment.TRANSPORT_SMS, new long[] {3L}))
        .isInstanceOf(SecurityException.class);
      assertThatThrownBy(() -> MessageDetailsFragment.arguments(
        9L, 7L, 99, new long[] {3L}))
        .isInstanceOf(SecurityException.class);

      Bundle arguments = MessageDetailsFragment.arguments(
        9L, 7L, MessageDetailsFragment.TRANSPORT_MMS, new long[] {3L});
      arguments.putString(MessageDetailsFragment.THREAD_ID_ARGUMENT, "7");
      assertThatThrownBy(() ->
        ConversationListDestination.MESSAGE_DETAILS.requireAllowedArguments(arguments))
        .isInstanceOf(SecurityException.class);
      }
}