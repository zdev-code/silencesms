/**
 * Copyright (C) 2014 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.smssecure.smssecure;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import androidx.activity.ComponentActivity;

import org.smssecure.smssecure.domain.conversation.ConversationPayloadStore;

import dagger.hilt.android.AndroidEntryPoint;
import javax.inject.Inject;

/**
 * An activity to quickly share content with contacts
 *
 * @author Jake McGinty
 */
@AndroidEntryPoint
public class ShareActivity extends ComponentActivity {
  public static final String EXTRA_THREAD_ID         = "thread_id";
  public static final String EXTRA_RECIPIENT_IDS     = "recipient_ids";
  public static final String EXTRA_DISTRIBUTION_TYPE = "distribution_type";

  private final ExternalRouterDispatchGuard dispatchGuard = new ExternalRouterDispatchGuard();
  @Inject ConversationPayloadStore conversationPayloadStore;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    routeOnce(getIntent(), savedInstanceState != null);
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    routeOnce(intent, false);
  }

  private void routeOnce(Intent source, boolean restored) {
    if (!dispatchGuard.claim(restored)) {
      clearSourceIntent(source);
      finish();
      return;
    }
    String token = null;
    Uri grantedMedia = null;
    try {
      ExternalConversationIntentParser.ShareInput input =
          ExternalConversationIntentParser.parseShare(source);
      Uri media = input.getPayload().getMedia();
      Runnable cleanup = () -> {};
      if (input.hasExternalMedia()) {
        grantUriPermission(getPackageName(), media, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        grantedMedia = media;
        cleanup = () -> revokeUriPermission(media, Intent.FLAG_GRANT_READ_URI_PERMISSION);
      }
      token = conversationPayloadStore.put(
          NewConversationFragment.PAYLOAD_OWNER, input.getPayload(), cleanup);
      Intent nextIntent = HostNavigationCommand.createNewConversationIntent(this, token);
      clearSourceIntent(source);
      startActivity(nextIntent);
    } catch (ExternalConversationIntentParser.InvalidIntentException | RuntimeException error) {
      if (token != null) conversationPayloadStore.discard(token);
      else if (grantedMedia != null) {
        revokeUriPermission(grantedMedia, Intent.FLAG_GRANT_READ_URI_PERMISSION);
      }
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
