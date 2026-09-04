package org.smssecure.smssecure;

import android.content.Intent;
import android.os.Bundle;
import androidx.activity.ComponentActivity;

import org.smssecure.smssecure.recipients.RecipientFactory;
import org.smssecure.smssecure.recipients.Recipients;
import org.smssecure.smssecure.database.ThreadDatabase;
import org.smssecure.smssecure.domain.conversation.ConversationPayload;
import org.smssecure.smssecure.domain.conversation.ConversationPayloadStore;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class SmsSendtoActivity extends ComponentActivity {
  private final ExternalRouterDispatchGuard dispatchGuard = new ExternalRouterDispatchGuard();
  @Inject ConversationPayloadStore payloadStore;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    routeOnce(getIntent(), savedInstanceState != null);
  }

  private void routeOnce(Intent source, boolean restored) {
    if (!dispatchGuard.claim(restored)) {
      clearSourceIntent(source);
      finish();
      return;
    }
    try {
      ExternalConversationIntentParser.SendToInput input =
          ExternalConversationIntentParser.parseSendTo(source);
      Recipients recipients = RecipientFactory.getRecipientsFromString(
          this, input.getDestination(), true);
      ConversationPayload payload = new ConversationPayload(
          -1L, recipients.getIds(), ThreadDatabase.DistributionTypes.DEFAULT,
          input.getBody(), null, null);
      String token = payloadStore.put(NewConversationFragment.PAYLOAD_OWNER, payload, () -> {});
      Intent nextIntent = HostNavigationCommand.createNewConversationIntent(this, token);
      clearSourceIntent(source);
      startActivity(nextIntent);
    } catch (ExternalConversationIntentParser.InvalidIntentException | RuntimeException error) {
      clearSourceIntent(source);
    }
    finish();
  }

  private void clearSourceIntent(Intent source) {
    if (source != null) source.replaceExtras((Bundle) null);
    if (source != null) {
      source.setData(null);
      source.setClipData(null);
      source.setAction(null);
      source.setType(null);
    }
    setIntent(new Intent());
  }
}
