package org.smssecure.smssecure.data.message;

import org.smssecure.smssecure.database.model.MessageRecord;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.domain.conversation.ConversationUnlockCapability;
import org.smssecure.smssecure.recipients.Recipients;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor.TaskHandle;

public interface MessageDetailsRepository {
  TaskHandle load(String transport, long messageId, ConversationUnlockCapability unlockCapability,
                  Callback callback);

  interface Callback {
    void onSuccess(Result result);
    void onFailure(Exception exception);
  }

  final class Result {
    private final MasterSecret masterSecret;
    private final MessageRecord message;
    private final Recipients recipients;

    public Result(MasterSecret masterSecret, MessageRecord message, Recipients recipients) {
      this.masterSecret = masterSecret;
      this.message = message;
      this.recipients = recipients;
    }

    public MasterSecret getMasterSecret() { return masterSecret; }
    public MessageRecord getMessage() { return message; }
    public Recipients getRecipients() { return recipients; }
  }
}