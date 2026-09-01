package org.smssecure.smssecure.data.conversationthread;

import org.smssecure.smssecure.domain.conversation.ConversationUnlockCapability;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor.TaskHandle;

import java.util.Set;

public interface ConversationThreadRepository {
  Subscription observe(ConversationThreadQuery query, Observer observer);
  TaskHandle delete(Set<MessageReference> messages, ConversationUnlockCapability unlockCapability,
                    MutationCallback callback);

  interface Subscription extends AutoCloseable {
    void refresh();
    @Override void close();
  }

  interface Observer {
    void onSnapshot(ConversationThreadSnapshot snapshot);
    void onError(Exception exception);
  }

  interface MutationCallback {
    void onSuccess(boolean threadDeleted);
    void onFailure(Exception exception);
  }

  final class MessageReference {
    private final long    messageId;
    private final boolean mms;

    public MessageReference(long messageId, boolean mms) {
      if (messageId <= 0) throw new IllegalArgumentException("Message ID must be positive");
      this.messageId = messageId;
      this.mms       = mms;
    }

    public long getMessageId() { return messageId; }
    public boolean isMms() { return mms; }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof MessageReference)) return false;
      MessageReference reference = (MessageReference) other;
      return messageId == reference.messageId && mms == reference.mms;
    }

    @Override
    public int hashCode() {
      return 31 * Long.hashCode(messageId) + Boolean.hashCode(mms);
    }
  }
}
