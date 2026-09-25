package org.smssecure.smssecure.database;

import android.content.Context;
import android.util.Log;

import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.crypto.SecurityEvent;
import org.smssecure.smssecure.crypto.storage.VendoredSessionStore;
import org.smssecure.smssecure.database.model.SmsMessageRecord;

public final class SmsEndSessionReplay {
  private static final String TAG = SmsEndSessionReplay.class.getSimpleName();

  interface SessionEffect {
    VendoredSessionStore.DeleteOutcome apply(SmsSendAttemptDatabase.PendingEndSession effect);
  }

  private SmsEndSessionReplay() {}

  public static int replay(Context context, MasterSecret masterSecret, int limit) {
    SmsSendAttemptDatabase ledger = DatabaseFactory.getSmsSendAttemptDatabase(context);
    return replay(ledger, limit, effect -> {
      try {
        SmsMessageRecord record = DatabaseFactory.getEncryptingSmsDatabase(context)
            .getMessage(masterSecret, effect.messageId);
        if (!record.isEndSession() || record.getSubscriptionId() != effect.subscriptionId ||
            record.getIndividualRecipient() == null) return VendoredSessionStore.DeleteOutcome.CHANGED;
        DatabaseFactory.getSmsDatabase(context).notifyMessageStateChanged(effect.messageId);
        VendoredSessionStore store = new VendoredSessionStore(context, masterSecret, effect.subscriptionId);
        VendoredSessionStore.DeleteOutcome outcome = store.deleteSessionIfUnchanged(
            record.getIndividualRecipient().getNumber(), effect.snapshot);
        if (outcome != VendoredSessionStore.DeleteOutcome.CHANGED) {
          SecurityEvent.broadcastSecurityUpdateEvent(context, record.getThreadId());
        }
        return outcome;
      } catch (NoSuchMessageException missing) {
        Log.w(TAG, "End-session message missing for pending attempt", missing);
        return VendoredSessionStore.DeleteOutcome.CHANGED;
      }
    });
  }

  static int replay(SmsSendAttemptDatabase ledger, int limit, SessionEffect sessionEffect) {
    int completed = 0;
    String afterId = null;
    while (true) {
      java.util.List<SmsSendAttemptDatabase.PendingEndSession> effects =
          ledger.getPendingEndSessionsAfter(afterId, limit);
      for (SmsSendAttemptDatabase.PendingEndSession effect : effects) {
        if (sessionEffect.apply(effect) != VendoredSessionStore.DeleteOutcome.CHANGED &&
            ledger.acknowledgeEndSession(effect.attemptId, effect.snapshot)) completed++;
        afterId = effect.attemptId;
      }
      if (effects.size() < limit) return completed;
    }
  }
}