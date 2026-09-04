package org.smssecure.smssecure.ui.databaseupgrade;

import android.content.Context;

import androidx.annotation.NonNull;

import org.smssecure.smssecure.domain.conversation.ConversationUnlockCapability;
import org.smssecure.smssecure.domain.security.UnlockSession;

import java.util.Objects;

public final class DatabaseUpgradeController implements AutoCloseable {
  public interface Observer {
    void onProgress(int progress, int total);
    void onComplete();
    void onFailure(@NonNull Exception exception);
  }

  public interface Subscription extends AutoCloseable {
    @Override void close();
  }

  public interface Operation {
    void start(int fromVersion, int targetVersion,
               @NonNull ConversationUnlockCapability capability);
    @NonNull Subscription observe(@NonNull Observer observer);
    void clearCompletedRecord();
    void finalizeWithoutUpgrade(@NonNull Context context,
                  @NonNull UnlockSession unlockSession) throws Exception;
  }

  private final Operation operation;
  private Subscription subscription;
  private int observationGeneration;
  private boolean started;
  private boolean completionClaimed;
  private boolean closed;

  public DatabaseUpgradeController(@NonNull Operation operation) {
    this.operation = Objects.requireNonNull(operation);
  }

  public void start(int fromVersion, int targetVersion,
                    @NonNull ConversationUnlockCapability capability) {
    if (closed || started) return;
    started = true;
    operation.start(fromVersion, targetVersion, capability);
  }

  public void attach(@NonNull Observer observer) {
    Objects.requireNonNull(observer);
    if (closed) return;
    detach();
    int generation = observationGeneration;
    Subscription newSubscription = operation.observe(new Observer() {
      @Override public void onProgress(int progress, int total) {
        if (isCurrent(generation)) observer.onProgress(progress, total);
      }

      @Override public void onComplete() {
        if (isCurrent(generation)) observer.onComplete();
      }

      @Override public void onFailure(@NonNull Exception exception) {
        if (isCurrent(generation)) observer.onFailure(exception);
      }
    });
    if (isCurrent(generation)) subscription = newSubscription;
    else newSubscription.close();
  }

  public void detach() {
    observationGeneration++;
    if (subscription != null) subscription.close();
    subscription = null;
  }

  public boolean claimCompletion() {
    if (closed || completionClaimed) return false;
    completionClaimed = true;
    return true;
  }

  public void clearCompletedRecord() {
    operation.clearCompletedRecord();
  }

  public void finalizeWithoutUpgrade(@NonNull Context context,
                                     @NonNull UnlockSession unlockSession) throws Exception {
    operation.finalizeWithoutUpgrade(context, unlockSession);
  }

  private boolean isCurrent(int generation) {
    return !closed && generation == observationGeneration;
  }

  @Override
  public void close() {
    if (closed) return;
    detach();
    closed = true;
  }
}