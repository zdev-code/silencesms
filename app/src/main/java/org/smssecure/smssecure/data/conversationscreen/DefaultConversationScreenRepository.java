package org.smssecure.smssecure.data.conversationscreen;

import android.content.Context;

import org.smssecure.smssecure.crypto.MasterCipher;
import org.smssecure.smssecure.database.DatabaseFactory;
import org.smssecure.smssecure.database.DraftDatabase;
import org.smssecure.smssecure.database.DraftDatabase.Drafts;
import org.smssecure.smssecure.database.RecipientPreferenceDatabase;
import org.smssecure.smssecure.database.ThreadDatabase;
import org.smssecure.smssecure.domain.conversation.ConversationUnlockCapability;
import org.smssecure.smssecure.mms.OutgoingMediaMessage;
import org.smssecure.smssecure.mms.OutgoingSecureMediaMessage;
import org.smssecure.smssecure.notifications.MessageNotifier;
import org.smssecure.smssecure.recipients.RecipientFactory;
import org.smssecure.smssecure.recipients.Recipients;
import org.smssecure.smssecure.sms.MessageSender;
import org.smssecure.smssecure.sms.OutgoingEncryptedMessage;
import org.smssecure.smssecure.sms.OutgoingTextMessage;
import org.smssecure.smssecure.util.Util;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor.TaskHandle;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class DefaultConversationScreenRepository implements ConversationScreenRepository {
  interface DataSource {
    Recipients recipients(long[] recipientIds);
    void setMuted(Recipients recipients, long until);
    void setBlocked(Recipients recipients, boolean blocked);
    long findExistingThread(Recipients recipients);
    long getOrCreateThread(Recipients recipients, int distributionType);
    void setDistributionType(long threadId, int distributionType);
    void deleteThread(long threadId);
    void setArchived(long threadId, boolean archived);
    Optional<RecipientPreferenceDatabase.RecipientsPreferences> preferences(long[] recipientIds);
    List<DraftDatabase.Draft> restoreDrafts(MasterCipher cipher, long threadId);
    long saveDrafts(MasterCipher cipher, long threadId, Recipients recipients, int distributionType,
                    Drafts drafts);
    void markRead(long threadId);
    void markLastSeen(long threadId);
    void setDefaultSubscription(Recipients recipients, int subscriptionId);
    void updateNotification(org.smssecure.smssecure.crypto.MasterSecret masterSecret);
    boolean isMmsCapable();
    long sendText(org.smssecure.smssecure.crypto.MasterSecret masterSecret, TextSendRequest request);
    long sendMedia(org.smssecure.smssecure.crypto.MasterSecret masterSecret, MediaSendRequest request);
  }

  private final AppTaskExecutor executor;
  private final DataSource dataSource;

  public DefaultConversationScreenRepository(Context context, AppTaskExecutor executor) {
    Context applicationContext = Objects.requireNonNull(context).getApplicationContext();
    this.executor = Objects.requireNonNull(executor);
    this.dataSource = new DatabaseDataSource(applicationContext);
  }

  DefaultConversationScreenRepository(AppTaskExecutor executor, DataSource dataSource) {
    this.executor = Objects.requireNonNull(executor);
    this.dataSource = Objects.requireNonNull(dataSource);
  }

  @Override public TaskHandle setMuted(long[] ids, long until, Callback<Void> callback) {
    long[] copy = copyIds(ids);
    return submit(() -> { dataSource.setMuted(dataSource.recipients(copy), until); return null; }, callback);
  }

  @Override public TaskHandle setBlocked(long[] ids, boolean blocked, Callback<Void> callback) {
    long[] copy = copyIds(ids);
    return submit(() -> { dataSource.setBlocked(dataSource.recipients(copy), blocked); return null; }, callback);
  }

  @Override public TaskHandle findExistingThread(long[] ids, Callback<Long> callback) {
    long[] copy = copyIds(ids);
    return submit(() -> dataSource.findExistingThread(dataSource.recipients(copy)), callback);
  }

  @Override public TaskHandle getOrCreateThread(long[] ids, int distributionType, Callback<Long> callback) {
    long[] copy = copyIds(ids);
    return submit(() -> dataSource.getOrCreateThread(dataSource.recipients(copy), distributionType), callback);
  }

  @Override public TaskHandle setDistributionType(long threadId, int type, Callback<Void> callback) {
    requireThread(threadId);
    return submit(() -> { dataSource.setDistributionType(threadId, type); return null; }, callback);
  }

  @Override public TaskHandle deleteThread(long threadId, Callback<Void> callback) {
    requireThread(threadId);
    return submit(() -> { dataSource.deleteThread(threadId); return null; }, callback);
  }

  @Override public TaskHandle setArchived(long threadId, boolean archived, Callback<Void> callback) {
    requireThread(threadId);
    return submit(() -> { dataSource.setArchived(threadId, archived); return null; }, callback);
  }

  @Override public TaskHandle loadDefaultSubscription(long[] ids, Callback<Optional<Integer>> callback) {
    long[] copy = copyIds(ids);
    return submit(() -> dataSource.preferences(copy)
        .flatMap(RecipientPreferenceDatabase.RecipientsPreferences::getDefaultSubscriptionId), callback);
  }

  @Override
  public TaskHandle restoreDrafts(long threadId, ConversationUnlockCapability capability,
                                  Callback<List<DraftDatabase.Draft>> callback) {
    requireThreadOrNew(threadId);
    Objects.requireNonNull(capability);
    return submit(() -> capability.use(secret ->
        dataSource.restoreDrafts(new MasterCipher(secret), threadId)), callback);
  }

  @Override
  public TaskHandle saveDrafts(long threadId, long[] ids, int distributionType, Drafts drafts,
                               ConversationUnlockCapability capability, Callback<Long> callback) {
    requireThreadOrNew(threadId);
    long[] copy = copyIds(ids);
    Objects.requireNonNull(drafts);
    Objects.requireNonNull(capability);
    return submit(() -> capability.use(secret -> dataSource.saveDrafts(new MasterCipher(secret),
        threadId, dataSource.recipients(copy), distributionType, drafts)), callback);
  }

  @Override
  public TaskHandle markRead(long threadId, ConversationUnlockCapability capability,
                             Callback<Void> callback) {
    if (threadId <= 0) return completed(callback);
    Objects.requireNonNull(capability);
    return submit(() -> capability.use(secret -> {
      dataSource.markRead(threadId);
      dataSource.updateNotification(secret);
      return null;
    }), callback);
  }

  @Override public TaskHandle markLastSeen(long threadId, Callback<Void> callback) {
    if (threadId <= 0) return completed(callback);
    return submit(() -> { dataSource.markLastSeen(threadId); return null; }, callback);
  }

  @Override public TaskHandle setDefaultSubscription(long[] ids, int subscriptionId,
                                                      Callback<Void> callback) {
    long[] copy = copyIds(ids);
    return submit(() -> {
      dataSource.setDefaultSubscription(dataSource.recipients(copy), subscriptionId);
      return null;
    }, callback);
  }

  @Override public TaskHandle loadMmsCapability(Callback<Boolean> callback) {
    return submit(dataSource::isMmsCapable, callback);
  }

  @Override
  public TaskHandle sendText(TextSendRequest request, ConversationUnlockCapability capability,
                             Callback<Long> callback) {
    Objects.requireNonNull(request);
    Objects.requireNonNull(capability);
    return submitParallel(() -> capability.use(secret -> dataSource.sendText(secret, request)), callback);
  }

  @Override
  public TaskHandle sendMedia(MediaSendRequest request, ConversationUnlockCapability capability,
                              Callback<Long> callback) {
    Objects.requireNonNull(request);
    Objects.requireNonNull(capability);
    return submitParallel(() -> capability.use(secret -> dataSource.sendMedia(secret, request)), callback);
  }

  private <T> TaskHandle submit(java.util.concurrent.Callable<T> work, Callback<T> callback) {
    Objects.requireNonNull(callback);
    return executor.submitSerial(work, callback::onSuccess, callback::onFailure);
  }

  private <T> TaskHandle submitParallel(java.util.concurrent.Callable<T> work, Callback<T> callback) {
    Objects.requireNonNull(callback);
    return executor.submitParallel(work, callback::onSuccess, callback::onFailure);
  }

  private TaskHandle completed(Callback<Void> callback) {
    return submit(() -> null, callback);
  }

  private static long[] copyIds(long[] ids) {
    Objects.requireNonNull(ids);
    if (ids.length == 0) throw new IllegalArgumentException("Recipients are required");
    return ids.clone();
  }

  private static void requireThread(long threadId) {
    if (threadId <= 0) throw new IllegalArgumentException("Thread ID must be positive");
  }

  private static void requireThreadOrNew(long threadId) {
    if (threadId == 0 || threadId < -1) throw new IllegalArgumentException("Invalid thread ID");
  }

  private static final class DatabaseDataSource implements DataSource {
    private final Context context;
    private final ThreadDatabase threadDatabase;
    private final DraftDatabase draftDatabase;
    private final RecipientPreferenceDatabase preferenceDatabase;

    private DatabaseDataSource(Context context) {
      this.context = context;
      threadDatabase = DatabaseFactory.getThreadDatabase(context);
      draftDatabase = DatabaseFactory.getDraftDatabase(context);
      preferenceDatabase = DatabaseFactory.getRecipientPreferenceDatabase(context);
    }

    @Override public Recipients recipients(long[] ids) {
      return RecipientFactory.getRecipientsForIds(context, ids, false);
    }
    @Override public void setMuted(Recipients recipients, long until) {
      preferenceDatabase.setMuted(recipients, until);
    }
    @Override public void setBlocked(Recipients recipients, boolean blocked) {
      preferenceDatabase.setBlocked(recipients, blocked);
    }
    @Override public long findExistingThread(Recipients recipients) {
      return threadDatabase.getThreadIdIfExistsFor(recipients);
    }

    @Override public long getOrCreateThread(Recipients recipients, int type) {
      return threadDatabase.getThreadIdFor(recipients, type);
    }
    @Override public void setDistributionType(long threadId, int type) {
      threadDatabase.setDistributionType(threadId, type);
    }
    @Override public void deleteThread(long threadId) { threadDatabase.deleteConversation(threadId); }
    @Override public void setArchived(long threadId, boolean archived) {
      if (archived) threadDatabase.archiveConversation(threadId);
      else          threadDatabase.unarchiveConversation(threadId);
    }
    @Override public Optional<RecipientPreferenceDatabase.RecipientsPreferences> preferences(long[] ids) {
      return preferenceDatabase.getRecipientsPreferences(ids);
    }
    @Override public List<DraftDatabase.Draft> restoreDrafts(MasterCipher cipher, long threadId) {
      List<DraftDatabase.Draft> drafts = draftDatabase.getDrafts(cipher, threadId);
      draftDatabase.clearDrafts(threadId);
      return drafts;
    }
    @Override public long saveDrafts(MasterCipher cipher, long threadId, Recipients recipients,
                                     int type, Drafts drafts) {
      long savedThreadId = threadId;
      if (drafts.size() > 0) {
        if (savedThreadId == -1) savedThreadId = threadDatabase.getThreadIdFor(recipients, type);
        draftDatabase.insertDrafts(cipher, savedThreadId, drafts);
        threadDatabase.updateSnippet(savedThreadId, drafts.getSnippet(context), drafts.getUriSnippet(context),
            System.currentTimeMillis(),
            org.smssecure.smssecure.database.MmsSmsColumns.Types.BASE_DRAFT_TYPE, true);
      } else if (savedThreadId > 0) {
        threadDatabase.update(savedThreadId, false);
      }
      return savedThreadId;
    }
    @Override public void markRead(long threadId) { threadDatabase.setRead(threadId); }
    @Override public void markLastSeen(long threadId) { threadDatabase.setLastSeen(threadId); }
    @Override public void setDefaultSubscription(Recipients recipients, int id) {
      preferenceDatabase.setDefaultSubscriptionId(recipients, id);
    }
    @Override public void updateNotification(org.smssecure.smssecure.crypto.MasterSecret secret) {
      MessageNotifier.updateNotification(context, secret);
    }
    @Override public boolean isMmsCapable() { return Util.isMmsCapable(context); }
    @Override public long sendText(org.smssecure.smssecure.crypto.MasterSecret secret,
                                   TextSendRequest request) {
      Recipients recipients = recipients(request.getRecipientIds());
      OutgoingTextMessage message = request.isSecure()
          ? new OutgoingEncryptedMessage(recipients, request.getBody(), request.getSubscriptionId())
          : new OutgoingTextMessage(recipients, request.getBody(), request.getSubscriptionId());
      return MessageSender.send(context, secret, message, request.getThreadId(), true);
    }
    @Override public long sendMedia(org.smssecure.smssecure.crypto.MasterSecret secret,
                                    MediaSendRequest request) {
      Recipients recipients = recipients(request.getRecipientIds());
      OutgoingMediaMessage message = new OutgoingMediaMessage(
          recipients, request.getSlideDeck(), request.getBody(), request.getSentTimeMillis(),
          request.getSubscriptionId(), request.getDistributionType());
      if (request.isSecure()) message = new OutgoingSecureMediaMessage(message);
      return MessageSender.send(context, secret, message, request.getThreadId(), true);
    }
  }
}
