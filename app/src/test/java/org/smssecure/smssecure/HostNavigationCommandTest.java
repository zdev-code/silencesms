package org.smssecure.smssecure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class HostNavigationCommandTest {
  private final Context context = ApplicationProvider.getApplicationContext();

  @Test
  public void createsExplicitCommandForPrivateHost() {
    Intent intent = HostNavigationCommand.createIntent(
        context, HostNavigationCommand.Destination.BLOCKED_CONTACTS);

    assertThat(intent.getComponent().getClassName()).isEqualTo(ConversationListActivity.class.getName());
    assertThat(HostNavigationCommand.consume(intent))
        .isEqualTo(HostNavigationCommand.Destination.BLOCKED_CONTACTS);
    assertThat(HostNavigationCommand.consume(intent))
        .isEqualTo(HostNavigationCommand.Destination.INBOX);
  }

  @Test
  public void unknownOrMissingCommandsFailClosedToInbox() {
    Intent unknown = HostNavigationCommand.createIntent(
        context, HostNavigationCommand.Destination.ARCHIVE);
    unknown.putExtra("org.smssecure.smssecure.navigation.HOST_DESTINATION", "FORGED");

    assertThat(HostNavigationCommand.consume(unknown))
        .isEqualTo(HostNavigationCommand.Destination.INBOX);
    assertThat(HostNavigationCommand.consume(new Intent()))
        .isEqualTo(HostNavigationCommand.Destination.INBOX);
  }

    @Test
    public void createsMmsPreferencesCommandWithoutForwardedPayload() {
        Intent intent = HostNavigationCommand.createIntent(
                context, HostNavigationCommand.Destination.MMS_PREFERENCES);

        assertThat(HostNavigationCommand.consume(intent))
                .isEqualTo(HostNavigationCommand.Destination.MMS_PREFERENCES);
        assertThat(intent.getExtras()).isNull();
    }

  @Test
  public void conversationCommandConsumesAndRemovesOpaqueFields() {
    Intent intent = HostNavigationCommand.createConversationIntent(
        context, new long[] {3L}, 7L, 1, false, 11L, 12L, "opaque-token");

    assertThat(HostNavigationCommand.consume(intent))
        .isEqualTo(HostNavigationCommand.Destination.CONVERSATION);
    Bundle arguments = HostNavigationCommand.consumeConversationArguments(intent);

    assertThat(arguments.getLongArray(ConversationScreenFragment.RECIPIENTS_ARGUMENT))
        .containsExactly(3L);
    assertThat(arguments.getString(ConversationScreenFragment.PAYLOAD_TOKEN_ARGUMENT))
        .isEqualTo("opaque-token");
    assertThat(intent.getExtras()).isNull();
  }

    @Test
    public void newConversationCommandConsumesAndRemovesOnlyOpaqueToken() {
        Intent intent = HostNavigationCommand.createNewConversationIntent(context, "opaque-token");

        assertThat(HostNavigationCommand.consume(intent))
                .isEqualTo(HostNavigationCommand.Destination.NEW_CONVERSATION);
        Bundle arguments = HostNavigationCommand.consumeNewConversationArguments(intent);
        assertThat(arguments.getString(NewConversationFragment.PAYLOAD_TOKEN_ARGUMENT))
                .isEqualTo("opaque-token");
        assertThat(intent.getExtras()).isNull();
    }

    @Test
    public void recipientPreferencesCommandConsumesAndRemovesOnlyIds() {
        Intent intent = HostNavigationCommand.createRecipientPreferencesIntent(context, new long[] {3L, 4L});

        assertThat(HostNavigationCommand.consume(intent))
                .isEqualTo(HostNavigationCommand.Destination.RECIPIENT_PREFERENCES);
        Bundle arguments = HostNavigationCommand.consumeRecipientPreferencesArguments(intent);
        assertThat(arguments.getLongArray(RecipientPreferenceFragment.RECIPIENT_IDS_ARGUMENT))
                .containsExactly(3L, 4L);
        assertThat(intent.getExtras()).isNull();
    }

    @Test
    public void mediaOverviewCommandConsumesAndRemovesOnlyPrimitiveIds() {
        Intent intent = HostNavigationCommand.createMediaOverviewIntent(context, 7L, 9L);

        assertThat(HostNavigationCommand.consume(intent))
                .isEqualTo(HostNavigationCommand.Destination.MEDIA_OVERVIEW);
        Bundle arguments = HostNavigationCommand.consumeMediaOverviewArguments(intent);
        assertThat(arguments.getLong(MediaOverviewFragment.THREAD_ID_ARGUMENT)).isEqualTo(7L);
        assertThat(arguments.getLong(MediaOverviewFragment.RECIPIENT_ID_ARGUMENT)).isEqualTo(9L);
        assertThat(intent.getExtras()).isNull();
    }

        @Test
        public void normalVerificationCommandCarriesOnlyPrimitiveIds() {
        Intent intent = HostNavigationCommand.createVerifyIdentityIntent(context, 7L, 3);

        assertThat(intent.getComponent().getClassName())
            .isEqualTo(ConversationListActivity.class.getName());
        assertThat(HostNavigationCommand.consume(intent))
            .isEqualTo(HostNavigationCommand.Destination.VERIFY_IDENTITY);
        Bundle arguments = HostNavigationCommand.consumeVerifyIdentityArguments(intent);
        assertThat(arguments.getLong(VerifyIdentityFragment.RECIPIENT_ID_ARGUMENT)).isEqualTo(7L);
        assertThat(arguments.getInt(VerifyIdentityFragment.SUBSCRIPTION_ID_ARGUMENT)).isEqualTo(3);
        assertThat(intent.getExtras()).isNull();
        }

        @Test
        public void conflictVerificationCommandCarriesOnlyOpaqueToken() {
        Intent intent = HostNavigationCommand.createConflictVerifyIdentityIntent(
            context, "opaque-token");

        assertThat(HostNavigationCommand.consume(intent))
            .isEqualTo(HostNavigationCommand.Destination.VERIFY_IDENTITY);
        Bundle arguments = HostNavigationCommand.consumeVerifyIdentityArguments(intent);
        assertThat(arguments.keySet()).containsExactly(VerifyIdentityFragment.CONFLICT_TOKEN_ARGUMENT);
        assertThat(arguments.getString(VerifyIdentityFragment.CONFLICT_TOKEN_ARGUMENT))
            .isEqualTo("opaque-token");
        assertThat(intent.getExtras()).isNull();
        }

        @Test
        public void verificationCommandRejectsUnexpectedFieldsAndTypeConfusion() {
        Intent unexpected = HostNavigationCommand.createConflictVerifyIdentityIntent(
            context, "opaque-token");
        unexpected.putExtra("remote_identity", new Bundle());
        HostNavigationCommand.consume(unexpected);
        assertThatThrownBy(() -> HostNavigationCommand.consumeVerifyIdentityArguments(unexpected))
            .isInstanceOf(SecurityException.class);

        Intent wrongType = HostNavigationCommand.createIntent(
            context, HostNavigationCommand.Destination.VERIFY_IDENTITY);
        wrongType.putExtra(VerifyIdentityFragment.CONFLICT_TOKEN_ARGUMENT, 7);
        HostNavigationCommand.consume(wrongType);
        assertThatThrownBy(() -> HostNavigationCommand.consumeVerifyIdentityArguments(wrongType))
            .isInstanceOf(SecurityException.class);
        }
}