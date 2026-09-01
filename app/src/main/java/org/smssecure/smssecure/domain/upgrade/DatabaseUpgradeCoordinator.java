package org.smssecure.smssecure.domain.upgrade;

import android.content.Context;

import org.smssecure.smssecure.ApplicationContext;
import org.smssecure.smssecure.DatabaseUpgradeActivity;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.database.DatabaseFactory;
import org.smssecure.smssecure.domain.conversation.ConversationUnlockCapability;
import org.smssecure.smssecure.notifications.MessageNotifier;
import org.smssecure.smssecure.util.ParcelUtil;
import org.smssecure.smssecure.util.SilencePreferences;
import org.smssecure.smssecure.util.VersionTracker;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor.TaskHandle;
import org.smssecure.smssecure.util.dualsim.DualSimUtil;
import org.smssecure.smssecure.util.dualsim.SubscriptionInfoCompat;
import org.smssecure.smssecure.util.dualsim.SubscriptionManagerCompat;
import org.whispersystems.jobqueue.EncryptionKeys;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DatabaseUpgradeCoordinator {
  public interface Observer {
    void onProgress(int progress, int total);
    void onComplete();
    void onFailure(Exception exception);
  }

  public interface Subscription extends AutoCloseable {
    @Override void close();
  }

  interface Steps {
    void runDatabase(MasterSecret secret, int fromVersion,
                     DatabaseUpgradeActivity.DatabaseUpgradeListener listener);
    void updateSimPrompt(int fromVersion);
    void migrateMultiSim(MasterSecret secret, int fromVersion);
    void finalizeUpgrade(MasterSecret secret);
  }

  private final AppTaskExecutor executor;
  private final UpgradeOperationStore.Storage store;
  private final Steps steps;
  private final CopyOnWriteArraySet<Observer> observers = new CopyOnWriteArraySet<>();
  private final AtomicBoolean running = new AtomicBoolean(false);
  private volatile TaskHandle activeTask;
  private volatile boolean complete;
  private volatile Exception failure;
  private volatile int progress;
  private volatile int total;

  public DatabaseUpgradeCoordinator(Context context, AppTaskExecutor executor) {
    Context applicationContext = Objects.requireNonNull(context).getApplicationContext();
    this.executor = Objects.requireNonNull(executor);
    this.store = new UpgradeOperationStore.PreferencesStorage(applicationContext);
    this.steps = new DefaultSteps(applicationContext);
  }

  DatabaseUpgradeCoordinator(AppTaskExecutor executor, UpgradeOperationStore.Storage store,
                             Steps steps) {
    this.executor = executor;
    this.store = store;
    this.steps = steps;
  }

  public Subscription observe(Observer observer) {
    observers.add(Objects.requireNonNull(observer));
    if (total > 0) observer.onProgress(progress, total);
    if (complete) observer.onComplete();
    else if (failure != null) observer.onFailure(failure);
    return () -> observers.remove(observer);
  }

  public void start(int fromVersion, int targetVersion,
                    ConversationUnlockCapability unlockCapability) {
    Objects.requireNonNull(unlockCapability);
    if (!running.compareAndSet(false, true)) return;
    complete = false;
    failure = null;
    UpgradeOperationStore.Record existing = store.read();
    UpgradeOperationStore.Record record = existing != null &&
        existing.getFromVersion() == fromVersion && existing.getTargetVersion() == targetVersion
        ? existing
        : new UpgradeOperationStore.Record(fromVersion, targetVersion,
                                           UpgradeOperationStore.Stage.DATABASE);
    store.write(record);
    activeTask = executor.submitSerial(
        () -> {
          run(record, unlockCapability);
          return null;
        },
        ignored -> {
          running.set(false);
          complete = true;
          for (Observer observer : observers) observer.onComplete();
        },
        exception -> {
          running.set(false);
          failure = exception;
          for (Observer observer : observers) observer.onFailure(exception);
        });
  }

  private void run(UpgradeOperationStore.Record initial,
                   ConversationUnlockCapability capability) throws Exception {
    int fromVersion = initial.getFromVersion();
    int targetVersion = initial.getTargetVersion();
    UpgradeOperationStore.Stage stage = initial.getStage();
    if (stage.ordinal() <= UpgradeOperationStore.Stage.DATABASE.ordinal()) {
      capability.use(secret -> {
        steps.runDatabase(secret, fromVersion, (progress, total) -> publishProgress(progress, total));
        return null;
      });
      stage = checkpoint(fromVersion, targetVersion, UpgradeOperationStore.Stage.SIM_PROMPT);
    }
    if (stage.ordinal() <= UpgradeOperationStore.Stage.SIM_PROMPT.ordinal()) {
      steps.updateSimPrompt(fromVersion);
      stage = checkpoint(fromVersion, targetVersion, UpgradeOperationStore.Stage.MULTI_SIM);
    }
    if (stage.ordinal() <= UpgradeOperationStore.Stage.MULTI_SIM.ordinal()) {
      capability.use(secret -> {
        steps.migrateMultiSim(secret, fromVersion);
        return null;
      });
      stage = checkpoint(fromVersion, targetVersion, UpgradeOperationStore.Stage.FINALIZE);
    }
    if (stage.ordinal() <= UpgradeOperationStore.Stage.FINALIZE.ordinal()) {
      capability.use(secret -> {
        steps.finalizeUpgrade(secret);
        return null;
      });
      checkpoint(fromVersion, targetVersion, UpgradeOperationStore.Stage.COMPLETE);
    }
  }

  private UpgradeOperationStore.Stage checkpoint(int fromVersion, int targetVersion,
                                                  UpgradeOperationStore.Stage stage) {
    store.write(new UpgradeOperationStore.Record(fromVersion, targetVersion, stage));
    return stage;
  }

  private void publishProgress(int progress, int total) {
    if (total <= 0) return;
    this.progress = progress;
    this.total = total;
    for (Observer observer : observers) observer.onProgress(progress, total);
  }

  public void clearCompletedRecord() {
    UpgradeOperationStore.Record record = store.read();
    if (record != null && record.getStage() == UpgradeOperationStore.Stage.COMPLETE) store.clear();
  }

  private static final class DefaultSteps implements Steps {
    private final Context context;

    private DefaultSteps(Context context) { this.context = context; }

    @Override
    public void runDatabase(MasterSecret secret, int fromVersion,
                            DatabaseUpgradeActivity.DatabaseUpgradeListener listener) {
      DatabaseFactory.getInstance(context).onApplicationLevelUpgrade(context, secret, fromVersion, listener);
    }

    @Override public void updateSimPrompt(int fromVersion) {
      if (fromVersion < DatabaseUpgradeActivity.ASK_FOR_SIM_CARD_VERSION &&
          !SilencePreferences.isFirstRun(context) &&
          SubscriptionManagerCompat.from(context).getActiveSubscriptionInfoList().size() > 1) {
        SilencePreferences.setSimCardAsked(context, false);
      }
    }

    @Override public void migrateMultiSim(MasterSecret secret, int fromVersion) {
      if (fromVersion >= DatabaseUpgradeActivity.MULTI_SIM_MULTI_KEYS_VERSION) return;
      List<SubscriptionInfoCompat> subscriptions =
          SubscriptionManagerCompat.from(context).getActiveSubscriptionInfoList();
      int smallerSlot = -1;
      int eligibleDeviceSubscriptionId = -1;
      for (SubscriptionInfoCompat subscription : subscriptions) {
        if (smallerSlot == -1 || subscription.getIccSlot() < smallerSlot) {
          smallerSlot = subscription.getIccSlot();
          eligibleDeviceSubscriptionId = subscription.getDeviceSubscriptionId();
        }
      }
      DualSimUtil.moveIdentityKeysAndSessionsToSubscriptionId(
          context, -1, eligibleDeviceSubscriptionId);
      DualSimUtil.generateKeysIfDoNotExist(context, secret, subscriptions);
      SubscriptionManagerCompat.from(context).updateActiveSubscriptionInfoList();
    }

    @Override public void finalizeUpgrade(MasterSecret secret) {
      ApplicationContext.getInstance(context).getJobManager()
          .setEncryptionKeys(new EncryptionKeys(ParcelUtil.serialize(secret)));
      MessageNotifier.updateNotification(context, secret);
      VersionTracker.updateLastSeenVersion(context);
    }
  }
}
