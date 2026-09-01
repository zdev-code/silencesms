package org.smssecure.smssecure.domain.conversation;

import android.content.Context;
import android.net.Uri;

import org.smssecure.smssecure.attachments.Attachment;
import org.smssecure.smssecure.attachments.UriAttachment;
import org.smssecure.smssecure.crypto.MasterCipher;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.crypto.SessionUtil;
import org.smssecure.smssecure.database.AttachmentDatabase;
import org.smssecure.smssecure.database.DatabaseFactory;
import org.smssecure.smssecure.database.DraftDatabase;
import org.smssecure.smssecure.database.ThreadDatabase;
import org.smssecure.smssecure.mms.OutgoingMediaMessage;
import org.smssecure.smssecure.mms.OutgoingSecureMediaMessage;
import org.smssecure.smssecure.recipients.RecipientFactory;
import org.smssecure.smssecure.recipients.Recipients;
import org.smssecure.smssecure.sms.MessageSender;
import org.smssecure.smssecure.sms.OutgoingEncryptedMessage;
import org.smssecure.smssecure.sms.OutgoingTextMessage;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor.TaskHandle;
import org.smssecure.smssecure.util.dualsim.SubscriptionManagerCompat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class SendSelectedDrafts {
  private final AppTaskExecutor executor;
  private final DraftStore      draftStore;
  private final RecipientResolver recipientResolver;
  private final SecurityResolver  securityResolver;
  private final DraftSender       draftSender;
  private final SubscriptionResolver subscriptionResolver;

  public SendSelectedDrafts(Context context, AppTaskExecutor executor) {
    Context applicationContext = Objects.requireNonNull(context).getApplicationContext();
    this.executor             = Objects.requireNonNull(executor);
    this.draftStore           = new DatabaseDraftStore(applicationContext);
    this.recipientResolver    = ids -> RecipientFactory.getRecipientsForIds(applicationContext, ids, true);
    this.securityResolver     = (secret, recipients, subscriptionId) ->
        SessionUtil.hasSession(applicationContext, secret,
            recipients.getPrimaryRecipient().getNumber(), subscriptionId);
    this.draftSender          = new MessageDraftSender(applicationContext);
    this.subscriptionResolver = () -> SubscriptionManagerCompat.getDefaultMessagingSubscriptionId().orElse(-1);
  }

  SendSelectedDrafts(AppTaskExecutor executor, DraftStore draftStore,
                     RecipientResolver recipientResolver, SecurityResolver securityResolver,
                     DraftSender draftSender, SubscriptionResolver subscriptionResolver) {
    this.executor             = Objects.requireNonNull(executor);
    this.draftStore           = Objects.requireNonNull(draftStore);
    this.recipientResolver    = Objects.requireNonNull(recipientResolver);
    this.securityResolver     = Objects.requireNonNull(securityResolver);
    this.draftSender          = Objects.requireNonNull(draftSender);
    this.subscriptionResolver = Objects.requireNonNull(subscriptionResolver);
  }

  public TaskHandle execute(Input input, ConversationUnlockCapability unlockCapability, Callback callback) {
    Objects.requireNonNull(input);
    Objects.requireNonNull(unlockCapability);
    Objects.requireNonNull(callback);
    return executor.submitSerial(
        () -> unlockCapability.use(secret -> run(input, secret)),
        callback::onSuccess,
        callback::onFailure);
  }

  Result run(Input input, MasterSecret masterSecret) throws Exception {
    int sentDraftCount = 0;
    int skippedThreadCount = 0;
    Map<Long, List<DraftDatabase.Draft>> selectedDrafts = new LinkedHashMap<>();
    for (Target target : input.getTargets()) {
      selectedDrafts.put(target.getThreadId(),
          new ArrayList<>(draftStore.getDrafts(masterSecret, target.getThreadId())));
    }
    for (Target target : input.getTargets()) {
      List<DraftDatabase.Draft> drafts = selectedDrafts.get(target.getThreadId());
      Recipients recipients = recipientResolver.resolve(target.getRecipientIds());
      if (recipients == null) {
        skippedThreadCount++;
        draftStore.clearDrafts(target.getThreadId());
        continue;
      }

      boolean singleRecipient = recipients.isSingleRecipient() && !recipients.isGroupRecipient();
      int subscriptionId = subscriptionResolver.getDefaultMessagingSubscriptionId();
      boolean secure = singleRecipient && securityResolver.hasSession(masterSecret, recipients, subscriptionId);
      if (drafts.size() > 1 && !DraftDatabase.Draft.TEXT.equals(drafts.get(1).getType())) {
        draftSender.sendMedia(masterSecret, recipients, secure, drafts.get(1), target.getThreadId(),
                              drafts.get(0).getValue());
        sentDraftCount++;
      } else {
        for (DraftDatabase.Draft draft : drafts) {
          if (DraftDatabase.Draft.TEXT.equals(draft.getType())) {
            draftSender.sendText(masterSecret, recipients, secure, draft, target.getThreadId());
          } else {
            draftSender.sendMedia(masterSecret, recipients, secure, draft, target.getThreadId(), null);
          }
          sentDraftCount++;
        }
      }
      draftStore.clearDrafts(target.getThreadId());
    }
    return new Result(sentDraftCount, skippedThreadCount);
  }

  public static final class Input {
    private final List<Target> targets;

    public Input(List<Target> targets) {
      this.targets = List.copyOf(targets);
      if (this.targets.isEmpty()) throw new IllegalArgumentException("At least one target is required");
    }

    public List<Target> getTargets() { return targets; }
  }

  public static final class Target {
    private final long   threadId;
    private final String recipientIds;

    public Target(long threadId, String recipientIds) {
      if (threadId <= 0) throw new IllegalArgumentException("Thread ID must be positive");
      this.threadId     = threadId;
      this.recipientIds = Objects.requireNonNull(recipientIds);
    }

    public long getThreadId() { return threadId; }
    public String getRecipientIds() { return recipientIds; }
  }

  public static final class Result {
    private final int sentDraftCount;
    private final int skippedThreadCount;

    Result(int sentDraftCount, int skippedThreadCount) {
      this.sentDraftCount    = sentDraftCount;
      this.skippedThreadCount = skippedThreadCount;
    }

    public int getSentDraftCount() { return sentDraftCount; }
    public int getSkippedThreadCount() { return skippedThreadCount; }
  }

  public interface Callback {
    void onSuccess(Result result);
    void onFailure(Exception exception);
  }

  interface DraftStore {
    List<DraftDatabase.Draft> getDrafts(MasterSecret masterSecret, long threadId);
    void clearDrafts(long threadId);
  }

  interface RecipientResolver { Recipients resolve(String recipientIds); }
  interface SecurityResolver {
    boolean hasSession(MasterSecret masterSecret, Recipients recipients, int subscriptionId);
  }
  interface SubscriptionResolver { int getDefaultMessagingSubscriptionId(); }

  interface DraftSender {
    void sendText(MasterSecret masterSecret, Recipients recipients, boolean secure,
                  DraftDatabase.Draft draft, long threadId);
    void sendMedia(MasterSecret masterSecret, Recipients recipients, boolean secure,
                   DraftDatabase.Draft draft, long threadId, String forcedValue);
  }

  private static final class DatabaseDraftStore implements DraftStore {
    private final DraftDatabase draftDatabase;

    private DatabaseDraftStore(Context context) {
      this.draftDatabase = DatabaseFactory.getDraftDatabase(context);
    }

    @Override
    public List<DraftDatabase.Draft> getDrafts(MasterSecret masterSecret, long threadId) {
      return draftDatabase.getDrafts(new MasterCipher(masterSecret), threadId);
    }

    @Override public void clearDrafts(long threadId) { draftDatabase.clearDrafts(threadId); }
  }

  private static final class MessageDraftSender implements DraftSender {
    private final Context context;

    private MessageDraftSender(Context context) { this.context = context; }

    @Override
    public void sendText(MasterSecret masterSecret, Recipients recipients, boolean secure,
                         DraftDatabase.Draft draft, long threadId) {
      OutgoingTextMessage message = secure
          ? new OutgoingEncryptedMessage(recipients, draft.getValue(), -1)
          : new OutgoingTextMessage(recipients, draft.getValue(), -1);
      MessageSender.send(context, masterSecret, message, threadId, false);
    }

    @Override
    public void sendMedia(MasterSecret masterSecret, Recipients recipients, boolean secure,
                          DraftDatabase.Draft draft, long threadId, String forcedValue) {
      List<Attachment> attachments = new ArrayList<>();
      attachments.add(new UriAttachment(Uri.parse(draft.getValue()), draft.getType() + "/*",
                                        AttachmentDatabase.TRANSFER_PROGRESS_DONE));
      OutgoingMediaMessage message = new OutgoingMediaMessage(recipients,
          forcedValue != null ? forcedValue : "", attachments, System.currentTimeMillis(), -1,
          ThreadDatabase.DistributionTypes.BROADCAST);
      if (secure) message = new OutgoingSecureMediaMessage(message);
      MessageSender.send(context, masterSecret, message, threadId, false);
    }
  }
}