package org.smssecure.smssecure.database;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.smssecure.smssecure.jobs.sms.SmsSendAttempt;
import org.smssecure.smssecure.jobs.sms.SmsSendResultPolicy;
import org.smssecure.smssecure.jobs.sms.SmsSendRetryPolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class SmsSendAttemptDatabase {
  static final String CREATE_ATTEMPTS = "CREATE TABLE IF NOT EXISTS sms_send_attempt (" +
      "attempt_id TEXT PRIMARY KEY, message_id INTEGER NOT NULL, attempt_number INTEGER NOT NULL, " +
      "part_count INTEGER NOT NULL, subscription_id INTEGER NOT NULL, state TEXT NOT NULL, " +
      "created_at INTEGER NOT NULL, submitted_at INTEGER, completed_at INTEGER, delivery_completed_at INTEGER, " +
      "effect_pending INTEGER NOT NULL DEFAULT 0, retry_due_at INTEGER, secure_intent INTEGER, " +
      "end_session_snapshot TEXT, delivery_effect_pending INTEGER NOT NULL DEFAULT 0, " +
      "delivery_effect_claim_token TEXT, delivery_effect_claim_expires_at INTEGER, " +
      "UNIQUE(message_id, attempt_number))";
  static final String CREATE_PARTS = "CREATE TABLE IF NOT EXISTS sms_send_part (" +
      "attempt_id TEXT NOT NULL, part_index INTEGER NOT NULL, sent_result INTEGER, sent_at INTEGER, " +
      "delivered_result INTEGER, delivered_at INTEGER, " +
      "PRIMARY KEY(attempt_id, part_index), " +
      "FOREIGN KEY(attempt_id) REFERENCES sms_send_attempt(attempt_id) ON DELETE CASCADE)";

  static void createTables(SQLiteDatabase db) {
    db.execSQL(CREATE_ATTEMPTS);
    db.execSQL(CREATE_PARTS);
    db.execSQL("CREATE INDEX IF NOT EXISTS sms_send_attempt_message ON sms_send_attempt(message_id, state)");
    db.execSQL("CREATE INDEX IF NOT EXISTS sms_send_attempt_stale ON sms_send_attempt(state, created_at)");
  }

  public static final class PendingEndSession {
    public final String attemptId;
    public final long messageId;
    public final int subscriptionId;
    public final String snapshot;

    PendingEndSession(String attemptId, long messageId, int subscriptionId, String snapshot) {
      this.attemptId = attemptId;
      this.messageId = messageId;
      this.subscriptionId = subscriptionId;
      this.snapshot = snapshot;
    }
  }

  public static final class PendingEffect {
    public final String attemptId;
    public final long messageId;
    public final int attemptNumber;
    public final SmsSendAttempt.Decision decision;

    PendingEffect(String attemptId, long messageId, int attemptNumber, SmsSendAttempt.Decision decision) {
      this.attemptId = attemptId;
      this.messageId = messageId;
      this.attemptNumber = attemptNumber;
      this.decision = decision;
    }
  }

  public static final class PendingDelivery {
    public final String attemptId;
    public final long messageId;
    public final String claimToken;

    PendingDelivery(String attemptId, long messageId, String claimToken) {
      this.attemptId = attemptId;
      this.messageId = messageId;
      this.claimToken = claimToken;
    }
  }

  public static final class Transition {
    public final SmsSendAttempt.Decision decision;
    public final boolean changed;
    public final boolean delivered;
    public final long messageId;
    public final int attemptNumber;

    Transition(SmsSendAttempt.Decision decision, boolean changed, long messageId, int attemptNumber) {
      this(decision, changed, false, messageId, attemptNumber);
    }

    Transition(SmsSendAttempt.Decision decision, boolean changed, boolean delivered, long messageId, int attemptNumber) {
      this.decision = decision;
      this.changed = changed;
      this.delivered = delivered;
      this.messageId = messageId;
      this.attemptNumber = attemptNumber;
    }
  }

  private SQLiteOpenHelper helper;

  SmsSendAttemptDatabase(SQLiteOpenHelper helper) {
    this.helper = helper;
  }

  void reset(SQLiteOpenHelper helper) {
    this.helper = helper;
  }

  public boolean matchesAttempt(String attemptId, long messageId, int attemptNumber) {
    if (attemptId == null || attemptNumber < 1 || messageId < 0) return false;
    try (Cursor cursor = helper.getReadableDatabase().rawQuery(
        "SELECT 1 FROM sms_send_attempt WHERE attempt_id = ? AND message_id = ? " +
        "AND attempt_number = ? LIMIT 1", new String[]{attemptId, Long.toString(messageId),
            Integer.toString(attemptNumber)})) {
      return cursor.moveToFirst();
    }
  }

  public boolean hasAttemptForMessage(long messageId) {
    try (Cursor cursor = helper.getReadableDatabase().rawQuery(
        "SELECT 1 FROM sms_send_attempt WHERE message_id = ? LIMIT 1",
        new String[]{Long.toString(messageId)})) {
      return cursor.moveToFirst();
    }
  }

  public boolean requiresManualResendWarning(long messageId) {
    try (Cursor cursor = helper.getReadableDatabase().rawQuery(
        "SELECT state FROM sms_send_attempt WHERE message_id = ? " +
        "ORDER BY attempt_number DESC LIMIT 1", new String[]{Long.toString(messageId)})) {
      if (cursor.moveToFirst()) {
        String state = cursor.getString(0);
        return SmsSendAttempt.Decision.PARTIAL_FAILURE.name().equals(state) ||
            SmsSendAttempt.Decision.UNKNOWN.name().equals(state);
      }
    }
    try (Cursor cursor = helper.getReadableDatabase().rawQuery(
        "SELECT type FROM sms WHERE _id = ?", new String[]{Long.toString(messageId)})) {
      return cursor.moveToFirst() &&
          (cursor.getLong(0) & MmsSmsColumns.Types.BASE_TYPE_MASK) ==
              MmsSmsColumns.Types.BASE_SENT_FAILED_TYPE;
    }
  }

  public int[] preparedAttempt(String attemptId, long messageId) {
    if (attemptId == null) return null;
    try (Cursor cursor = helper.getReadableDatabase().rawQuery(
        "SELECT attempt_number, part_count FROM sms_send_attempt WHERE attempt_id = ? " +
        "AND message_id = ? AND state = 'PREPARED'", new String[]{attemptId, Long.toString(messageId)})) {
      return cursor.moveToFirst() ? new int[]{cursor.getInt(0), cursor.getInt(1)} : null;
    }
  }

  public String createAttempt(long messageId, int partCount, int subscriptionId, long now) {
    if (partCount <= 0) throw new IllegalArgumentException("partCount must be positive");
    SQLiteDatabase db = helper.getWritableDatabase();
    db.beginTransaction();
    try {
      try (Cursor cursor = db.rawQuery("SELECT 1 FROM sms_send_attempt WHERE message_id = ? LIMIT 1",
                                        new String[]{Long.toString(messageId)})) {
        if (cursor.moveToFirst()) throw new IllegalStateException("Prior SMS attempt exists");
      }
      String id = insertAttempt(db, messageId, 1, partCount, subscriptionId, now);
      db.setTransactionSuccessful();
      return id;
    } finally {
      db.endTransaction();
    }
  }

  public boolean stageEndSessionSnapshot(String attemptId, String snapshot) {
    if (snapshot == null || snapshot.isEmpty()) throw new IllegalArgumentException("Missing session snapshot");
    SQLiteDatabase db = helper.getWritableDatabase();
    ContentValues values = new ContentValues();
    values.put("end_session_snapshot", snapshot);
    return db.update("sms_send_attempt", values, "attempt_id = ? AND state = 'PREPARED' " +
        "AND end_session_snapshot IS NULL AND EXISTS (SELECT 1 FROM sms WHERE sms._id = message_id " +
        "AND (sms.type & " + MmsSmsColumns.Types.END_SESSION_BIT + ") != 0)",
        new String[]{attemptId}) == 1;
  }

  public boolean stageSecureIntent(String attemptId, boolean secure) {
    ContentValues values = new ContentValues();
    values.put("secure_intent", secure ? 1 : 0);
    return helper.getWritableDatabase().update("sms_send_attempt", values,
        "attempt_id = ? AND state = 'PREPARED' AND secure_intent IS NULL",
        new String[]{attemptId}) == 1;
  }

  public boolean scheduleRetry(String attemptId, long dueAt) {
    SQLiteDatabase db = helper.getWritableDatabase();
    ContentValues values = new ContentValues();
    values.put("retry_due_at", dueAt);
    values.put("effect_pending", 0);
    return db.update("sms_send_attempt", values,
        "attempt_id = ? AND state = 'RETRYABLE_FAILURE' AND effect_pending = 1 " +
        "AND retry_due_at IS NULL AND completed_at <= ? AND attempt_number < ? " +
        "AND NOT EXISTS (SELECT 1 FROM sms_send_part WHERE attempt_id = ? AND sent_result = -1)",
        new String[]{attemptId, Long.toString(dueAt),
            Integer.toString(SmsSendRetryPolicy.MAX_ATTEMPTS), attemptId}) == 1;
  }

  public int scheduleUnscheduledRetries(long dueAt, int limit) {
    if (limit <= 0) throw new IllegalArgumentException("limit must be positive");
    List<String> ids = new ArrayList<>();
    try (Cursor cursor = helper.getReadableDatabase().rawQuery(
        "SELECT attempt_id FROM sms_send_attempt WHERE state = 'RETRYABLE_FAILURE' " +
        "AND effect_pending = 1 AND retry_due_at IS NULL ORDER BY completed_at, attempt_id LIMIT " + limit,
        null)) {
      while (cursor.moveToNext()) ids.add(cursor.getString(0));
    }
    int scheduled = 0;
    for (String id : ids) {
      if (scheduleRetry(id, dueAt)) scheduled++;
    }
    return scheduled;
  }

  public List<String> getDueRetries(long now, int limit) {
    if (limit <= 0) throw new IllegalArgumentException("limit must be positive");
    List<String> ids = new ArrayList<>();
    try (Cursor cursor = helper.getReadableDatabase().rawQuery(
        "SELECT attempt_id FROM sms_send_attempt WHERE state = 'RETRYABLE_FAILURE' " +
        "AND effect_pending = 0 AND retry_due_at <= ? ORDER BY retry_due_at, attempt_id LIMIT " + limit,
        new String[]{Long.toString(now)})) {
      while (cursor.moveToNext()) ids.add(cursor.getString(0));
    }
    return ids;
  }

  public long retryDueAt(String attemptId) {
    try (Cursor cursor = helper.getReadableDatabase().rawQuery(
        "SELECT retry_due_at FROM sms_send_attempt WHERE attempt_id = ? " +
        "AND state = 'RETRYABLE_FAILURE' AND effect_pending = 0",
        new String[]{attemptId})) {
      return cursor.moveToFirst() && !cursor.isNull(0) ? cursor.getLong(0) : -1;
    }
  }

  public long messageIdForAttempt(String attemptId) {
    try (Cursor cursor = helper.getReadableDatabase().rawQuery(
        "SELECT message_id FROM sms_send_attempt WHERE attempt_id = ?", new String[]{attemptId})) {
      return cursor.moveToFirst() ? cursor.getLong(0) : -1;
    }
  }

  public long timeoutDueAt(String attemptId, long timeoutMillis) {
    if (timeoutMillis <= 0) throw new IllegalArgumentException("timeoutMillis must be positive");
    try (Cursor cursor = helper.getReadableDatabase().rawQuery(
        "SELECT created_at FROM sms_send_attempt WHERE attempt_id = ? " +
        "AND state IN ('PREPARED', 'SUBMITTED')", new String[]{attemptId})) {
      return cursor.moveToFirst() ? cursor.getLong(0) + timeoutMillis : -1;
    }
  }

  public List<String> getScheduledRetries(int limit) {
    return getScheduledRetriesAfter(null, limit);
  }

  public List<String> getScheduledRetriesAfter(String afterId, int limit) {
    if (limit <= 0) throw new IllegalArgumentException("limit must be positive");
    List<String> ids = new ArrayList<>();
    try (Cursor cursor = helper.getReadableDatabase().rawQuery(
        "SELECT attempt_id FROM sms_send_attempt WHERE state = 'RETRYABLE_FAILURE' " +
        "AND effect_pending = 0 AND retry_due_at IS NOT NULL AND attempt_id > ? " +
        "ORDER BY attempt_id LIMIT " + limit, new String[]{afterId == null ? "" : afterId})) {
      while (cursor.moveToNext()) ids.add(cursor.getString(0));
    }
    return ids;
  }

  public String claimRetry(String attemptId, long now) {
    SQLiteDatabase db = helper.getWritableDatabase();
    db.beginTransaction();
    try (Cursor cursor = db.rawQuery(
        "SELECT message_id, attempt_number, part_count, subscription_id, state, effect_pending, retry_due_at " +
        "FROM sms_send_attempt WHERE attempt_id = ?", new String[]{attemptId})) {
      if (!cursor.moveToFirst() || !"RETRYABLE_FAILURE".equals(cursor.getString(4)) ||
          cursor.getInt(5) != 0 || cursor.isNull(6) || cursor.getLong(6) > now ||
          cursor.getInt(1) >= SmsSendRetryPolicy.MAX_ATTEMPTS) return null;
      long messageId = cursor.getLong(0);
      int number = cursor.getInt(1) + 1;
      try (Cursor latest = db.rawQuery(
          "SELECT attempt_id FROM sms_send_attempt WHERE message_id = ? ORDER BY attempt_number DESC LIMIT 1",
          new String[]{Long.toString(messageId)})) {
        if (!latest.moveToFirst() || !attemptId.equals(latest.getString(0))) return null;
      }
      String id = insertAttempt(db, messageId, number, cursor.getInt(2), cursor.getInt(3), now);
      ContentValues values = new ContentValues();
      values.put("state", "SUPERSEDED");
      values.putNull("retry_due_at");
      if (db.update("sms_send_attempt", values, "attempt_id = ? AND state = 'RETRYABLE_FAILURE'",
          new String[]{attemptId}) != 1) throw new IllegalStateException("Retry claim changed");
      db.setTransactionSuccessful();
      return id;
    } finally {
      db.endTransaction();
    }
  }

  private static String insertAttempt(SQLiteDatabase db, long messageId, int number,
                                      int partCount, int subscriptionId, long now) {
      String id = UUID.randomUUID().toString();
      ContentValues values = new ContentValues();
      values.put("attempt_id", id);
      values.put("message_id", messageId);
      values.put("attempt_number", number);
      values.put("part_count", partCount);
      values.put("subscription_id", subscriptionId);
      values.put("state", "PREPARED");
      values.put("created_at", now);
      db.insertOrThrow("sms_send_attempt", null, values);
      for (int index = 0; index < partCount; index++) {
        ContentValues part = new ContentValues();
        part.put("attempt_id", id);
        part.put("part_index", index);
        db.insertOrThrow("sms_send_part", null, part);
      }
      return id;
  }

  public boolean markSubmitted(String attemptId, long now) {
    SQLiteDatabase db = helper.getWritableDatabase();
    ContentValues values = new ContentValues();
    values.put("state", "SUBMITTED");
    values.put("submitted_at", now);
    return db.update("sms_send_attempt", values, "attempt_id = ? AND state = 'PREPARED'",
      new String[]{attemptId}) == 1;
  }

  public Transition markTimedOut(String attemptId, long deadline) {
    SQLiteDatabase db = helper.getWritableDatabase();
    db.beginTransaction();
    try (Cursor cursor = db.rawQuery("SELECT message_id, attempt_number, state, created_at FROM sms_send_attempt WHERE attempt_id = ?",
                                     new String[]{attemptId})) {
      if (!cursor.moveToFirst()) throw new IllegalArgumentException("Unknown attempt");
      long messageId = cursor.getLong(0);
      int number = cursor.getInt(1);
      String state = cursor.getString(2);
      if ((!state.equals("PREPARED") && !state.equals("SUBMITTED")) || cursor.getLong(3) > deadline) {
        return new Transition(decisionFor(state), false, messageId, number);
      }
      ContentValues values = new ContentValues();
      values.put("state", SmsSendAttempt.Decision.UNKNOWN.name());
      values.put("completed_at", deadline);
      values.put("effect_pending", 1);
      db.update("sms_send_attempt", values, "attempt_id = ?", new String[]{attemptId});
      db.setTransactionSuccessful();
      return new Transition(SmsSendAttempt.Decision.UNKNOWN, true, messageId, number);
    } finally {
      db.endTransaction();
    }
  }

  public int reconcileStaleAttempts(long createdBefore, long now, int limit) {
    if (limit <= 0) throw new IllegalArgumentException("limit must be positive");
    SQLiteDatabase db = helper.getWritableDatabase();
    db.beginTransaction();
    try {
      List<String> staleIds = new ArrayList<>();
      try (Cursor cursor = db.rawQuery(
          "SELECT attempt_id FROM sms_send_attempt WHERE state IN ('PREPARED', 'SUBMITTED') " +
          "AND created_at <= ? ORDER BY created_at, attempt_id LIMIT " + limit,
          new String[]{Long.toString(createdBefore)})) {
        while (cursor.moveToNext()) staleIds.add(cursor.getString(0));
      }
      for (String attemptId : staleIds) markTimedOut(attemptId, now);
      db.setTransactionSuccessful();
      return staleIds.size();
    } finally {
      db.endTransaction();
    }
  }

  public Transition recordSentResult(String attemptId, int index, int count, int rawResult, long now) {
    return recordSentResult(attemptId, index, count, rawResult, now, null);
  }

  public Transition recordSentResultAndScheduleRetry(String attemptId, int index, int count,
                                                     int rawResult, long now, long retryDueAt) {
    if (retryDueAt < now) throw new IllegalArgumentException("Retry cannot precede callback");
    return recordSentResult(attemptId, index, count, rawResult, now, retryDueAt);
  }

  private Transition recordSentResult(String attemptId, int index, int count, int rawResult,
                                      long now, Long retryDueAt) {
    SQLiteDatabase db = helper.getWritableDatabase();
    db.beginTransaction();
    try (Cursor attempt = db.rawQuery("SELECT message_id, attempt_number, part_count, state FROM sms_send_attempt WHERE attempt_id = ?",
                                      new String[]{attemptId})) {
      if (!attempt.moveToFirst() || count != attempt.getInt(2) || index < 0 || index >= count) {
        throw new IllegalArgumentException("Unknown attempt or invalid part metadata");
      }
      long messageId = attempt.getLong(0);
      int number = attempt.getInt(1);
      String state = attempt.getString(3);
      if (!state.equals("PREPARED") && !state.equals("SUBMITTED")) {
        try (Cursor part = db.rawQuery("SELECT sent_result FROM sms_send_part WHERE attempt_id = ? AND part_index = ?",
                                       new String[]{attemptId, Integer.toString(index)})) {
          boolean found = part.moveToFirst();
          if (found && !part.isNull(0) && part.getInt(0) != rawResult) {
            throw new IllegalStateException("Conflicting SMS callback");
          }
          if (found && part.isNull(0)) {
            ContentValues lateResult = new ContentValues();
            lateResult.put("sent_result", rawResult);
            lateResult.put("sent_at", now);
            db.update("sms_send_part", lateResult, "attempt_id = ? AND part_index = ?",
                new String[]{attemptId, Integer.toString(index)});
          }
        }
        db.setTransactionSuccessful();
        return new Transition(decisionFor(state), false, messageId, number);
      }

      try (Cursor part = db.rawQuery("SELECT sent_result FROM sms_send_part WHERE attempt_id = ? AND part_index = ?",
                                     new String[]{attemptId, Integer.toString(index)})) {
        if (!part.moveToFirst()) throw new IllegalStateException("Missing part");
        if (!part.isNull(0)) {
          if (part.getInt(0) != rawResult) throw new IllegalStateException("Conflicting SMS callback");
          return new Transition(decisionFor(state), false, messageId, number);
        }
      }
      ContentValues result = new ContentValues();
      result.put("sent_result", rawResult);
      result.put("sent_at", now);
      db.update("sms_send_part", result, "attempt_id = ? AND part_index = ?",
                new String[]{attemptId, Integer.toString(index)});

      SmsSendAttempt aggregate = new SmsSendAttempt(count);
      try (Cursor parts = db.rawQuery("SELECT part_index, sent_result FROM sms_send_part WHERE attempt_id = ? ORDER BY part_index",
                                      new String[]{attemptId})) {
        while (parts.moveToNext()) {
          if (!parts.isNull(1)) aggregate = aggregate.recordResult(parts.getInt(0),
              SmsSendResultPolicy.classify(parts.getInt(1)));
        }
      }
      SmsSendAttempt.Decision decision = aggregate.decision();
      if (decision == SmsSendAttempt.Decision.RETRYABLE_FAILURE &&
          number >= SmsSendRetryPolicy.MAX_ATTEMPTS) {
        decision = SmsSendAttempt.Decision.TERMINAL_FAILURE;
      }
      boolean changed = decision != SmsSendAttempt.Decision.PENDING;
      if (changed) {
        ContentValues terminal = new ContentValues();
        terminal.put("state", decision.name());
        terminal.put("completed_at", now);
        if (decision == SmsSendAttempt.Decision.RETRYABLE_FAILURE && retryDueAt != null) {
          terminal.put("retry_due_at", retryDueAt);
          terminal.put("effect_pending", 0);
        } else {
          terminal.put("effect_pending", 1);
        }
        db.update("sms_send_attempt", terminal, "attempt_id = ?", new String[]{attemptId});
      }
      boolean delivered = changed && decision == SmsSendAttempt.Decision.SUCCEEDED &&
          completeDelivery(db, attemptId, count, now);
      db.setTransactionSuccessful();
      return new Transition(decision, changed, delivered, messageId, number);
    } finally {
      db.endTransaction();
    }
  }

  Transition recordSentResultWithMessageState(String attemptId, int index, int count,
                                              int rawResult, long now, boolean secure) {
    SQLiteDatabase db = helper.getWritableDatabase();
    db.beginTransaction();
    try {
      ContentValues intent = new ContentValues();
      intent.put("secure_intent", secure ? 1 : 0);
      if (db.update("sms_send_attempt", intent,
          "attempt_id = ? AND (secure_intent IS NULL OR secure_intent = ?)",
          new String[]{attemptId, secure ? "1" : "0"}) != 1) {
        throw new IllegalStateException("Conflicting or missing SMS secure intent");
      }
      Transition transition = recordSentResult(attemptId, index, count, rawResult, now);
      if (transition.changed && transition.decision != SmsSendAttempt.Decision.RETRYABLE_FAILURE) {
        applyPendingMessageState(attemptId, transition.decision, secure);
      }
      db.setTransactionSuccessful();
      return transition;
    } finally {
      db.endTransaction();
    }
  }

  public boolean recordDeliveryResult(String attemptId, int index, int count, int status, long now) {
    SQLiteDatabase db = helper.getWritableDatabase();
    db.beginTransaction();
    try (Cursor attempt = db.rawQuery("SELECT part_count, state, delivery_completed_at FROM sms_send_attempt WHERE attempt_id = ?",
                                      new String[]{attemptId})) {
      if (!attempt.moveToFirst() || attempt.getInt(0) != count || index < 0 || index >= count) {
        throw new IllegalArgumentException("Unknown attempt or invalid delivery part metadata");
      }
      String state = attempt.getString(1);
      if ((!state.equals("PREPARED") && !state.equals("SUBMITTED") &&
           !state.equals(SmsSendAttempt.Decision.SUCCEEDED.name())) || !attempt.isNull(2)) {
        return false;
      }
      try (Cursor part = db.rawQuery("SELECT delivered_result FROM sms_send_part WHERE attempt_id = ? AND part_index = ?",
                                     new String[]{attemptId, Integer.toString(index)})) {
        if (!part.moveToFirst()) throw new IllegalStateException("Missing delivery part");
        if (!part.isNull(0)) {
          int previous = part.getInt(0);
          if (previous == status) return false;
          if ((previous & 0x60) != 0x20) throw new IllegalStateException("Conflicting final delivery receipt");
        }
      }
      ContentValues receipt = new ContentValues();
      receipt.put("delivered_result", status);
      receipt.put("delivered_at", now);
      db.update("sms_send_part", receipt, "attempt_id = ? AND part_index = ?",
                new String[]{attemptId, Integer.toString(index)});

      boolean delivered = state.equals(SmsSendAttempt.Decision.SUCCEEDED.name()) &&
          completeDelivery(db, attemptId, count, now);
      db.setTransactionSuccessful();
      return delivered;
    } finally {
      db.endTransaction();
    }
  }

  private static boolean completeDelivery(SQLiteDatabase db, String attemptId, int count, long now) {
    int received = 0;
    try (Cursor parts = db.rawQuery("SELECT delivered_result FROM sms_send_part WHERE attempt_id = ?",
                                    new String[]{attemptId})) {
      while (parts.moveToNext()) {
        received++;
        if (parts.isNull(0) || (parts.getInt(0) & 0x60) != 0) return false;
      }
    }
    if (received != count) return false;
    ContentValues complete = new ContentValues();
    complete.put("delivery_completed_at", now);
    complete.put("delivery_effect_pending", 1);
    return db.update("sms_send_attempt", complete,
        "attempt_id = ? AND delivery_completed_at IS NULL", new String[]{attemptId}) == 1;
  }

  public int deleteCompletedAttemptsOlderThan(long cutoff) {
    SQLiteDatabase db = helper.getWritableDatabase();
    db.beginTransaction();
    try {
      db.execSQL("DELETE FROM sms_send_part WHERE attempt_id IN (SELECT attempt_id FROM sms_send_attempt " +
           "WHERE completed_at < ? AND effect_pending = 0 AND delivery_effect_pending = 0 " +
           "AND retry_due_at IS NULL " +
           "AND state NOT IN ('PREPARED', 'SUBMITTED'))", new Object[]{cutoff});
      int removed = db.delete("sms_send_attempt",
          "completed_at < ? AND effect_pending = 0 AND delivery_effect_pending = 0 " +
          "AND retry_due_at IS NULL " +
          "AND state NOT IN ('PREPARED', 'SUBMITTED')",
          new String[]{Long.toString(cutoff)});
      db.setTransactionSuccessful();
      return removed;
    } finally {
      db.endTransaction();
    }
  }

  public List<PendingEffect> getPendingEffects(int limit) {
    if (limit <= 0) throw new IllegalArgumentException("limit must be positive");
    List<PendingEffect> effects = new ArrayList<>();
    try (Cursor cursor = helper.getReadableDatabase().rawQuery(
        "SELECT attempt_id, message_id, attempt_number, state FROM sms_send_attempt " +
        "WHERE effect_pending = 1 ORDER BY completed_at, attempt_id LIMIT " + limit, null)) {
      while (cursor.moveToNext()) {
        effects.add(new PendingEffect(cursor.getString(0), cursor.getLong(1), cursor.getInt(2),
            SmsSendAttempt.Decision.valueOf(cursor.getString(3))));
      }
    }
    return effects;
  }

  public List<Long> getPendingMessageStateIds(int limit) {
    if (limit <= 0) throw new IllegalArgumentException("limit must be positive");
    List<Long> ids = new ArrayList<>();
    try (Cursor cursor = helper.getReadableDatabase().rawQuery(
        "SELECT a.message_id FROM sms_send_attempt a JOIN sms ON sms._id = a.message_id " +
        "WHERE a.effect_pending = 1 " +
        "AND a.state IN ('SUCCEEDED', 'PARTIAL_FAILURE', 'TERMINAL_FAILURE', 'UNKNOWN') " +
        "AND ((a.state = 'SUCCEEDED' AND (sms.type & " + MmsSmsColumns.Types.BASE_TYPE_MASK +
        ") != " + MmsSmsColumns.Types.BASE_SENT_TYPE + ") OR " +
        "(a.state = 'SUCCEEDED' AND a.secure_intent = 1 AND " +
        "(sms.type & " + MmsSmsColumns.Types.SECURE_MESSAGE_BIT + ") = 0) OR " +
        "(a.state != 'SUCCEEDED' AND (sms.type & " + MmsSmsColumns.Types.BASE_TYPE_MASK +
        ") != " + MmsSmsColumns.Types.BASE_SENT_FAILED_TYPE + ")) " +
        "ORDER BY a.completed_at, a.attempt_id LIMIT " + limit, null)) {
      while (cursor.moveToNext()) ids.add(cursor.getLong(0));
    }
    return ids;
  }

  public List<PendingEffect> getPendingFailureNotifications(int limit) {
    if (limit <= 0) throw new IllegalArgumentException("limit must be positive");
    List<PendingEffect> effects = new ArrayList<>();
    try (Cursor cursor = helper.getReadableDatabase().rawQuery(
      "SELECT a.attempt_id, a.message_id, a.attempt_number, a.state FROM sms_send_attempt a " +
      "JOIN sms ON sms._id = a.message_id WHERE a.effect_pending = 1 " +
      "AND a.state IN ('PARTIAL_FAILURE', 'TERMINAL_FAILURE', 'UNKNOWN') " +
      "AND (sms.type & " + MmsSmsColumns.Types.BASE_TYPE_MASK + ") = " +
      MmsSmsColumns.Types.BASE_SENT_FAILED_TYPE +
      " ORDER BY a.completed_at, a.attempt_id LIMIT " + limit, null)) {
      while (cursor.moveToNext()) {
        effects.add(new PendingEffect(cursor.getString(0), cursor.getLong(1), cursor.getInt(2),
            SmsSendAttempt.Decision.valueOf(cursor.getString(3))));
      }
    }
    return effects;
  }

  public List<PendingEffect> getPendingOrdinarySuccesses(int limit) {
    if (limit <= 0) throw new IllegalArgumentException("limit must be positive");
    List<PendingEffect> effects = new ArrayList<>();
    try (Cursor cursor = helper.getReadableDatabase().rawQuery(
        "SELECT a.attempt_id, a.message_id, a.attempt_number, a.state " +
        "FROM sms_send_attempt a JOIN sms ON sms._id = a.message_id " +
        "WHERE a.state = 'SUCCEEDED' AND a.effect_pending = 1 " +
        "AND (sms.type & " + MmsSmsColumns.Types.END_SESSION_BIT + ") = 0 " +
        "AND (sms.type & " + MmsSmsColumns.Types.BASE_TYPE_MASK + ") = " +
        MmsSmsColumns.Types.BASE_SENT_TYPE + " AND (a.secure_intent IS NULL OR a.secure_intent = 0 " +
        "OR (sms.type & " + MmsSmsColumns.Types.SECURE_MESSAGE_BIT + ") != 0) " +
        "ORDER BY a.completed_at, a.attempt_id LIMIT " + limit, null)) {
      while (cursor.moveToNext()) {
        effects.add(new PendingEffect(cursor.getString(0), cursor.getLong(1), cursor.getInt(2),
            SmsSendAttempt.Decision.SUCCEEDED));
      }
    }
    return effects;
  }

  public List<Long> getPendingDeliveredMessages(int limit) {
    return getPendingDeliveredMessagesAfter(-1, limit);
  }

  public List<Long> getPendingDeliveredMessagesAfter(long afterMessageId, int limit) {
    if (limit <= 0) throw new IllegalArgumentException("limit must be positive");
    List<Long> ids = new ArrayList<>();
    try (Cursor cursor = helper.getReadableDatabase().rawQuery(
        "SELECT DISTINCT a.message_id FROM sms_send_attempt a JOIN sms ON sms._id = a.message_id " +
        "WHERE a.state = 'SUCCEEDED' AND a.delivery_effect_pending = 1 " +
        "AND (sms.type & " + MmsSmsColumns.Types.BASE_TYPE_MASK + ") = " +
        MmsSmsColumns.Types.BASE_SENT_TYPE + " AND a.message_id > ? " +
        "ORDER BY a.message_id LIMIT " + limit, new String[]{Long.toString(afterMessageId)})) {
      while (cursor.moveToNext()) ids.add(cursor.getLong(0));
    }
    return ids;
  }

  public PendingDelivery claimPendingDelivery(long now, long claimExpiresAt) {
    if (claimExpiresAt <= now) throw new IllegalArgumentException("claim must expire after now");
    SQLiteDatabase db = helper.getWritableDatabase();
    db.beginTransaction();
    try {
      String attemptId;
      long messageId;
      try (Cursor cursor = db.rawQuery(
          "SELECT a.attempt_id, a.message_id FROM sms_send_attempt a JOIN sms ON sms._id = a.message_id " +
          "WHERE a.state = 'SUCCEEDED' AND a.delivery_effect_pending = 1 " +
          "AND (a.delivery_effect_claim_token IS NULL OR a.delivery_effect_claim_expires_at IS NULL " +
          "OR a.delivery_effect_claim_expires_at <= ?) " +
          "AND (sms.type & " + MmsSmsColumns.Types.BASE_TYPE_MASK + ") = " +
          MmsSmsColumns.Types.BASE_SENT_TYPE + " ORDER BY a.delivery_completed_at, a.attempt_id LIMIT 1",
          new String[]{Long.toString(now)})) {
        if (!cursor.moveToFirst()) return null;
        attemptId = cursor.getString(0);
        messageId = cursor.getLong(1);
      }
      String token = UUID.randomUUID().toString();
      ContentValues claim = new ContentValues();
      claim.put("delivery_effect_claim_token", token);
      claim.put("delivery_effect_claim_expires_at", claimExpiresAt);
      if (db.update("sms_send_attempt", claim,
          "attempt_id = ? AND delivery_effect_pending = 1 AND " +
          "(delivery_effect_claim_token IS NULL OR delivery_effect_claim_expires_at IS NULL " +
          "OR delivery_effect_claim_expires_at <= ?)",
          new String[]{attemptId, Long.toString(now)}) != 1) {
        return null;
      }
      db.setTransactionSuccessful();
      return new PendingDelivery(attemptId, messageId, token);
    } finally {
      db.endTransaction();
    }
  }

  public boolean acknowledgeDelivery(PendingDelivery delivery) {
    ContentValues values = new ContentValues();
    values.put("delivery_effect_pending", 0);
    values.putNull("delivery_effect_claim_token");
    values.putNull("delivery_effect_claim_expires_at");
    return helper.getWritableDatabase().update("sms_send_attempt", values,
        "attempt_id = ? AND delivery_effect_pending = 1 AND delivery_effect_claim_token = ?",
        new String[]{delivery.attemptId, delivery.claimToken}) == 1;
  }

  public boolean releaseDelivery(PendingDelivery delivery) {
    ContentValues values = new ContentValues();
    values.putNull("delivery_effect_claim_token");
    values.putNull("delivery_effect_claim_expires_at");
    return helper.getWritableDatabase().update("sms_send_attempt", values,
        "attempt_id = ? AND delivery_effect_pending = 1 AND delivery_effect_claim_token = ?",
        new String[]{delivery.attemptId, delivery.claimToken}) == 1;
  }

  public List<PendingEndSession> getPendingEndSessions(int limit) {
    return getPendingEndSessionsAfter(null, limit);
  }

  public List<PendingEndSession> getPendingEndSessionsAfter(String afterId, int limit) {
    if (limit <= 0) throw new IllegalArgumentException("limit must be positive");
    List<PendingEndSession> effects = new ArrayList<>();
    try (Cursor cursor = helper.getReadableDatabase().rawQuery(
        "SELECT a.attempt_id, a.message_id, a.subscription_id, a.end_session_snapshot " +
        "FROM sms_send_attempt a JOIN sms ON sms._id = a.message_id " +
        "WHERE a.state = 'SUCCEEDED' AND a.effect_pending = 1 " +
        "AND a.end_session_snapshot IS NOT NULL AND (sms.type & " +
        MmsSmsColumns.Types.END_SESSION_BIT + ") != 0 AND (sms.type & " +
        MmsSmsColumns.Types.BASE_TYPE_MASK + ") = " + MmsSmsColumns.Types.BASE_SENT_TYPE +
        " AND (a.secure_intent IS NULL OR a.secure_intent = 0 OR (sms.type & " +
        MmsSmsColumns.Types.SECURE_MESSAGE_BIT + ") != 0) " +
        "AND a.attempt_id > ? ORDER BY a.attempt_id LIMIT " + limit,
        new String[]{afterId == null ? "" : afterId})) {
      while (cursor.moveToNext()) {
        effects.add(new PendingEndSession(cursor.getString(0), cursor.getLong(1), cursor.getInt(2),
            cursor.getString(3)));
      }
    }
    return effects;
  }

  public boolean acknowledgeEndSession(String attemptId, String snapshot) {
    if (snapshot == null) return false;
    ContentValues values = new ContentValues();
    values.put("effect_pending", 0);
    return helper.getWritableDatabase().update("sms_send_attempt", values,
        "attempt_id = ? AND end_session_snapshot = ? AND state = 'SUCCEEDED' " +
        "AND effect_pending = 1 AND EXISTS (SELECT 1 FROM sms WHERE sms._id = message_id " +
        "AND (sms.type & " + MmsSmsColumns.Types.END_SESSION_BIT + ") != 0 " +
        "AND (sms.type & " + MmsSmsColumns.Types.BASE_TYPE_MASK + ") = " +
        MmsSmsColumns.Types.BASE_SENT_TYPE + " AND (secure_intent IS NULL OR secure_intent = 0 " +
        "OR (sms.type & " + MmsSmsColumns.Types.SECURE_MESSAGE_BIT + ") != 0))",
        new String[]{attemptId, snapshot}) == 1;
  }

  public int replayPendingMessageStates(int limit) {
    if (limit <= 0) throw new IllegalArgumentException("limit must be positive");
    SQLiteDatabase db = helper.getWritableDatabase();
    List<PendingEffect> effects = new ArrayList<>();
    try (Cursor cursor = db.rawQuery(
      "SELECT a.attempt_id, a.message_id, a.attempt_number, a.state FROM sms_send_attempt a " +
      "JOIN sms ON sms._id = a.message_id WHERE a.effect_pending = 1 " +
      "AND a.state IN ('SUCCEEDED', 'PARTIAL_FAILURE', 'TERMINAL_FAILURE', 'UNKNOWN') " +
      "AND ((a.state = 'SUCCEEDED' AND (sms.type & " + MmsSmsColumns.Types.BASE_TYPE_MASK +
      ") != " + MmsSmsColumns.Types.BASE_SENT_TYPE + ") OR " +
      "(a.state = 'SUCCEEDED' AND a.secure_intent = 1 AND " +
      "(sms.type & " + MmsSmsColumns.Types.SECURE_MESSAGE_BIT + ") = 0) OR " +
      "(a.state != 'SUCCEEDED' AND (sms.type & " + MmsSmsColumns.Types.BASE_TYPE_MASK +
      ") != " + MmsSmsColumns.Types.BASE_SENT_FAILED_TYPE + ")) " +
      "ORDER BY a.completed_at, a.attempt_id LIMIT " + limit, null)) {
      while (cursor.moveToNext()) {
        effects.add(new PendingEffect(cursor.getString(0), cursor.getLong(1), cursor.getInt(2),
            SmsSendAttempt.Decision.valueOf(cursor.getString(3))));
      }
    }
    int updated = 0;
    for (PendingEffect effect : effects) {
      try (Cursor message = db.rawQuery("SELECT sms.type, a.secure_intent FROM sms " +
          "JOIN sms_send_attempt a ON a.message_id = sms._id WHERE sms._id = ? AND a.attempt_id = ?",
          new String[]{Long.toString(effect.messageId), effect.attemptId})) {
        if (!message.moveToFirst() || message.isNull(0)) continue;
        if (applyPendingMessageState(effect.attemptId, effect.decision,
            (!message.isNull(1) && message.getInt(1) == 1) ||
            (message.getLong(0) & MmsSmsColumns.Types.SECURE_MESSAGE_BIT) != 0)) updated++;
      }
    }
    return updated;
  }

  public boolean applyPendingMessageState(String attemptId, SmsSendAttempt.Decision expectedDecision,
                                          boolean secure) {
    if (expectedDecision == SmsSendAttempt.Decision.PENDING ||
        expectedDecision == SmsSendAttempt.Decision.RETRYABLE_FAILURE) {
      throw new IllegalArgumentException("Decision cannot be applied to SMS state");
    }
    SQLiteDatabase db = helper.getWritableDatabase();
    db.beginTransaction();
    try (Cursor attempt = db.rawQuery(
        "SELECT message_id, state, effect_pending FROM sms_send_attempt WHERE attempt_id = ?",
        new String[]{attemptId})) {
      if (!attempt.moveToFirst() || !expectedDecision.name().equals(attempt.getString(1)) ||
          attempt.getInt(2) != 1) {
        throw new IllegalStateException("No matching pending terminal effect");
      }
      long messageId = attempt.getLong(0);
      long target = expectedDecision == SmsSendAttempt.Decision.SUCCEEDED
          ? MmsSmsColumns.Types.BASE_SENT_TYPE : MmsSmsColumns.Types.BASE_SENT_FAILED_TYPE;
      try (Cursor message = db.rawQuery("SELECT type FROM sms WHERE _id = ?",
                                        new String[]{Long.toString(messageId)})) {
        if (!message.moveToFirst() || message.isNull(0)) {
          throw new IllegalStateException("Missing SMS for pending terminal effect");
        }
        long current = message.getLong(0);
        long currentBase = current & MmsSmsColumns.Types.BASE_TYPE_MASK;
        long desired = (current & ~MmsSmsColumns.Types.BASE_TYPE_MASK) | target;
        if (expectedDecision == SmsSendAttempt.Decision.SUCCEEDED && secure) {
          desired |= MmsSmsColumns.Types.SECURE_MESSAGE_BIT;
        }
        if (currentBase == target && current == desired) {
          db.setTransactionSuccessful();
          return false;
        }
        if (currentBase != target && currentBase != MmsSmsColumns.Types.BASE_OUTBOX_TYPE &&
            currentBase != MmsSmsColumns.Types.BASE_SENDING_TYPE) {
          throw new IllegalStateException("SMS state changed since attempt was created");
        }
        ContentValues values = new ContentValues();
        values.put("type", desired);
        int updated = db.update("sms", values,
            "_id = ? AND type = ?", new String[]{Long.toString(messageId), Long.toString(current)});
        if (updated != 1) throw new IllegalStateException("SMS state changed during effect application");
      }
      db.setTransactionSuccessful();
      return true;
    } finally {
      db.endTransaction();
    }
  }

  public boolean acknowledgeEffect(String attemptId, SmsSendAttempt.Decision expectedDecision) {
    if (expectedDecision != SmsSendAttempt.Decision.SUCCEEDED) return false;
    SQLiteDatabase db = helper.getWritableDatabase();
    db.beginTransaction();
    try {
      try (Cursor cursor = db.rawQuery("SELECT sms.type, a.secure_intent FROM sms_send_attempt a " +
          "JOIN sms ON sms._id = a.message_id WHERE a.attempt_id = ? AND a.state = ? " +
          "AND a.effect_pending = 1", new String[]{attemptId, expectedDecision.name()})) {
        if (!cursor.moveToFirst()) return false;
        long type = cursor.getLong(0);
        if ((type & MmsSmsColumns.Types.END_SESSION_BIT) != 0) return false;
        long target = expectedDecision == SmsSendAttempt.Decision.SUCCEEDED
            ? MmsSmsColumns.Types.BASE_SENT_TYPE : MmsSmsColumns.Types.BASE_SENT_FAILED_TYPE;
        if ((type & MmsSmsColumns.Types.BASE_TYPE_MASK) != target ||
            (!cursor.isNull(1) && cursor.getInt(1) == 1 &&
             (type & MmsSmsColumns.Types.SECURE_MESSAGE_BIT) == 0)) return false;
      }
      ContentValues values = new ContentValues();
      values.put("effect_pending", 0);
      boolean acknowledged = db.update("sms_send_attempt", values,
          "attempt_id = ? AND state = ? AND effect_pending = 1",
          new String[]{attemptId, expectedDecision.name()}) == 1;
      db.setTransactionSuccessful();
      return acknowledged;
    } finally {
      db.endTransaction();
    }
  }

  public boolean acknowledgeFailureNotification(String attemptId) {
    SQLiteDatabase db = helper.getWritableDatabase();
    db.beginTransaction();
    try {
      try (Cursor cursor = db.rawQuery("SELECT sms.type FROM sms_send_attempt a " +
          "JOIN sms ON sms._id = a.message_id WHERE a.attempt_id = ? " +
          "AND a.state IN ('PARTIAL_FAILURE', 'TERMINAL_FAILURE', 'UNKNOWN') " +
          "AND a.effect_pending = 1", new String[]{attemptId})) {
        if (!cursor.moveToFirst() ||
            (cursor.getLong(0) & MmsSmsColumns.Types.BASE_TYPE_MASK) !=
                MmsSmsColumns.Types.BASE_SENT_FAILED_TYPE) return false;
      }
      ContentValues values = new ContentValues();
      values.put("effect_pending", 0);
      boolean acknowledged = db.update("sms_send_attempt", values,
          "attempt_id = ? AND effect_pending = 1 AND state IN " +
          "('PARTIAL_FAILURE', 'TERMINAL_FAILURE', 'UNKNOWN')", new String[]{attemptId}) == 1;
      db.setTransactionSuccessful();
      return acknowledged;
    } finally {
      db.endTransaction();
    }
  }

  private static SmsSendAttempt.Decision decisionFor(String state) {
    try {
      return SmsSendAttempt.Decision.valueOf(state);
    } catch (IllegalArgumentException ignored) {
      return SmsSendAttempt.Decision.PENDING;
    }
  }
}