/**
 * Copyright (C) 2016 Open Whisper Systems
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

package org.smssecure.smssecure.notifications;

import android.content.BroadcastReceiver.PendingResult;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.core.app.RemoteInput;

import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.util.concurrent.AsyncBroadcastTask;

/**
 * Get the response text from the Wearable Device and sends an message as a reply
 */
public class RemoteReplyReceiver extends MasterSecretBroadcastReceiver {

  public static final String TAG                 = RemoteReplyReceiver.class.getSimpleName();
  public static final String REPLY_ACTION        = "org.smssecure.smssecure.notifications.WEAR_REPLY";
  public static final String RECIPIENT_IDS_EXTRA = "recipient_ids";

  @Override
  protected void onReceive(final Context context, Intent intent,
                           final @Nullable MasterSecret masterSecret)
  {
    if (!REPLY_ACTION.equals(intent.getAction())) return;

    Bundle remoteInput = RemoteInput.getResultsFromIntent(intent);

    if (remoteInput == null) return;

    final long[]       recipientIds = intent.getLongArrayExtra(RECIPIENT_IDS_EXTRA);
    final CharSequence responseText = remoteInput.getCharSequence(MessageNotifier.EXTRA_REMOTE_REPLY);

    if (masterSecret == null || recipientIds == null || responseText == null) return;

    Context appContext = context.getApplicationContext();
    PendingResult pendingResult = goAsync();
    AsyncBroadcastTask.submit(pendingResult, TAG, () -> {
      NotificationActionOperations.sendReply(appContext, masterSecret, recipientIds,
                                             responseText, -1,
                                             NotificationActionOperations.UNKNOWN_SUBSCRIPTION_ID,
                                             false, true);
      return null;
    });

  }
}
