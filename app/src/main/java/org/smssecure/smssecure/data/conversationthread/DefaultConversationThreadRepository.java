package org.smssecure.smssecure.data.conversationthread;

import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;

import org.smssecure.smssecure.database.DatabaseContentProviders;
import org.smssecure.smssecure.database.DatabaseFactory;
import org.smssecure.smssecure.database.MmsSmsDatabase;
import org.smssecure.smssecure.database.ThreadDatabase;
import org.smssecure.smssecure.database.model.MessageRecord;
import org.smssecure.smssecure.domain.conversation.ConversationUnlockCapability;
import org.smssecure.smssecure.sms.MessageSender;
import org.smssecure.smssecure.util.SaveAttachmentTask;
import org.smssecure.smssecure.util.SaveAttachmentTask.Attachment;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor.TaskHandle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class DefaultConversationThreadRepository implements ConversationThreadRepository {
  interface DataSource {
    Cursor query(long threadId, long limit);
    long getLastSeen(long threadId);
    boolean delete(MessageReference message);
  }

  interface InvalidationSource {
    void register(long threadId, Runnable listener);
    void unregister(Runnable listener);
  }

  interface Operations {
    void resend(org.smssecure.smssecure.crypto.MasterSecret masterSecret, MessageRecord message);
    int saveAttachment(org.smssecure.smssecure.crypto.MasterSecret masterSecret, Attachment attachment);
  }

  private final AppTaskExecutor executor;
  private final DataSource dataSource;
  private final InvalidationSource invalidationSource;
  private final Operations operations;

  public DefaultConversationThreadRepository(Context context, AppTaskExecutor executor) {
    Context applicationContext = Objects.requireNonNull(context).getApplicationContext();
    this.executor = Objects.requireNonNull(executor);
    this.dataSource = new DatabaseDataSource(applicationContext);
    this.invalidationSource = new ContentResolverInvalidationSource(applicationContext);
    this.operations = new Operations() {
      @Override public void resend(org.smssecure.smssecure.crypto.MasterSecret masterSecret,
                                   MessageRecord message) {
        MessageSender.resend(applicationContext, masterSecret, message);
      }

      @Override public int saveAttachment(org.smssecure.smssecure.crypto.MasterSecret masterSecret,
                                          Attachment attachment) {
        return SaveAttachmentTask.save(applicationContext, masterSecret, attachment);
      }
    };
  }

  DefaultConversationThreadRepository(AppTaskExecutor executor, DataSource dataSource,
                                      InvalidationSource invalidationSource) {
    this(executor, dataSource, invalidationSource, new Operations() {
      @Override public void resend(org.smssecure.smssecure.crypto.MasterSecret masterSecret,
                                   MessageRecord message) {}
      @Override public int saveAttachment(org.smssecure.smssecure.crypto.MasterSecret masterSecret,
                                          Attachment attachment) {
        return SaveAttachmentTask.SUCCESS;
      }
    });
  }

  DefaultConversationThreadRepository(AppTaskExecutor executor, DataSource dataSource,
                                      InvalidationSource invalidationSource, Operations operations) {
    this.executor = Objects.requireNonNull(executor);
    this.dataSource = Objects.requireNonNull(dataSource);
    this.invalidationSource = Objects.requireNonNull(invalidationSource);
    this.operations = Objects.requireNonNull(operations);
  }

  @Override
  public Subscription observe(ConversationThreadQuery query, Observer observer) {
    return new DefaultSubscription(Objects.requireNonNull(query), Objects.requireNonNull(observer));
  }

  @Override
  public TaskHandle delete(Set<MessageReference> messages,
                           ConversationUnlockCapability unlockCapability,
                           MutationCallback callback) {
    Objects.requireNonNull(unlockCapability);
    Objects.requireNonNull(callback);
    Set<MessageReference> copy = validatedReferences(messages);
    return executor.submitSerial(
        () -> unlockCapability.use(secret -> {
          boolean threadDeleted = false;
          for (MessageReference message : copy) threadDeleted |= dataSource.delete(message);
          return threadDeleted;
        }),
        callback::onSuccess,
        callback::onFailure);
  }

        @Override
        public TaskHandle resend(MessageRecord message, ConversationUnlockCapability unlockCapability,
                 OperationCallback callback) {
          Objects.requireNonNull(message);
          Objects.requireNonNull(unlockCapability);
          Objects.requireNonNull(callback);
          return executor.submitSerial(
          () -> unlockCapability.use(secret -> {
            operations.resend(secret, message);
            return null;
          }),
          ignored -> callback.onSuccess(),
          callback::onFailure);
        }

        @Override
        public TaskHandle saveAttachment(Attachment attachment,
                     ConversationUnlockCapability unlockCapability,
                     AttachmentCallback callback) {
          Objects.requireNonNull(attachment);
          Objects.requireNonNull(unlockCapability);
          Objects.requireNonNull(callback);
          return executor.submitSerial(
          () -> unlockCapability.use(secret -> operations.saveAttachment(secret, attachment)),
          callback::onSuccess,
          callback::onFailure);
        }

  private static Set<MessageReference> validatedReferences(Set<MessageReference> messages) {
    Objects.requireNonNull(messages);
    if (messages.isEmpty()) throw new IllegalArgumentException("At least one message is required");
    LinkedHashSet<MessageReference> copy = new LinkedHashSet<>();
    for (MessageReference message : messages) copy.add(Objects.requireNonNull(message));
    return Collections.unmodifiableSet(copy);
  }

  private ConversationThreadSnapshot querySnapshot(ConversationThreadQuery query) {
    List<ConversationMessageRow> messages = new ArrayList<>();
    try (Cursor cursor = dataSource.query(query.getThreadId(), query.getLimit())) {
      if (cursor != null) {
        while (cursor.moveToNext()) messages.add(ConversationMessageRow.copyCurrent(cursor));
      }
    }
    long lastSeen = query.getLastSeen() == -1 ? dataSource.getLastSeen(query.getThreadId())
                                               : query.getLastSeen();
    boolean limited = query.hasLimit() && messages.size() >= query.getLimit();
    return new ConversationThreadSnapshot(messages, lastSeen, limited);
  }

  private final class DefaultSubscription implements Subscription {
    private final ConversationThreadQuery query;
    private final Observer observer;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicLong generation = new AtomicLong();
    private final Runnable invalidationListener = this::refresh;
    private volatile TaskHandle activeTask;

    private DefaultSubscription(ConversationThreadQuery query, Observer observer) {
      this.query = query;
      this.observer = observer;
      invalidationSource.register(query.getThreadId(), invalidationListener);
      refresh();
    }

    @Override
    public void refresh() {
      if (closed.get()) return;
      long requestedGeneration = generation.incrementAndGet();
      TaskHandle previous = activeTask;
      if (previous != null) previous.cancel();
      activeTask = executor.submitSerial(
          () -> querySnapshot(query),
          snapshot -> {
            if (!closed.get() && generation.get() == requestedGeneration) observer.onSnapshot(snapshot);
          },
          exception -> {
            if (!closed.get() && generation.get() == requestedGeneration) observer.onError(exception);
          });
    }

    @Override
    public void close() {
      if (!closed.compareAndSet(false, true)) return;
      generation.incrementAndGet();
      invalidationSource.unregister(invalidationListener);
      TaskHandle current = activeTask;
      if (current != null) current.cancel();
    }
  }

  private static final class DatabaseDataSource implements DataSource {
    private final MmsSmsDatabase mmsSmsDatabase;
    private final ThreadDatabase threadDatabase;
    private final org.smssecure.smssecure.database.MmsDatabase mmsDatabase;
    private final org.smssecure.smssecure.database.SmsDatabase smsDatabase;

    private DatabaseDataSource(Context context) {
      mmsSmsDatabase = DatabaseFactory.getMmsSmsDatabase(context);
      threadDatabase = DatabaseFactory.getThreadDatabase(context);
      mmsDatabase = DatabaseFactory.getMmsDatabase(context);
      smsDatabase = DatabaseFactory.getSmsDatabase(context);
    }

    @Override public Cursor query(long threadId, long limit) {
      return mmsSmsDatabase.getConversation(threadId, limit);
    }
    @Override public long getLastSeen(long threadId) { return threadDatabase.getLastSeen(threadId); }
    @Override public boolean delete(MessageReference message) {
      return message.isMms() ? mmsDatabase.delete(message.getMessageId())
                             : smsDatabase.deleteMessage(message.getMessageId());
    }
  }

  private static final class ContentResolverInvalidationSource implements InvalidationSource {
    private final Context context;
    private final Map<Runnable, ContentObserver> observers = new ConcurrentHashMap<>();

    private ContentResolverInvalidationSource(Context context) {
      this.context = context;
    }

    @Override
    public void register(long threadId, Runnable listener) {
      ContentObserver observer = new ContentObserver(null) {
        @Override public void onChange(boolean selfChange) { listener.run(); }
      };
      observers.put(listener, observer);
      context.getContentResolver().registerContentObserver(
          DatabaseContentProviders.Conversation.getUriForThread(threadId), true, observer);
    }

    @Override
    public void unregister(Runnable listener) {
      ContentObserver observer = observers.remove(listener);
      if (observer != null) context.getContentResolver().unregisterContentObserver(observer);
    }
  }
}
