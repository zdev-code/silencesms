package org.smssecure.smssecure.data.media;

import android.content.Context;
import android.database.Cursor;

import org.smssecure.smssecure.attachments.AttachmentId;
import org.smssecure.smssecure.attachments.DatabaseAttachment;
import org.smssecure.smssecure.database.DatabaseFactory;
import org.smssecure.smssecure.database.ImageDatabase.ImageRecord;
import org.smssecure.smssecure.domain.conversation.ConversationUnlockCapability;
import org.smssecure.smssecure.recipients.Recipient;
import org.smssecure.smssecure.recipients.RecipientFactory;
import org.smssecure.smssecure.util.SaveAttachmentTask;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor.TaskHandle;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class DefaultMediaOverviewRepository implements MediaOverviewRepository {
  private final Context context;
  private final AppTaskExecutor executor;

  public DefaultMediaOverviewRepository(Context context, AppTaskExecutor executor) {
    this.context = Objects.requireNonNull(context).getApplicationContext();
    this.executor = Objects.requireNonNull(executor);
  }

  @Override public TaskHandle load(long threadId, long recipientId,
                                   ConversationUnlockCapability unlockCapability,
                                   Callback<Snapshot> callback) {
    Objects.requireNonNull(unlockCapability);
    return submit(() -> unlockCapability.use(masterSecret -> {
      Recipient recipient = recipientId > -1
          ? RecipientFactory.getRecipientForId(context, recipientId, true)
          : threadId > -1
              ? DatabaseFactory.getThreadDatabase(context).getRecipientsForThreadId(threadId).getPrimaryRecipient()
              : null;
      return new Snapshot(recipient, readRecords(threadId));
    }), callback);
  }

  @Override
  public TaskHandle collectAttachments(long threadId,
                                       ConversationUnlockCapability unlockCapability,
                                       Callback<List<SaveAttachmentTask.Attachment>> callback) {
    Objects.requireNonNull(unlockCapability);
    return submit(() -> unlockCapability.use(masterSecret -> {
      List<SaveAttachmentTask.Attachment> attachments = new ArrayList<>();
      for (ImageRecord record : readRecords(threadId)) {
        attachments.add(new SaveAttachmentTask.Attachment(record.getAttachment().getDataUri(),
                                                           record.getContentType(), record.getDate()));
      }
      return attachments;
    }), callback);
  }

  @Override
  public TaskHandle loadPreview(long partRowId, long partUniqueId, long messageId, long threadId,
                                long recipientId, ConversationUnlockCapability unlockCapability,
                                Callback<PreviewSnapshot> callback) {
    Objects.requireNonNull(unlockCapability);
    return submit(() -> unlockCapability.use(masterSecret -> {
      DatabaseAttachment attachment = DatabaseFactory.getAttachmentDatabase(context)
          .getAttachment(new AttachmentId(partRowId, partUniqueId));
      if (attachment == null || attachment.getMmsId() != messageId ||
          DatabaseFactory.getMmsDatabase(context).getThreadIdForMessage(messageId) != threadId) {
        throw new SecurityException("Media attachment does not match its route IDs");
      }
      Recipient recipient = recipientId > 0L
          ? RecipientFactory.getRecipientForId(context, recipientId, true) : null;
      return new PreviewSnapshot(recipient, attachment.getDataUri(),
                                 attachment.getContentType(), attachment.getSize());
    }), callback);
  }

  @Override
  public TaskHandle saveAttachments(ConversationUnlockCapability unlockCapability,
                                    List<SaveAttachmentTask.Attachment> attachments,
                                    Callback<Integer> callback) {
    Objects.requireNonNull(unlockCapability);
    SaveAttachmentTask.Attachment[] copy = attachments.toArray(new SaveAttachmentTask.Attachment[0]);
    return submit(() -> unlockCapability.use(
        masterSecret -> SaveAttachmentTask.save(context, masterSecret, copy)), callback);
  }

  private List<ImageRecord> readRecords(long threadId) {
    List<ImageRecord> records = new ArrayList<>();
    try (Cursor cursor = DatabaseFactory.getImageDatabase(context).getImagesForThread(threadId)) {
      while (cursor.moveToNext()) records.add(ImageRecord.from(cursor));
    }
    return records;
  }

  private <T> TaskHandle submit(java.util.concurrent.Callable<T> work, Callback<T> callback) {
    Objects.requireNonNull(callback);
    return executor.submitSerial(work, callback::onSuccess, callback::onFailure);
  }
}