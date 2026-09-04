package org.smssecure.smssecure.data.media;

import android.net.Uri;

import org.smssecure.smssecure.database.ImageDatabase.ImageRecord;
import org.smssecure.smssecure.domain.conversation.ConversationUnlockCapability;
import org.smssecure.smssecure.recipients.Recipient;
import org.smssecure.smssecure.util.SaveAttachmentTask;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor.TaskHandle;

import java.util.List;

public interface MediaOverviewRepository {
  TaskHandle load(long threadId, long recipientId, ConversationUnlockCapability unlockCapability,
                  Callback<Snapshot> callback);
  TaskHandle collectAttachments(long threadId, ConversationUnlockCapability unlockCapability,
                                Callback<List<SaveAttachmentTask.Attachment>> callback);
  TaskHandle loadPreview(long partRowId, long partUniqueId, long messageId, long threadId,
                         long recipientId, ConversationUnlockCapability unlockCapability,
                         Callback<PreviewSnapshot> callback);
  TaskHandle saveAttachments(ConversationUnlockCapability unlockCapability,
                             List<SaveAttachmentTask.Attachment> attachments,
                             Callback<Integer> callback);

  interface Callback<T> {
    void onSuccess(T value);
    void onFailure(Exception exception);
  }

  final class Snapshot {
    private final Recipient recipient;
    private final List<ImageRecord> records;

    public Snapshot(Recipient recipient, List<ImageRecord> records) {
      this.recipient = recipient;
      this.records = List.copyOf(records);
    }

    public Recipient getRecipient() { return recipient; }
    public List<ImageRecord> getRecords() { return records; }
  }

  final class PreviewSnapshot {
    private final Recipient recipient;
    private final Uri uri;
    private final String contentType;
    private final long size;

    public PreviewSnapshot(Recipient recipient, Uri uri, String contentType, long size) {
      this.recipient = recipient;
      this.uri = uri;
      this.contentType = contentType;
      this.size = size;
    }

    public Recipient getRecipient() { return recipient; }
    public Uri getUri() { return uri; }
    public String getContentType() { return contentType; }
    public long getSize() { return size; }
  }
}