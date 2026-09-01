package org.smssecure.smssecure.data.conversation;

import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;

import org.smssecure.smssecure.contacts.ContactAccessor;
import org.smssecure.smssecure.database.DatabaseContentProviders;
import org.smssecure.smssecure.database.DatabaseFactory;
import org.smssecure.smssecure.database.ThreadDatabase;
import org.smssecure.smssecure.domain.conversation.ConversationUnlockCapability;
import org.smssecure.smssecure.notifications.MessageNotifier;
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

public final class DefaultConversationRepository implements ConversationRepository {
  interface DataSource {
    Cursor query(ConversationListQuery query);
    int getArchivedCount();
    void archive(long threadId);
    void unarchive(long threadId);
    void delete(Set<Long> threadIds);
    void setRead(long threadId);
    void setUnread(long threadId);
  }

  interface NotificationUpdater {
    void update(org.smssecure.smssecure.crypto.MasterSecret masterSecret);
  }

  interface InvalidationSource {
    void register(Runnable listener);
    void unregister(Runnable listener);
  }

  private final AppTaskExecutor executor;
  private final DataSource      dataSource;
  private final InvalidationSource invalidationSource;
  private final NotificationUpdater notificationUpdater;

  public DefaultConversationRepository(Context context, AppTaskExecutor executor) {
    Context applicationContext = Objects.requireNonNull(context).getApplicationContext();
    this.executor           = Objects.requireNonNull(executor);
    this.dataSource         = new DatabaseDataSource(applicationContext);
    this.invalidationSource = new ContentResolverInvalidationSource(applicationContext);
    this.notificationUpdater = secret -> MessageNotifier.updateNotification(applicationContext, secret);
  }

  DefaultConversationRepository(AppTaskExecutor executor, DataSource dataSource,
                                InvalidationSource invalidationSource) {
    this(executor, dataSource, invalidationSource, secret -> {});
  }

  DefaultConversationRepository(AppTaskExecutor executor, DataSource dataSource,
                                InvalidationSource invalidationSource,
                                NotificationUpdater notificationUpdater) {
    this.executor           = Objects.requireNonNull(executor);
    this.dataSource         = Objects.requireNonNull(dataSource);
    this.invalidationSource = Objects.requireNonNull(invalidationSource);
    this.notificationUpdater = Objects.requireNonNull(notificationUpdater);
  }

  @Override
  public Subscription observe(ConversationListQuery query, Observer observer) {
    return new DefaultSubscription(Objects.requireNonNull(query), Objects.requireNonNull(observer));
  }

  @Override
  public TaskHandle archive(Set<Long> threadIds, MutationCallback callback) {
    Objects.requireNonNull(callback);
    Set<Long> validatedIds = validatedIds(threadIds);
    return executor.submitSerial(
        () -> {
          for (long threadId : validatedIds) dataSource.archive(threadId);
          return null;
        },
        ignored -> callback.onSuccess(),
        callback::onFailure);
  }

  @Override
  public TaskHandle unarchive(Set<Long> threadIds, MutationCallback callback) {
    Objects.requireNonNull(callback);
    Set<Long> validatedIds = validatedIds(threadIds);
    return executor.submitSerial(
        () -> {
          for (long threadId : validatedIds) dataSource.unarchive(threadId);
          return null;
        },
        ignored -> callback.onSuccess(),
        callback::onFailure);
  }

  @Override
  public TaskHandle delete(Set<Long> threadIds, ConversationUnlockCapability unlockCapability,
                           MutationCallback callback) {
    Objects.requireNonNull(unlockCapability);
    Objects.requireNonNull(callback);
    Set<Long> validatedIds = validatedIds(threadIds);
    return executor.submitSerial(
        () -> unlockCapability.use(secret -> {
            dataSource.delete(validatedIds);
            notificationUpdater.update(secret);
            return null;
          }),
        ignored -> callback.onSuccess(),
        callback::onFailure);
  }

  @Override
  public TaskHandle setArchivedFromSwipe(long threadId, boolean archived, boolean updateReadState,
                                         ConversationUnlockCapability unlockCapability,
                                         MutationCallback callback) {
    if (threadId <= 0) throw new IllegalArgumentException("Thread ID must be positive");
    Objects.requireNonNull(unlockCapability);
    Objects.requireNonNull(callback);
    return executor.submitSerial(
        () -> {
          if (updateReadState) {
            unlockCapability.use(secret -> {
              if (archived) dataSource.archive(threadId);
              else          dataSource.unarchive(threadId);
              if (archived) dataSource.setRead(threadId);
              else          dataSource.setUnread(threadId);
              notificationUpdater.update(secret);
              return null;
            });
          } else {
            if (archived) dataSource.archive(threadId);
            else          dataSource.unarchive(threadId);
          }
          return null;
        },
        ignored -> callback.onSuccess(),
        callback::onFailure);
  }

  private static Set<Long> validatedIds(Set<Long> threadIds) {
    Objects.requireNonNull(threadIds);
    if (threadIds.isEmpty()) throw new IllegalArgumentException("At least one thread ID is required");

    LinkedHashSet<Long> copy = new LinkedHashSet<>();
    for (Long threadId : threadIds) {
      if (threadId == null || threadId <= 0) {
        throw new IllegalArgumentException("Conversation mutations require positive thread IDs");
      }
      copy.add(threadId);
    }
    return Collections.unmodifiableSet(copy);
  }

  private ConversationListSnapshot querySnapshot(ConversationListQuery query) {
    List<ConversationListEntry> entries = new ArrayList<>();
    try (Cursor cursor = dataSource.query(query)) {
      if (cursor != null) {
        while (cursor.moveToNext()) {
          long threadId = cursor.getLong(cursor.getColumnIndexOrThrow(ThreadDatabase.ID));
          if (threadId <= 0) continue;
          int snippetUriIndex = cursor.getColumnIndexOrThrow(ThreadDatabase.SNIPPET_URI);
          entries.add(new ConversationListEntry(
              threadId,
              cursor.getString(cursor.getColumnIndexOrThrow(ThreadDatabase.RECIPIENT_IDS)),
              cursor.getLong(cursor.getColumnIndexOrThrow(ThreadDatabase.DATE)),
              cursor.getLong(cursor.getColumnIndexOrThrow(ThreadDatabase.MESSAGE_COUNT)),
              cursor.getInt(cursor.getColumnIndexOrThrow(ThreadDatabase.READ)) == 1,
              cursor.getString(cursor.getColumnIndexOrThrow(ThreadDatabase.SNIPPET)),
              cursor.getLong(cursor.getColumnIndexOrThrow(ThreadDatabase.SNIPPET_TYPE)),
              cursor.isNull(snippetUriIndex) ? null : cursor.getString(snippetUriIndex),
              cursor.getInt(cursor.getColumnIndexOrThrow(ThreadDatabase.TYPE)),
              cursor.getInt(cursor.getColumnIndexOrThrow(ThreadDatabase.ARCHIVED)) != 0,
              cursor.getInt(cursor.getColumnIndexOrThrow(ThreadDatabase.STATUS)),
              cursor.getLong(cursor.getColumnIndexOrThrow(ThreadDatabase.LAST_SEEN))));
        }
      }
    }
    int archivedCount = query.isArchived() || query.isFiltered() ? 0 : dataSource.getArchivedCount();
    return new ConversationListSnapshot(entries, archivedCount);
  }

  private final class DefaultSubscription implements Subscription {
    private final ConversationListQuery query;
    private final Observer              observer;
    private final AtomicBoolean         closed = new AtomicBoolean(false);
    private final AtomicLong            generation = new AtomicLong();
    private final Runnable              invalidationListener = this::refresh;
    private volatile TaskHandle         activeTask;

    private DefaultSubscription(ConversationListQuery query, Observer observer) {
      this.query    = query;
      this.observer = observer;
      invalidationSource.register(invalidationListener);
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
    private final Context        context;
    private final ThreadDatabase threadDatabase;

    private DatabaseDataSource(Context context) {
      this.context        = context;
      this.threadDatabase = DatabaseFactory.getThreadDatabase(context);
    }

    @Override
    public Cursor query(ConversationListQuery query) {
      if (query.isFiltered()) {
        List<String> numbers = ContactAccessor.getInstance()
                                              .getNumbersForThreadSearchFilter(context, query.getFilter());
        return threadDatabase.getFilteredConversationList(numbers);
      }
      return query.isArchived() ? threadDatabase.getArchivedConversationList()
                                : threadDatabase.getConversationList();
    }

    @Override
    public int getArchivedCount() {
      return threadDatabase.getArchivedConversationListCount();
    }

    @Override
    public void archive(long threadId) {
      threadDatabase.archiveConversation(threadId);
    }

    @Override
    public void unarchive(long threadId) {
      threadDatabase.unarchiveConversation(threadId);
    }

    @Override
    public void delete(Set<Long> threadIds) {
      threadDatabase.deleteConversations(threadIds);
    }

    @Override public void setRead(long threadId) { threadDatabase.setRead(threadId); }
    @Override public void setUnread(long threadId) { threadDatabase.setUnread(threadId); }
  }

  private static final class ContentResolverInvalidationSource implements InvalidationSource {
    private final Context                       context;
    private final Map<Runnable, ContentObserver> observers = new ConcurrentHashMap<>();

    private ContentResolverInvalidationSource(Context context) {
      this.context = context;
    }

    @Override
    public void register(Runnable listener) {
      ContentObserver observer = new ContentObserver(null) {
        @Override
        public void onChange(boolean selfChange) {
          listener.run();
        }
      };
      observers.put(listener, observer);
      context.getContentResolver().registerContentObserver(
          DatabaseContentProviders.ConversationList.CONTENT_URI, true, observer);
    }

    @Override
    public void unregister(Runnable listener) {
      ContentObserver observer = observers.remove(listener);
      if (observer != null) context.getContentResolver().unregisterContentObserver(observer);
    }
  }
}