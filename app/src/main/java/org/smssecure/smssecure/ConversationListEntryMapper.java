package org.smssecure.smssecure;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import org.smssecure.smssecure.crypto.MasterCipher;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.data.conversation.ConversationListEntry;
import org.smssecure.smssecure.database.MmsSmsColumns;
import org.smssecure.smssecure.database.model.DisplayRecord;
import org.smssecure.smssecure.database.model.ThreadRecord;
import org.smssecure.smssecure.recipients.RecipientFactory;
import org.smssecure.smssecure.recipients.Recipients;
import org.signal.libsignal.protocol.InvalidMessageException;

final class ConversationListEntryMapper {
  private final Context      context;
  private final MasterCipher masterCipher;

  ConversationListEntryMapper(Context context, MasterSecret masterSecret) {
    this.context      = context.getApplicationContext();
    this.masterCipher = new MasterCipher(masterSecret);
  }

  ThreadRecord map(ConversationListEntry entry) {
    Recipients recipients = RecipientFactory.getRecipientsForIds(context, entry.getRecipientIds(), true);
    Uri snippetUri = null;
    if (!TextUtils.isEmpty(entry.getSnippetUri())) {
      try {
        snippetUri = Uri.parse(entry.getSnippetUri());
      } catch (IllegalArgumentException error) {
        Log.w("ConversationListEntryMapper", error);
      }
    }
    return new ThreadRecord(context, plaintextBody(entry), snippetUri, recipients, entry.getDate(),
        entry.getMessageCount(), entry.isRead(), entry.getThreadId(), entry.getStatus(),
        entry.getSnippetType(), entry.getDistributionType(), entry.isArchived(), entry.getLastSeen());
  }

  private DisplayRecord.Body plaintextBody(ConversationListEntry entry) {
    String snippet = entry.getSnippet();
    try {
      if (!TextUtils.isEmpty(snippet) && MmsSmsColumns.Types.isSymmetricEncryption(entry.getSnippetType())) {
        return new DisplayRecord.Body(masterCipher.decryptBody(snippet), true);
      }
      return new DisplayRecord.Body(snippet, true);
    } catch (InvalidMessageException error) {
      Log.w("ConversationListEntryMapper", error);
      return new DisplayRecord.Body(context.getString(R.string.EncryptingSmsDatabase_error_decrypting_message), true);
    }
  }
}