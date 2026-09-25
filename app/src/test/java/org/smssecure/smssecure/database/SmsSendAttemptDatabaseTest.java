package org.smssecure.smssecure.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.telephony.SmsManager;

import androidx.test.core.app.ApplicationProvider;

import org.smssecure.smssecure.jobs.sms.SmsSendAttempt;
import org.smssecure.smssecure.crypto.storage.VendoredSessionStore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class SmsSendAttemptDatabaseTest {
  private SQLiteOpenHelper helper(Context context, String name) {
    return new SQLiteOpenHelper(context, name, null, 32) {
      @Override public void onCreate(SQLiteDatabase db) {
        SmsSendAttemptDatabase.createTables(db);
        db.execSQL("CREATE TABLE sms (_id INTEGER PRIMARY KEY, type INTEGER, " +
          "date_delivery_received INTEGER DEFAULT 0)");
      }

      @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        SmsSendAttemptDatabase.createTables(db);
      }
    };
  }

  private Constructor<?> databaseHelperConstructor() throws Exception {
    Class<?> helperClass = Class.forName("org.smssecure.smssecure.database.DatabaseFactory$DatabaseHelper");
    Constructor<?> constructor = helperClass.getDeclaredConstructor(Context.class, String.class,
        SQLiteDatabase.CursorFactory.class, int.class);
    constructor.setAccessible(true);
    return constructor;
  }

  private void assertCompleteSchema(SQLiteDatabase db) {
    List<String> attemptColumns = new ArrayList<>();
    try (Cursor cursor = db.rawQuery("PRAGMA table_info(sms_send_attempt)", null)) {
      while (cursor.moveToNext()) attemptColumns.add(cursor.getString(cursor.getColumnIndexOrThrow("name")));
    }
    assertThat(attemptColumns).containsExactly("attempt_id", "message_id", "attempt_number", "part_count",
        "subscription_id", "state", "created_at", "submitted_at", "completed_at",
        "delivery_completed_at", "effect_pending", "retry_due_at", "secure_intent",
        "end_session_snapshot", "delivery_effect_pending", "delivery_effect_claim_token",
        "delivery_effect_claim_expires_at");

    List<String> partColumns = new ArrayList<>();
    try (Cursor cursor = db.rawQuery("PRAGMA table_info(sms_send_part)", null)) {
      while (cursor.moveToNext()) partColumns.add(cursor.getString(cursor.getColumnIndexOrThrow("name")));
    }
    assertThat(partColumns).containsExactly("attempt_id", "part_index", "sent_result", "sent_at",
        "delivered_result", "delivered_at");

    List<String> indexes = new ArrayList<>();
    try (Cursor cursor = db.rawQuery(
        "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'sms_send_attempt'", null)) {
      while (cursor.moveToNext()) indexes.add(cursor.getString(0));
    }
    assertThat(indexes).contains("sms_send_attempt_message", "sms_send_attempt_stale");
  }

  private String shortDatabaseName(String prefix) {
    return prefix + Long.toHexString(System.nanoTime()) + ".db";
  }

  @Test
  public void persistsPartsAcrossReopenAndTransitionsOnlyOnce() {
    Context context = ApplicationProvider.getApplicationContext();
    String name = "sms-attempt-test-" + UUID.randomUUID() + ".db";
    SQLiteOpenHelper first = helper(context, name);
    String id = new SmsSendAttemptDatabase(first).createAttempt(7, 2, 1, 100);
    SmsSendAttemptDatabase ledger = new SmsSendAttemptDatabase(first);
    ledger.markSubmitted(id, 110);
    assertThat(ledger.recordSentResult(id, 1, 2, Activity.RESULT_OK, 120).changed).isFalse();
    first.close();

    SQLiteOpenHelper second = helper(context, name);
    try {
      ledger = new SmsSendAttemptDatabase(second);
      SmsSendAttemptDatabase.Transition result = ledger.recordSentResult(id, 0, 2, Activity.RESULT_OK, 130);
      assertThat(result.changed).isTrue();
      assertThat(result.decision).isEqualTo(org.smssecure.smssecure.jobs.sms.SmsSendAttempt.Decision.SUCCEEDED);
      assertThat(ledger.recordSentResult(id, 0, 2, Activity.RESULT_OK, 140).changed).isFalse();
      assertThatThrownBy(() -> new SmsSendAttemptDatabase(second).recordSentResult(
          id, 0, 2, SmsManager.RESULT_ERROR_NO_SERVICE, 150)).isInstanceOf(IllegalStateException.class);
      try (Cursor cursor = second.getReadableDatabase().rawQuery(
          "SELECT state FROM sms_send_attempt WHERE attempt_id = ?", new String[]{id})) {
        assertThat(cursor.moveToFirst()).isTrue();
        assertThat(cursor.getString(0)).isEqualTo("SUCCEEDED");
      }
    } finally {
      second.close();
      context.deleteDatabase(name);
    }
  }

  @Test
  public void rejectsConflictsAndNeverRetriesAfterPartialSuccess() {
    Context context = ApplicationProvider.getApplicationContext();
    String name = "sms-attempt-test-" + UUID.randomUUID() + ".db";
    SQLiteOpenHelper helper = helper(context, name);
    try {
      SmsSendAttemptDatabase ledger = new SmsSendAttemptDatabase(helper);
      String id = ledger.createAttempt(8, 2, -1, 100);
      ledger.recordSentResult(id, 0, 2, Activity.RESULT_OK, 110);
      assertThatThrownBy(() -> ledger.recordSentResult(id, 0, 2, SmsManager.RESULT_ERROR_NO_SERVICE, 120))
          .isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(() -> ledger.recordSentResult(id, 2, 2, Activity.RESULT_OK, 120))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(ledger.recordSentResult(id, 1, 2, SmsManager.RESULT_ERROR_NO_SERVICE, 130).decision)
          .isEqualTo(org.smssecure.smssecure.jobs.sms.SmsSendAttempt.Decision.PARTIAL_FAILURE);
    } finally {
      helper.close();
      context.deleteDatabase(name);
    }
  }

  @Test
  public void freshDatabaseCreatesCompleteVersion32Schema() throws Exception {
    java.lang.reflect.Field versionField = DatabaseFactory.class.getDeclaredField("DATABASE_VERSION");
    versionField.setAccessible(true);
    assertThat(versionField.getInt(null)).isEqualTo(32);
    Context context = ApplicationProvider.getApplicationContext();
    String name = shortDatabaseName("s32-");
    SQLiteOpenHelper helper = (SQLiteOpenHelper) databaseHelperConstructor().newInstance(context, name, null, 32);
    try {
      SQLiteDatabase db = helper.getWritableDatabase();
      assertThat(db.getVersion()).isEqualTo(32);
      assertCompleteSchema(db);
      SmsSendAttemptDatabase.createTables(db);
      SmsSendAttemptDatabase.createTables(db);
      db.execSQL("INSERT INTO sms_send_attempt " +
          "(attempt_id, message_id, attempt_number, part_count, subscription_id, state, created_at) " +
          "VALUES ('fresh-a', 1, 1, 1, -1, 'PREPARED', 100)");
      assertThatThrownBy(() -> db.execSQL("INSERT INTO sms_send_attempt " +
          "(attempt_id, message_id, attempt_number, part_count, subscription_id, state, created_at) " +
          "VALUES ('fresh-b', 1, 1, 1, -1, 'PREPARED', 101)"))
          .isInstanceOf(android.database.SQLException.class);
      db.execSQL("INSERT INTO sms_send_part (attempt_id, part_index) VALUES ('fresh-a', 0)");
      assertThatThrownBy(() -> db.execSQL(
          "INSERT INTO sms_send_part (attempt_id, part_index) VALUES ('fresh-a', 0)"))
          .isInstanceOf(android.database.SQLException.class);
    } finally {
      helper.close();
      context.deleteDatabase(name);
    }
  }

  @Test
  public void rejectsOverlappingSendsAndLimitsRetryLineage() {
    Context context = ApplicationProvider.getApplicationContext();
    String name = "sms-attempt-test-" + UUID.randomUUID() + ".db";
    SQLiteOpenHelper helper = helper(context, name);
    try {
      SmsSendAttemptDatabase ledger = new SmsSendAttemptDatabase(helper);
      String first = ledger.createAttempt(8, 1, -1, 100);
      assertThatThrownBy(() -> ledger.createAttempt(8, 1, -1, 101)).isInstanceOf(IllegalStateException.class);
      ledger.recordSentResult(first, 0, 1, SmsManager.RESULT_ERROR_NO_SERVICE, 110);
      assertThatThrownBy(() -> ledger.createAttempt(8, 1, -1, 115)).isInstanceOf(IllegalStateException.class);
      assertThat(ledger.scheduleRetry(first, 120)).isTrue();
      assertThat(ledger.claimRetry(first, 119)).isNull();
      String second = ledger.claimRetry(first, 120);
      assertThat(second).isNotNull();
      assertThat(ledger.claimRetry(first, 121)).isNull();
      assertThat(ledger.recordSentResult(first, 0, 1, SmsManager.RESULT_ERROR_NO_SERVICE, 130).changed).isFalse();
      ledger.recordSentResult(second, 0, 1, SmsManager.RESULT_ERROR_RADIO_OFF, 140);
      assertThat(ledger.scheduleRetry(second, 150)).isTrue();
      String third = ledger.claimRetry(second, 150);
      assertThat(third).isNotNull();
        assertThat(ledger.recordSentResult(third, 0, 1, SmsManager.RESULT_ERROR_NO_SERVICE, 160).decision)
          .isEqualTo(SmsSendAttempt.Decision.TERMINAL_FAILURE);
        assertThat(ledger.scheduleRetry(third, 200)).isFalse();
      assertThatThrownBy(() -> ledger.createAttempt(8, 1, -1, 170)).isInstanceOf(IllegalStateException.class);
    } finally {
      helper.close();
      context.deleteDatabase(name);
    }
  }

  @Test
  public void scheduledRetrySurvivesReopenAndOnlyClaimsOnceAtDueTime() {
    Context context = ApplicationProvider.getApplicationContext();
    String name = "sms-retry-" + UUID.randomUUID() + ".db";
    SQLiteOpenHelper first = helper(context, name);
    SmsSendAttemptDatabase ledger = new SmsSendAttemptDatabase(first);
    String id = ledger.createAttempt(83, 1, -1, 100);
    ledger.recordSentResult(id, 0, 1, SmsManager.RESULT_ERROR_RADIO_OFF, 110);
    assertThat(ledger.acknowledgeEffect(id, SmsSendAttempt.Decision.RETRYABLE_FAILURE)).isFalse();
    assertThat(ledger.scheduleRetry(id, 109)).isFalse();
    assertThat(ledger.scheduleRetry(id, 200)).isTrue();
    assertThat(ledger.scheduleRetry(id, 200)).isFalse();
    assertThat(ledger.getDueRetries(199, 1)).isEmpty();
    assertThat(ledger.deleteCompletedAttemptsOlderThan(500)).isZero();
    first.close();

    SQLiteOpenHelper reopened = helper(context, name);
    try {
      ledger = new SmsSendAttemptDatabase(reopened);
      assertThat(ledger.getDueRetries(200, 1)).containsExactly(id);
      assertThat(ledger.claimRetry(id, 199)).isNull();
      String next = ledger.claimRetry(id, 200);
      assertThat(next).isNotNull();
      assertThat(ledger.claimRetry(id, 201)).isNull();
      assertThat(ledger.getDueRetries(300, 1)).isEmpty();
      assertThat(ledger.deleteCompletedAttemptsOlderThan(500)).isEqualTo(1);
      assertThatThrownBy(() -> new SmsSendAttemptDatabase(reopened).createAttempt(83, 1, -1, 201))
          .isInstanceOf(IllegalStateException.class);
      assertThat(ledger.markTimedOut(next, 250).changed).isTrue();
      assertThat(ledger.claimRetry(id, 300)).isNull();
    } finally {
      reopened.close();
      context.deleteDatabase(name);
    }
  }

  @Test
  public void finalCallbackCommitsRetryDeadlineAtomically() {
    Context context = ApplicationProvider.getApplicationContext();
    String name = "sms-atomic-retry-" + UUID.randomUUID() + ".db";
    SQLiteOpenHelper helper = helper(context, name);
    try {
      SmsSendAttemptDatabase ledger = new SmsSendAttemptDatabase(helper);
      String id = ledger.createAttempt(86, 2, -1, 100);
      assertThat(ledger.recordSentResultAndScheduleRetry(id, 0, 2,
          SmsManager.RESULT_ERROR_NO_SERVICE, 110, 200).decision)
          .isEqualTo(SmsSendAttempt.Decision.PENDING);
      assertThat(ledger.retryDueAt(id)).isEqualTo(-1);

      assertThat(ledger.recordSentResultAndScheduleRetry(id, 1, 2,
          SmsManager.RESULT_ERROR_RADIO_OFF, 120, 210).decision)
          .isEqualTo(SmsSendAttempt.Decision.RETRYABLE_FAILURE);
      assertThat(ledger.retryDueAt(id)).isEqualTo(210);
      assertThat(ledger.getScheduledRetries(1)).containsExactly(id);
    } finally {
      helper.close();
      context.deleteDatabase(name);
    }
  }

  @Test
  public void recoverySchedulesLegacyUnscheduledRetryableAttempt() {
    Context context = ApplicationProvider.getApplicationContext();
    String name = "sms-unscheduled-retry-" + UUID.randomUUID() + ".db";
    SQLiteOpenHelper helper = helper(context, name);
    try {
      SmsSendAttemptDatabase ledger = new SmsSendAttemptDatabase(helper);
      String id = ledger.createAttempt(87, 1, -1, 100);
      ledger.recordSentResult(id, 0, 1, SmsManager.RESULT_ERROR_NO_SERVICE, 110);
      assertThat(ledger.retryDueAt(id)).isEqualTo(-1);

      assertThat(ledger.scheduleUnscheduledRetries(200, 32)).isEqualTo(1);
      assertThat(ledger.scheduleUnscheduledRetries(201, 32)).isZero();
      assertThat(ledger.retryDueAt(id)).isEqualTo(200);
    } finally {
      helper.close();
      context.deleteDatabase(name);
    }
  }

  @Test
  public void secureIntentCanOnlyBeStagedBeforeSubmission() {
    Context context = ApplicationProvider.getApplicationContext();
    String name = "sms-secure-intent-stage-" + UUID.randomUUID() + ".db";
    SQLiteOpenHelper helper = helper(context, name);
    try {
      SmsSendAttemptDatabase ledger = new SmsSendAttemptDatabase(helper);
      String id = ledger.createAttempt(88, 1, -1, 100);
      assertThat(ledger.stageSecureIntent(id, true)).isTrue();
      assertThat(ledger.stageSecureIntent(id, true)).isFalse();
      assertThat(ledger.markSubmitted(id, 110)).isTrue();
      assertThat(ledger.stageSecureIntent(id, false)).isFalse();
    } finally {
      helper.close();
      context.deleteDatabase(name);
    }
  }

  @Test
  public void submissionFenceAndClaimedRetryIdentitySurviveRestart() {
    Context context = ApplicationProvider.getApplicationContext();
    String name = "sms-submit-fence-" + UUID.randomUUID() + ".db";
    SQLiteOpenHelper first = helper(context, name);
    SmsSendAttemptDatabase ledger = new SmsSendAttemptDatabase(first);
    String original = ledger.createAttempt(84, 2, 3, 100);
    assertThat(ledger.markSubmitted(original, 101)).isTrue();
    assertThat(ledger.markSubmitted(original, 102)).isFalse();
    assertThat(ledger.preparedAttempt(original, 84)).isNull();
    ledger.recordSentResult(original, 0, 2, SmsManager.RESULT_ERROR_NO_SERVICE, 105);
    ledger.recordSentResult(original, 1, 2, SmsManager.RESULT_ERROR_RADIO_OFF, 106);
    assertThat(ledger.scheduleRetry(original, 200)).isTrue();
    assertThat(ledger.retryDueAt(original)).isEqualTo(200);
    assertThat(ledger.getScheduledRetries(1)).containsExactly(original);
    first.close();

    SQLiteOpenHelper reopened = helper(context, name);
    try {
      SmsSendAttemptDatabase restored = new SmsSendAttemptDatabase(reopened);
      assertThat(restored.claimRetry(original, 199)).isNull();
      String next = restored.claimRetry(original, 200);
      assertThat(next).isNotNull();
      assertThat(restored.matchesAttempt(next, 84, 2)).isTrue();
      assertThat(restored.matchesAttempt(next, 85, 2)).isFalse();
      assertThat(restored.matchesAttempt(next, 84, 1)).isFalse();
      assertThat(restored.preparedAttempt(next, 85)).isNull();
      assertThat(restored.preparedAttempt(next, 84)).containsExactly(2, 2);
      assertThat(restored.markSubmitted(next, 201)).isTrue();
      assertThat(restored.markSubmitted(next, 202)).isFalse();
      assertThat(restored.preparedAttempt(next, 84)).isNull();
      assertThat(restored.claimRetry(original, 210)).isNull();
    } finally {
      reopened.close();
      context.deleteDatabase(name);
    }
  }

  @Test
  public void manualResendWarnsOnlyForPartialOrUnknownLatestAttempt() {
    Context context = ApplicationProvider.getApplicationContext();
    String name = "sms-resend-warning-" + UUID.randomUUID() + ".db";
    SQLiteOpenHelper helper = helper(context, name);
    try {
      SmsSendAttemptDatabase ledger = new SmsSendAttemptDatabase(helper);
      String partial = ledger.createAttempt(31, 2, -1, 100);
      ledger.recordSentResult(partial, 0, 2, Activity.RESULT_OK, 110);
      ledger.recordSentResult(partial, 1, 2, SmsManager.RESULT_ERROR_NO_SERVICE, 120);
      assertThat(ledger.requiresManualResendWarning(31)).isTrue();

      String unknown = ledger.createAttempt(32, 1, -1, 100);
      ledger.markTimedOut(unknown, 120);
      assertThat(ledger.requiresManualResendWarning(32)).isTrue();

      String terminal = ledger.createAttempt(33, 1, -1, 100);
      ledger.recordSentResult(terminal, 0, 1, SmsManager.RESULT_ERROR_NULL_PDU, 120);
      assertThat(ledger.requiresManualResendWarning(33)).isFalse();
      assertThat(ledger.requiresManualResendWarning(999)).isFalse();
        helper.getWritableDatabase().execSQL("INSERT INTO sms (_id, type) VALUES (?, ?)",
          new Object[]{36, MmsSmsColumns.Types.BASE_SENT_FAILED_TYPE});
        assertThat(ledger.requiresManualResendWarning(36)).isTrue();
    } finally {
      helper.close();
      context.deleteDatabase(name);
    }
  }

  @Test
  public void timeoutDeadlineExistsOnlyWhileAttemptIsInFlight() {
    Context context = ApplicationProvider.getApplicationContext();
    String name = "sms-timeout-deadline-" + UUID.randomUUID() + ".db";
    SQLiteOpenHelper helper = helper(context, name);
    try {
      SmsSendAttemptDatabase ledger = new SmsSendAttemptDatabase(helper);
      String id = ledger.createAttempt(34, 1, -1, 100);
      assertThat(ledger.timeoutDueAt(id, 50)).isEqualTo(150);
      assertThat(ledger.markSubmitted(id, 110)).isTrue();
      assertThat(ledger.timeoutDueAt(id, 50)).isEqualTo(150);
      ledger.recordSentResult(id, 0, 1, Activity.RESULT_OK, 120);
      assertThat(ledger.timeoutDueAt(id, 50)).isEqualTo(-1);
    } finally {
      helper.close();
      context.deleteDatabase(name);
    }
  }

  @Test
  public void lateCallbackIsRecordedWithoutChangingUnknownDecision() {
    Context context = ApplicationProvider.getApplicationContext();
    String name = "sms-late-evidence-" + UUID.randomUUID() + ".db";
    SQLiteOpenHelper helper = helper(context, name);
    try {
      SmsSendAttemptDatabase ledger = new SmsSendAttemptDatabase(helper);
      String id = ledger.createAttempt(35, 1, -1, 100);
      assertThat(ledger.markTimedOut(id, 150).decision).isEqualTo(SmsSendAttempt.Decision.UNKNOWN);

      SmsSendAttemptDatabase.Transition transition =
          ledger.recordSentResult(id, 0, 1, Activity.RESULT_OK, 160);

      assertThat(transition.decision).isEqualTo(SmsSendAttempt.Decision.UNKNOWN);
      assertThat(transition.changed).isFalse();
      try (Cursor cursor = helper.getReadableDatabase().rawQuery(
          "SELECT sent_result FROM sms_send_part WHERE attempt_id = ? AND part_index = 0",
          new String[]{id})) {
        assertThat(cursor.moveToFirst()).isTrue();
        assertThat(cursor.getInt(0)).isEqualTo(Activity.RESULT_OK);
      }
      assertThat(ledger.claimRetry(id, 170)).isNull();
    } finally {
      helper.close();
      context.deleteDatabase(name);
    }
  }

  @Test
  public void timeoutBecomesUnknownAndCannotBeReusedForAutomaticRetry() {
    Context context = ApplicationProvider.getApplicationContext();
    String name = "sms-attempt-test-" + UUID.randomUUID() + ".db";
    SQLiteOpenHelper helper = helper(context, name);
    try {
      SmsSendAttemptDatabase ledger = new SmsSendAttemptDatabase(helper);
      String id = ledger.createAttempt(10, 2, -1, 100);
      assertThat(ledger.markTimedOut(id, 99).changed).isFalse();
      assertThat(ledger.markTimedOut(id, 110).changed).isTrue();
      assertThat(ledger.getPendingEffects(1).get(0).decision).isEqualTo(SmsSendAttempt.Decision.UNKNOWN);
      assertThat(ledger.recordSentResult(id, 0, 2, Activity.RESULT_OK, 120).changed).isFalse();
      assertThatThrownBy(() -> ledger.createAttempt(10, 2, -1, 130)).isInstanceOf(IllegalStateException.class);
    } finally {
      helper.close();
      context.deleteDatabase(name);
    }
  }

  @Test
  public void reconcilesOldInflightAttemptsInBoundedBatchesAcrossReopen() {
    Context context = ApplicationProvider.getApplicationContext();
    String name = "sms-reconcile-" + UUID.randomUUID() + ".db";
    SQLiteOpenHelper first = helper(context, name);
    SmsSendAttemptDatabase ledger = new SmsSendAttemptDatabase(first);
    String prepared = ledger.createAttempt(80, 1, -1, 100);
    String submitted = ledger.createAttempt(81, 1, -1, 110);
    ledger.markSubmitted(submitted, 115);
    String recent = ledger.createAttempt(82, 1, -1, 130);
    assertThat(ledger.reconcileStaleAttempts(120, 200, 1)).isEqualTo(1);
    assertThat(ledger.getPendingEffects(5)).hasSize(1);
    first.close();

    SQLiteOpenHelper reopened = helper(context, name);
    try {
      ledger = new SmsSendAttemptDatabase(reopened);
      assertThat(ledger.reconcileStaleAttempts(120, 210, 1)).isEqualTo(1);
      assertThat(ledger.reconcileStaleAttempts(120, 220, 5)).isZero();
      assertThat(ledger.getPendingEffects(5)).hasSize(2);
      assertThat(ledger.getPendingEffects(5)).allMatch(effect ->
          effect.decision == SmsSendAttempt.Decision.UNKNOWN);
      assertThat(ledger.recordSentResult(prepared, 0, 1, Activity.RESULT_OK, 230).changed).isFalse();
      assertThat(ledger.recordSentResult(submitted, 0, 1, Activity.RESULT_OK, 230).changed).isFalse();
      assertThat(ledger.recordSentResult(recent, 0, 1, Activity.RESULT_OK, 230).changed).isTrue();
      assertThat(ledger.getPendingEffects(5)).hasSize(3);
      assertThatThrownBy(() -> new SmsSendAttemptDatabase(reopened).reconcileStaleAttempts(120, 220, 0))
          .isInstanceOf(IllegalArgumentException.class);
    } finally {
      reopened.close();
      context.deleteDatabase(name);
    }
  }

  @Test
  public void populatedVersion30UpgradesToCompleteVersion32Schema() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    String name = shortDatabaseName("u30-");
    Constructor<?> constructor = databaseHelperConstructor();
    SQLiteOpenHelper first = (SQLiteOpenHelper) constructor.newInstance(context, name, null, 32);
    SQLiteDatabase oldDatabase = first.getWritableDatabase();
    oldDatabase.execSQL("INSERT INTO sms (_id, type, body) VALUES (42, 23, 'retained')");
    oldDatabase.execSQL("DROP TABLE sms_send_part");
    oldDatabase.execSQL("DROP TABLE sms_send_attempt");
    oldDatabase.setVersion(30);
    first.close();

    SQLiteOpenHelper upgraded = (SQLiteOpenHelper) constructor.newInstance(context, name, null, 32);
    try {
      SQLiteDatabase db = upgraded.getWritableDatabase();
      assertThat(db.getVersion()).isEqualTo(32);
      assertCompleteSchema(db);
      try (Cursor cursor = db.rawQuery("SELECT type, body FROM sms WHERE _id = 42", null)) {
        assertThat(cursor.moveToFirst()).isTrue();
        assertThat(cursor.getLong(0)).isEqualTo(23);
        assertThat(cursor.getString(1)).isEqualTo("retained");
      }
      SmsSendAttemptDatabase ledger = new SmsSendAttemptDatabase(upgraded);
      String attemptId = ledger.createAttempt(42, 2, -1, 100);
      try (Cursor cursor = db.rawQuery(
          "SELECT part_index FROM sms_send_part WHERE attempt_id = ? ORDER BY part_index",
          new String[]{attemptId})) {
        assertThat(cursor.moveToNext()).isTrue();
        assertThat(cursor.getInt(0)).isZero();
        assertThat(cursor.moveToNext()).isTrue();
        assertThat(cursor.getInt(0)).isEqualTo(1);
        assertThat(cursor.moveToNext()).isFalse();
      }
      assertThat(ledger.getPendingEffects(1)).isEmpty();
    } finally {
      upgraded.close();
      context.deleteDatabase(name);
    }
  }

  @Test
  public void resetUsesTheReplacementHelper() {
    Context context = ApplicationProvider.getApplicationContext();
    String firstName = "sms-attempt-test-" + UUID.randomUUID() + ".db";
    String secondName = "sms-attempt-test-" + UUID.randomUUID() + ".db";
    SQLiteOpenHelper first = helper(context, firstName);
    SQLiteOpenHelper second = helper(context, secondName);
    try {
      SmsSendAttemptDatabase ledger = new SmsSendAttemptDatabase(first);
      ledger.createAttempt(1, 1, -1, 100);
      first.close();
      ledger.reset(second);
      String id = ledger.createAttempt(2, 1, -1, 200);
      assertThat(ledger.recordSentResult(id, 0, 1, Activity.RESULT_OK, 210).changed).isTrue();
    } finally {
      first.close();
      second.close();
      context.deleteDatabase(firstName);
      context.deleteDatabase(secondName);
    }
  }

  @Test
  public void deliveryRequiresAllFinalPartsAndOnlyEmitsOnce() {
    Context context = ApplicationProvider.getApplicationContext();
    String name = "sms-attempt-test-" + UUID.randomUUID() + ".db";
    SQLiteOpenHelper helper = helper(context, name);
    try {
      SmsSendAttemptDatabase ledger = new SmsSendAttemptDatabase(helper);
      String id = ledger.createAttempt(20, 2, -1, 100);
      assertThat(ledger.recordDeliveryResult(id, 0, 2, 0, 105)).isFalse();
      ledger.recordSentResult(id, 0, 2, Activity.RESULT_OK, 110);
      ledger.recordSentResult(id, 1, 2, Activity.RESULT_OK, 120);
      assertThat(ledger.recordDeliveryResult(id, 0, 2, 0, 130)).isFalse();
      assertThat(ledger.recordDeliveryResult(id, 0, 2, 0, 140)).isFalse();
      assertThat(ledger.recordDeliveryResult(id, 1, 2, 0, 150)).isTrue();
      assertThat(ledger.recordDeliveryResult(id, 1, 2, 0, 160)).isFalse();
    } finally {
      helper.close();
      context.deleteDatabase(name);
    }
  }

  @Test
  public void earlyDeliveryAndTemporaryStatusCompleteAfterSentAggregation() {
    Context context = ApplicationProvider.getApplicationContext();
    String name = "sms-attempt-test-" + UUID.randomUUID() + ".db";
    SQLiteOpenHelper helper = helper(context, name);
    try {
      SmsSendAttemptDatabase ledger = new SmsSendAttemptDatabase(helper);
      String id = ledger.createAttempt(22, 2, -1, 100);
      assertThat(ledger.recordDeliveryResult(id, 0, 2, 0x20, 105)).isFalse();
      assertThat(ledger.recordDeliveryResult(id, 0, 2, 0, 110)).isFalse();
      assertThat(ledger.recordDeliveryResult(id, 1, 2, 0, 115)).isFalse();
      ledger.recordSentResult(id, 1, 2, Activity.RESULT_OK, 120);
      assertThat(ledger.recordSentResult(id, 0, 2, Activity.RESULT_OK, 125).delivered).isTrue();
      assertThat(ledger.recordDeliveryResult(id, 1, 2, 0, 130)).isFalse();
    } finally {
      helper.close();
      context.deleteDatabase(name);
    }
  }

  @Test
  public void cleanupLeavesInFlightAndRecentAttemptsIntact() {
    Context context = ApplicationProvider.getApplicationContext();
    String name = "sms-attempt-test-" + UUID.randomUUID() + ".db";
    SQLiteOpenHelper helper = helper(context, name);
    try {
      SmsSendAttemptDatabase ledger = new SmsSendAttemptDatabase(helper);
      String old = ledger.createAttempt(30, 1, -1, 100);
      helper.getWritableDatabase().execSQL("INSERT INTO sms (_id, type) VALUES (30, 21)");
      ledger.recordSentResult(old, 0, 1, Activity.RESULT_OK, 110);
      ledger.applyPendingMessageState(old, SmsSendAttempt.Decision.SUCCEEDED, false);
      assertThat(ledger.acknowledgeEffect(old,
          org.smssecure.smssecure.jobs.sms.SmsSendAttempt.Decision.SUCCEEDED)).isTrue();
      String recent = ledger.createAttempt(31, 1, -1, 200);
      ledger.recordSentResult(recent, 0, 1, Activity.RESULT_OK, 210);
      ledger.createAttempt(32, 1, -1, 90);
      assertThat(ledger.deleteCompletedAttemptsOlderThan(150)).isEqualTo(1);
      try (Cursor cursor = helper.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM sms_send_attempt", null)) {
        assertThat(cursor.moveToFirst()).isTrue();
        assertThat(cursor.getInt(0)).isEqualTo(2);
      }
    } finally {
      helper.close();
      context.deleteDatabase(name);
    }
  }

  @Test
  public void terminalEffectSurvivesReopenUntilAcknowledged() {
    Context context = ApplicationProvider.getApplicationContext();
    String name = "sms-effect-test-" + UUID.randomUUID() + ".db";
    SQLiteOpenHelper first = helper(context, name);
    String id = new SmsSendAttemptDatabase(first).createAttempt(61, 2, -1, 100);
    SmsSendAttemptDatabase ledger = new SmsSendAttemptDatabase(first);
    ledger.recordSentResult(id, 0, 2, Activity.RESULT_OK, 110);
    ledger.recordSentResult(id, 1, 2, Activity.RESULT_OK, 120);
    assertThat(ledger.recordSentResult(id, 1, 2, Activity.RESULT_OK, 130).changed).isFalse();
    first.close();

    SQLiteOpenHelper reopened = helper(context, name);
    try {
      ledger = new SmsSendAttemptDatabase(reopened);
      assertThat(ledger.getPendingEffects(1)).hasSize(1);
      assertThat(ledger.getPendingEffects(1).get(0).messageId).isEqualTo(61);
      assertThat(ledger.getPendingEffects(1).get(0).decision)
          .isEqualTo(org.smssecure.smssecure.jobs.sms.SmsSendAttempt.Decision.SUCCEEDED);
      assertThat(ledger.deleteCompletedAttemptsOlderThan(200)).isZero();
      assertThat(ledger.acknowledgeEffect(id,
          org.smssecure.smssecure.jobs.sms.SmsSendAttempt.Decision.UNKNOWN)).isFalse();
        reopened.getWritableDatabase().execSQL("INSERT INTO sms (_id, type) VALUES (61, 21)");
        assertThat(ledger.applyPendingMessageState(id, SmsSendAttempt.Decision.SUCCEEDED, false)).isTrue();
      assertThat(ledger.acknowledgeEffect(id,
          org.smssecure.smssecure.jobs.sms.SmsSendAttempt.Decision.SUCCEEDED)).isTrue();
      assertThat(ledger.acknowledgeEffect(id,
          org.smssecure.smssecure.jobs.sms.SmsSendAttempt.Decision.SUCCEEDED)).isFalse();
      assertThat(ledger.getPendingEffects(1)).isEmpty();
      assertThat(ledger.deleteCompletedAttemptsOlderThan(200)).isEqualTo(1);
    } finally {
      reopened.close();
      context.deleteDatabase(name);
    }
  }

  @Test
  public void pendingMessageStateIsIdempotentAndRejectsMissingOrStaleRows() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    String name = "sms-effect-state-" + UUID.randomUUID() + ".db";
    Class<?> helperClass = Class.forName("org.smssecure.smssecure.database.DatabaseFactory$DatabaseHelper");
    Constructor<?> constructor = helperClass.getDeclaredConstructor(Context.class, String.class,
        SQLiteDatabase.CursorFactory.class, int.class);
    constructor.setAccessible(true);
    SQLiteOpenHelper helper = (SQLiteOpenHelper) constructor.newInstance(context, name, null, 32);
    try {
      SmsSendAttemptDatabase ledger = new SmsSendAttemptDatabase(helper);
      String id = ledger.createAttempt(71, 1, -1, 100);
      ledger.recordSentResult(id, 0, 1, Activity.RESULT_OK, 110);
      assertThatThrownBy(() -> ledger.applyPendingMessageState(id, SmsSendAttempt.Decision.SUCCEEDED, true))
          .isInstanceOf(IllegalStateException.class);
      assertThat(ledger.getPendingEffects(1)).hasSize(1);
      SQLiteDatabase db = helper.getWritableDatabase();
      db.execSQL("INSERT INTO sms (_id, type, body) VALUES (71, 21, 'retained')");
      assertThat(ledger.acknowledgeEffect(id, SmsSendAttempt.Decision.SUCCEEDED)).isFalse();
      assertThat(ledger.applyPendingMessageState(id, SmsSendAttempt.Decision.SUCCEEDED, true)).isTrue();
      assertThat(ledger.applyPendingMessageState(id, SmsSendAttempt.Decision.SUCCEEDED, true)).isFalse();
      try (Cursor cursor = db.rawQuery("SELECT type FROM sms WHERE _id = 71", null)) {
        assertThat(cursor.moveToFirst()).isTrue();
        assertThat(cursor.getLong(0)).isEqualTo(23 | MmsSmsColumns.Types.SECURE_MESSAGE_BIT);
      }
      assertThat(ledger.getPendingEffects(1)).hasSize(1);
      assertThatThrownBy(() -> ledger.applyPendingMessageState(id, SmsSendAttempt.Decision.UNKNOWN, true))
          .isInstanceOf(IllegalStateException.class);
      assertThat(ledger.acknowledgeEffect(id, SmsSendAttempt.Decision.SUCCEEDED)).isTrue();
      assertThatThrownBy(() -> ledger.applyPendingMessageState(id, SmsSendAttempt.Decision.SUCCEEDED, true))
          .isInstanceOf(IllegalStateException.class);

      String stale = ledger.createAttempt(72, 1, -1, 100);
      db.execSQL("INSERT INTO sms (_id, type, body) VALUES (72, 20, 'unchanged')");
        ledger.recordSentResult(stale, 0, 1, SmsManager.RESULT_ERROR_NULL_PDU, 110);
      assertThatThrownBy(() -> ledger.applyPendingMessageState(stale,
          SmsSendAttempt.Decision.TERMINAL_FAILURE, false)).isInstanceOf(IllegalStateException.class);
      try (Cursor cursor = db.rawQuery("SELECT type FROM sms WHERE _id = 72", null)) {
        assertThat(cursor.moveToFirst()).isTrue();
        assertThat(cursor.getLong(0)).isEqualTo(20);
      }
    } finally {
      helper.close();
      context.deleteDatabase(name);
    }
  }

  @Test
  public void terminalEffectsCannotBeAckedBeforeExternalOwnersRun() {
    Context context = ApplicationProvider.getApplicationContext();
    String name = "effect-guard-" + UUID.randomUUID() + ".db";
    SQLiteOpenHelper helper = helper(context, name);
    try {
      SmsSendAttemptDatabase ledger = new SmsSendAttemptDatabase(helper);
      SQLiteDatabase db = helper.getWritableDatabase();
      db.execSQL("INSERT INTO sms (_id, type) VALUES (81, ?)",
          new Object[]{21 | MmsSmsColumns.Types.END_SESSION_BIT});
      String endSession = ledger.createAttempt(81, 1, -1, 100);
      ledger.recordSentResult(endSession, 0, 1, Activity.RESULT_OK, 110);
      ledger.applyPendingMessageState(endSession, SmsSendAttempt.Decision.SUCCEEDED, false);
      assertThat(ledger.acknowledgeEffect(endSession, SmsSendAttempt.Decision.SUCCEEDED)).isFalse();

      db.execSQL("INSERT INTO sms (_id, type) VALUES (82, 21)");
      String failed = ledger.createAttempt(82, 1, -1, 100);
      ledger.recordSentResult(failed, 0, 1, SmsManager.RESULT_ERROR_NULL_PDU, 110);
      ledger.applyPendingMessageState(failed, SmsSendAttempt.Decision.TERMINAL_FAILURE, false);
      assertThat(ledger.acknowledgeEffect(failed, SmsSendAttempt.Decision.TERMINAL_FAILURE)).isFalse();
      assertThat(ledger.getPendingEffects(2)).hasSize(2);
    } finally {
      helper.close();
      context.deleteDatabase(name);
    }
  }

  @Test
  public void failedNotificationReplayProgressesPastOtherPendingEffects() {
    Context context = ApplicationProvider.getApplicationContext();
    String name = "failure-replay-" + UUID.randomUUID() + ".db";
    SQLiteOpenHelper helper = helper(context, name);
    try {
      SmsSendAttemptDatabase ledger = new SmsSendAttemptDatabase(helper);
      SQLiteDatabase db = helper.getWritableDatabase();
      db.execSQL("INSERT INTO sms (_id, type) VALUES (83, 21), (84, 21), (85, 21)");
      String success = ledger.createAttempt(83, 1, -1, 100);
      ledger.recordSentResult(success, 0, 1, Activity.RESULT_OK, 110);
      ledger.applyPendingMessageState(success, SmsSendAttempt.Decision.SUCCEEDED, false);
      String failure = ledger.createAttempt(84, 1, -1, 100);
      ledger.recordSentResult(failure, 0, 1, SmsManager.RESULT_ERROR_NULL_PDU, 120);
      String later = ledger.createAttempt(85, 1, -1, 100);
      ledger.recordSentResult(later, 0, 1, SmsManager.RESULT_ERROR_NULL_PDU, 130);
        assertThat(ledger.getPendingFailureNotifications(1)).isEmpty();
      assertThat(ledger.acknowledgeFailureNotification(failure)).isFalse();
      ledger.applyPendingMessageState(failure, SmsSendAttempt.Decision.TERMINAL_FAILURE, false);
        assertThat(ledger.getPendingFailureNotifications(1)).extracting(effect -> effect.attemptId)
          .containsExactly(failure);
          ledger.applyPendingMessageState(later, SmsSendAttempt.Decision.TERMINAL_FAILURE, false);
      assertThat(ledger.acknowledgeFailureNotification(failure)).isTrue();
      assertThat(ledger.acknowledgeFailureNotification(failure)).isFalse();
      assertThat(ledger.getPendingFailureNotifications(1)).extracting(effect -> effect.attemptId)
          .containsExactly(later);
    } finally {
      helper.close();
      context.deleteDatabase(name);
    }
  }

  @Test
  public void failureNotificationReplaysAfterPostError() {
    Context context = ApplicationProvider.getApplicationContext();
    String name = "notify-replay-" + UUID.randomUUID() + ".db";
    SQLiteOpenHelper first = helper(context, name);
    SmsSendAttemptDatabase ledger = new SmsSendAttemptDatabase(first);
    first.getWritableDatabase().execSQL("INSERT INTO sms (_id, type) VALUES (86, 21)");
    String id = ledger.createAttempt(86, 1, -1, 100);
    ledger.recordSentResult(id, 0, 1, SmsManager.RESULT_ERROR_NULL_PDU, 110);
    ledger.applyPendingMessageState(id, SmsSendAttempt.Decision.TERMINAL_FAILURE, false);
    assertThatThrownBy(() -> SmsFailureNotificationReplay.replay(ledger, 1, messageId -> {
      throw new IllegalStateException("notification post failed");
    })).isInstanceOf(IllegalStateException.class);
    first.close();

    SQLiteOpenHelper reopened = helper(context, name);
    try {
      SmsSendAttemptDatabase restored = new SmsSendAttemptDatabase(reopened);
      assertThat(restored.getPendingFailureNotifications(1)).hasSize(1);
      assertThat(SmsFailureNotificationReplay.replay(restored, 1, messageId -> messageId == 86))
          .isEqualTo(1);
      assertThat(SmsFailureNotificationReplay.replay(restored, 1, messageId -> {
        throw new AssertionError("already acknowledged");
      })).isZero();
    } finally {
      reopened.close();
      context.deleteDatabase(name);
    }
  }

  @Test
  public void endSessionSnapshotIsDurableAndRequiredBeforeReplay() {
    Context context = ApplicationProvider.getApplicationContext();
    String name = "end-snapshot-" + UUID.randomUUID() + ".db";
    SQLiteOpenHelper first = helper(context, name);
    SmsSendAttemptDatabase ledger = new SmsSendAttemptDatabase(first);
    first.getWritableDatabase().execSQL("INSERT INTO sms (_id, type) VALUES (87, ?)",
        new Object[]{21 | MmsSmsColumns.Types.END_SESSION_BIT});
    String id = ledger.createAttempt(87, 1, 2, 100);
    assertThat(ledger.stageEndSessionSnapshot(id, "123.2:old-session")).isTrue();
    assertThat(ledger.stageEndSessionSnapshot(id, "123.2:replacement")).isFalse();
    ledger.recordSentResult(id, 0, 1, Activity.RESULT_OK, 110);
    ledger.applyPendingMessageState(id, SmsSendAttempt.Decision.SUCCEEDED, false);
    first.close();

    SQLiteOpenHelper reopened = helper(context, name);
    try {
      SmsSendAttemptDatabase restored = new SmsSendAttemptDatabase(reopened);
      assertThat(restored.getPendingEndSessions(1)).hasSize(1);
      assertThat(restored.getPendingEndSessions(1).get(0).snapshot).isEqualTo("123.2:old-session");
      assertThat(restored.acknowledgeEffect(id, SmsSendAttempt.Decision.SUCCEEDED)).isFalse();
      assertThat(restored.acknowledgeEndSession(id, "123.2:replacement")).isFalse();
      assertThat(restored.acknowledgeEndSession(id, "123.2:old-session")).isTrue();
      assertThat(restored.acknowledgeEndSession(id, "123.2:old-session")).isFalse();
    } finally {
      reopened.close();
      context.deleteDatabase(name);
    }
  }

  @Test
  public void endSessionReplayRetainsChangedStateAndRecoversAfterDeletion() {
    Context context = ApplicationProvider.getApplicationContext();
    String name = "session-replay-" + UUID.randomUUID() + ".db";
    SQLiteOpenHelper first = helper(context, name);
    SmsSendAttemptDatabase ledger = new SmsSendAttemptDatabase(first);
    first.getWritableDatabase().execSQL("INSERT INTO sms (_id, type) VALUES (88, ?)",
        new Object[]{21 | MmsSmsColumns.Types.END_SESSION_BIT});
    String id = ledger.createAttempt(88, 1, 2, 100);
    ledger.stageEndSessionSnapshot(id, "123.2:old-session");
    ledger.recordSentResult(id, 0, 1, Activity.RESULT_OK, 110);
    ledger.applyPendingMessageState(id, SmsSendAttempt.Decision.SUCCEEDED, false);
    assertThat(SmsEndSessionReplay.replay(ledger, 1, effect -> VendoredSessionStore.DeleteOutcome.CHANGED))
        .isZero();
    assertThat(ledger.getPendingEndSessions(1)).hasSize(1);
    assertThatThrownBy(() -> SmsEndSessionReplay.replay(ledger, 1, effect -> {
      throw new IllegalStateException("process died after deletion");
    })).isInstanceOf(IllegalStateException.class);
    first.close();

    SQLiteOpenHelper reopened = helper(context, name);
    try {
      SmsSendAttemptDatabase restored = new SmsSendAttemptDatabase(reopened);
      assertThat(SmsEndSessionReplay.replay(restored, 1,
          effect -> VendoredSessionStore.DeleteOutcome.ALREADY_ABSENT)).isEqualTo(1);
      assertThat(SmsEndSessionReplay.replay(restored, 1, effect -> {
        throw new AssertionError("already acknowledged");
      })).isZero();
    } finally {
      reopened.close();
      context.deleteDatabase(name);
    }
  }

  @Test
  public void missingSnapshotAndSecureBitNeverAuthorizeEndSessionReplay() {
    Context context = ApplicationProvider.getApplicationContext();
    String name = "guard-replay-" + UUID.randomUUID() + ".db";
    SQLiteOpenHelper helper = helper(context, name);
    try {
      SmsSendAttemptDatabase ledger = new SmsSendAttemptDatabase(helper);
      SQLiteDatabase db = helper.getWritableDatabase();
      db.execSQL("INSERT INTO sms (_id, type) VALUES (89, ?), (90, ?)",
          new Object[]{21 | MmsSmsColumns.Types.END_SESSION_BIT,
              21 | MmsSmsColumns.Types.END_SESSION_BIT});
      String old = ledger.createAttempt(89, 1, 2, 100);
      ledger.recordSentResult(old, 0, 1, Activity.RESULT_OK, 110);
      ledger.applyPendingMessageState(old, SmsSendAttempt.Decision.SUCCEEDED, false);
      String secure = ledger.createAttempt(90, 1, 2, 100);
      ledger.stageEndSessionSnapshot(secure, "123.2:old");
      ledger.recordSentResultWithMessageState(secure, 0, 1, Activity.RESULT_OK, 120, true);
      db.execSQL("UPDATE sms SET type = type & ~? WHERE _id = 90",
          new Object[]{MmsSmsColumns.Types.SECURE_MESSAGE_BIT});
      assertThat(ledger.getPendingEndSessions(5)).isEmpty();
      assertThat(ledger.acknowledgeEndSession(old, "123.2:old")).isFalse();
      assertThat(ledger.acknowledgeEndSession(secure, "123.2:old")).isFalse();
      assertThat(ledger.replayPendingMessageStates(5)).isEqualTo(1);
      assertThat(ledger.getPendingEndSessions(5)).extracting(effect -> effect.attemptId)
          .containsExactly(secure);
    } finally {
      helper.close();
      context.deleteDatabase(name);
    }
  }

  @Test
  public void ordinarySuccessReplayIsNotStarvedByOtherPendingEffects() {
    Context context = ApplicationProvider.getApplicationContext();
    String name = "ordinary-replay-" + UUID.randomUUID() + ".db";
    SQLiteOpenHelper helper = helper(context, name);
    try {
      SmsSendAttemptDatabase ledger = new SmsSendAttemptDatabase(helper);
      for (int index = 0; index < 33; index++) {
        long messageId = 500 + index;
        helper.getWritableDatabase().execSQL("INSERT INTO sms (_id, type) VALUES (?, ?)",
            new Object[]{messageId, 21 | MmsSmsColumns.Types.END_SESSION_BIT});
        String attempt = ledger.createAttempt(messageId, 1, 1, index);
        ledger.stageEndSessionSnapshot(attempt, "old:" + index);
        ledger.recordSentResult(attempt, 0, 1, Activity.RESULT_OK, index + 1);
        ledger.applyPendingMessageState(attempt, SmsSendAttempt.Decision.SUCCEEDED, false);
      }
      helper.getWritableDatabase().execSQL("INSERT INTO sms (_id, type) VALUES (600, 21)");
      String success = ledger.createAttempt(600, 1, 1, 100);
      ledger.recordSentResult(success, 0, 1, Activity.RESULT_OK, 101);
      ledger.applyPendingMessageState(success, SmsSendAttempt.Decision.SUCCEEDED, false);
      assertThat(ledger.getPendingEffects(32)).hasSize(32);
      assertThat(ledger.getPendingOrdinarySuccesses(32)).extracting(effect -> effect.attemptId)
          .containsExactly(success);
      assertThat(ledger.acknowledgeEffect(success, SmsSendAttempt.Decision.SUCCEEDED)).isTrue();
      assertThat(ledger.getPendingOrdinarySuccesses(32)).isEmpty();
      assertThat(ledger.getPendingEndSessions(32)).hasSize(32);
    } finally {
      helper.close();
      context.deleteDatabase(name);
    }
  }

  @Test
  public void endSessionReplayPagesPastChangedEffect() {
    Context context = ApplicationProvider.getApplicationContext();
    String name = "end-replay-page-" + UUID.randomUUID() + ".db";
    SQLiteOpenHelper helper = helper(context, name);
    try {
      SmsSendAttemptDatabase ledger = new SmsSendAttemptDatabase(helper);
      for (int index = 0; index < 3; index++) {
        long messageId = 700 + index;
        helper.getWritableDatabase().execSQL("INSERT INTO sms (_id, type) VALUES (?, ?)",
            new Object[]{messageId, 21 | MmsSmsColumns.Types.END_SESSION_BIT});
        String id = ledger.createAttempt(messageId, 1, 1, 100 + index);
        ledger.stageEndSessionSnapshot(id, "snapshot-" + index);
        ledger.recordSentResult(id, 0, 1, Activity.RESULT_OK, 110 + index);
        ledger.applyPendingMessageState(id, SmsSendAttempt.Decision.SUCCEEDED, false);
      }
      java.util.List<SmsSendAttemptDatabase.PendingEndSession> ordered =
          ledger.getPendingEndSessionsAfter(null, 3);
      String blocked = ordered.get(0).attemptId;
      assertThat(SmsEndSessionReplay.replay(ledger, 1, effect -> effect.attemptId.equals(blocked)
          ? VendoredSessionStore.DeleteOutcome.CHANGED : VendoredSessionStore.DeleteOutcome.DELETED))
          .isEqualTo(2);
      assertThat(ledger.getPendingEndSessions(5)).extracting(effect -> effect.attemptId)
          .containsExactly(blocked);
    } finally {
      helper.close();
      context.deleteDatabase(name);
    }
  }

  @Test
  public void deliveredCheckpointReplaysAfterRestartWithoutRepeatingDelivery() {
    Context context = ApplicationProvider.getApplicationContext();
    String name = "delivery-replay-" + UUID.randomUUID() + ".db";
    SQLiteOpenHelper first = helper(context, name);
    SmsSendAttemptDatabase ledger = new SmsSendAttemptDatabase(first);
    first.getWritableDatabase().execSQL("INSERT INTO sms (_id, type) VALUES (777, 21)");
    String id = ledger.createAttempt(777, 2, 1, 100);
    ledger.recordSentResultWithMessageState(id, 0, 2, Activity.RESULT_OK, 101, false);
    ledger.recordSentResultWithMessageState(id, 1, 2, Activity.RESULT_OK, 102, false);
    assertThat(ledger.recordDeliveryResult(id, 0, 2, 0, 110)).isFalse();
    assertThat(ledger.recordDeliveryResult(id, 1, 2, 0, 111)).isTrue();
    first.close();

    SQLiteOpenHelper reopened = helper(context, name);
    try {
      SmsSendAttemptDatabase restored = new SmsSendAttemptDatabase(reopened);
      assertThat(restored.getPendingDeliveredMessages(5)).containsExactly(777L);
      java.util.concurrent.atomic.AtomicInteger deliveries = new java.util.concurrent.atomic.AtomicInteger();
      assertThat(SmsDeliveryReplay.replay(restored, 5, 1_000, messageId -> {
        deliveries.incrementAndGet();
        reopened.getWritableDatabase().execSQL(
            "UPDATE sms SET date_delivery_received = 112 WHERE _id = ?", new Object[]{messageId});
        return true;
      })).isEqualTo(1);
      assertThat(SmsDeliveryReplay.replay(restored, 5, 1_000, messageId -> {
        deliveries.incrementAndGet();
        return true;
      })).isZero();
      assertThat(deliveries.get()).isEqualTo(1);
      assertThat(restored.getPendingDeliveredMessages(5)).isEmpty();
      assertThat(restored.recordDeliveryResult(id, 1, 2, 0, 113)).isFalse();
    } finally {
      reopened.close();
      context.deleteDatabase(name);
    }
  }

  @Test
  public void deliveryReplayClaimsPreventConcurrentDuplicateEffects() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    String name = "delivery-claim-" + UUID.randomUUID() + ".db";
    SQLiteOpenHelper first = helper(context, name);
    SQLiteOpenHelper second = helper(context, name);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    CountDownLatch sinkEntered = new CountDownLatch(1);
    CountDownLatch releaseSink = new CountDownLatch(1);
    try {
      SmsSendAttemptDatabase firstLedger = deliveredAttempt(first, 780);
      SmsSendAttemptDatabase secondLedger = new SmsSendAttemptDatabase(second);
      java.util.concurrent.atomic.AtomicInteger deliveries = new java.util.concurrent.atomic.AtomicInteger();
      Future<Integer> firstReplay = executor.submit(() -> SmsDeliveryReplay.replay(
          firstLedger, 1, 1_000, messageId -> {
            sinkEntered.countDown();
            try {
              if (!releaseSink.await(5, TimeUnit.SECONDS)) return false;
            } catch (InterruptedException error) {
              Thread.currentThread().interrupt();
              return false;
            }
            deliveries.incrementAndGet();
            return true;
          }));

      assertThat(sinkEntered.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(SmsDeliveryReplay.replay(secondLedger, 1, 1_000, messageId -> {
        deliveries.incrementAndGet();
        return true;
      })).isZero();
      releaseSink.countDown();
      assertThat(firstReplay.get(5, TimeUnit.SECONDS)).isEqualTo(1);
      assertThat(deliveries.get()).isEqualTo(1);
    } finally {
      releaseSink.countDown();
      executor.shutdownNow();
      first.close();
      second.close();
      context.deleteDatabase(name);
    }
  }

  @Test
  public void deliveryReplayRecoversClaimAfterDeliveredStateBeforeToast() {
    Context context = ApplicationProvider.getApplicationContext();
    String name = "delivery-claim-recovery-" + UUID.randomUUID() + ".db";
    SQLiteOpenHelper helper = helper(context, name);
    try {
      SmsSendAttemptDatabase ledger = deliveredAttempt(helper, 781);
      assertThatThrownBy(() -> SmsDeliveryReplay.replay(ledger, 1, 1_000, messageId -> {
        helper.getWritableDatabase().execSQL(
            "UPDATE sms SET date_delivery_received = 112 WHERE _id = ?", new Object[]{messageId});
        throw new IllegalStateException("process interrupted before toast");
      })).isInstanceOf(IllegalStateException.class);

      assertThat(ledger.getPendingDeliveredMessages(1)).containsExactly(781L);
      assertThat(SmsDeliveryReplay.replay(ledger, 1,
          1_000 + SmsDeliveryReplay.CLAIM_LEASE_MILLIS - 1, messageId -> true)).isZero();
      java.util.concurrent.atomic.AtomicInteger toasts = new java.util.concurrent.atomic.AtomicInteger();
      assertThat(SmsDeliveryReplay.replay(ledger, 1,
          1_000 + SmsDeliveryReplay.CLAIM_LEASE_MILLIS, messageId -> {
            toasts.incrementAndGet();
            return true;
          })).isEqualTo(1);
      assertThat(toasts.get()).isEqualTo(1);
      assertThat(ledger.getPendingDeliveredMessages(1)).isEmpty();
    } finally {
      helper.close();
      context.deleteDatabase(name);
    }
  }

  private SmsSendAttemptDatabase deliveredAttempt(SQLiteOpenHelper helper, long messageId) {
    SmsSendAttemptDatabase ledger = new SmsSendAttemptDatabase(helper);
    helper.getWritableDatabase().execSQL("INSERT INTO sms (_id, type) VALUES (?, 21)",
        new Object[]{messageId});
    String attemptId = ledger.createAttempt(messageId, 1, 1, 100);
    ledger.recordSentResultWithMessageState(attemptId, 0, 1, Activity.RESULT_OK, 101, false);
    assertThat(ledger.recordDeliveryResult(attemptId, 0, 1, 0, 110)).isTrue();
    return ledger;
  }

  @Test
  public void deliveredReplayPagesPastAnUnchangedFirstMessage() {
    Context context = ApplicationProvider.getApplicationContext();
    String name = "delivery-pages-" + UUID.randomUUID() + ".db";
    SQLiteOpenHelper helper = helper(context, name);
    try {
      SmsSendAttemptDatabase ledger = new SmsSendAttemptDatabase(helper);
      for (long messageId : new long[]{777, 778}) {
        helper.getWritableDatabase().execSQL("INSERT INTO sms (_id, type) VALUES (?, 21)",
            new Object[]{messageId});
        String attemptId = ledger.createAttempt(messageId, 1, 1, 100);
        ledger.recordSentResultWithMessageState(attemptId, 0, 1, Activity.RESULT_OK, 101, false);
        assertThat(ledger.recordDeliveryResult(attemptId, 0, 1, 0, 110)).isTrue();
      }

      assertThat(ledger.getPendingDeliveredMessagesAfter(-1, 1)).containsExactly(777L);
      assertThat(ledger.getPendingDeliveredMessagesAfter(777, 1)).containsExactly(778L);
      assertThat(ledger.getPendingDeliveredMessagesAfter(778, 1)).isEmpty();
    } finally {
      helper.close();
      context.deleteDatabase(name);
    }
  }

  @Test
  public void lastCallbackAndMessageStateCommitOrRollBackTogether() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    String name = "sms-atomic-result-" + UUID.randomUUID() + ".db";
    Class<?> helperClass = Class.forName("org.smssecure.smssecure.database.DatabaseFactory$DatabaseHelper");
    Constructor<?> constructor = helperClass.getDeclaredConstructor(Context.class, String.class,
        SQLiteDatabase.CursorFactory.class, int.class);
    constructor.setAccessible(true);
    SQLiteOpenHelper helper = (SQLiteOpenHelper) constructor.newInstance(context, name, null, 32);
    try {
      SmsSendAttemptDatabase ledger = new SmsSendAttemptDatabase(helper);
      String id = ledger.createAttempt(73, 2, -1, 100);
      assertThat(ledger.recordSentResultWithMessageState(id, 0, 2, Activity.RESULT_OK, 110, false).changed)
          .isFalse();
      assertThatThrownBy(() -> ledger.recordSentResultWithMessageState(id, 1, 2, Activity.RESULT_OK, 120, false))
          .isInstanceOf(IllegalStateException.class);
      try (Cursor cursor = helper.getReadableDatabase().rawQuery(
          "SELECT state, effect_pending FROM sms_send_attempt WHERE attempt_id = ?", new String[]{id})) {
        assertThat(cursor.moveToFirst()).isTrue();
        assertThat(cursor.getString(0)).isEqualTo("PREPARED");
        assertThat(cursor.getInt(1)).isZero();
      }
      try (Cursor cursor = helper.getReadableDatabase().rawQuery(
          "SELECT sent_result FROM sms_send_part WHERE attempt_id = ? AND part_index = 1", new String[]{id})) {
        assertThat(cursor.moveToFirst()).isTrue();
        assertThat(cursor.isNull(0)).isTrue();
      }
      helper.getWritableDatabase().execSQL("INSERT INTO sms (_id, type, body) VALUES (73, 21, 'retained')");
      assertThat(ledger.recordSentResultWithMessageState(id, 1, 2, Activity.RESULT_OK, 130, false).changed)
          .isTrue();
      assertThat(ledger.recordSentResultWithMessageState(id, 1, 2, Activity.RESULT_OK, 140, false).changed)
          .isFalse();
      try (Cursor cursor = helper.getReadableDatabase().rawQuery("SELECT type FROM sms WHERE _id = 73", null)) {
        assertThat(cursor.moveToFirst()).isTrue();
        assertThat(cursor.getLong(0)).isEqualTo(23);
      }
      assertThat(ledger.getPendingEffects(1)).hasSize(1);
      String retry = ledger.createAttempt(74, 1, -1, 150);
      helper.getWritableDatabase().execSQL("INSERT INTO sms (_id, type, body) VALUES (74, 21, 'retained')");
      assertThat(ledger.recordSentResultWithMessageState(retry, 0, 1,
          SmsManager.RESULT_ERROR_NO_SERVICE, 160, false).decision)
          .isEqualTo(SmsSendAttempt.Decision.RETRYABLE_FAILURE);
      try (Cursor cursor = helper.getReadableDatabase().rawQuery("SELECT type FROM sms WHERE _id = 74", null)) {
        assertThat(cursor.moveToFirst()).isTrue();
        assertThat(cursor.getLong(0)).isEqualTo(21);
      }
      assertThatThrownBy(() -> ledger.applyPendingMessageState(retry,
          SmsSendAttempt.Decision.RETRYABLE_FAILURE, false)).isInstanceOf(IllegalArgumentException.class);
    } finally {
      helper.close();
      context.deleteDatabase(name);
    }
  }

  @Test
  public void replaysMessageStateAfterRestart() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    String name = "replay-" + UUID.randomUUID() + ".db";
    Class<?> helperClass = Class.forName("org.smssecure.smssecure.database.DatabaseFactory$DatabaseHelper");
    Constructor<?> constructor = helperClass.getDeclaredConstructor(Context.class, String.class,
        SQLiteDatabase.CursorFactory.class, int.class);
    constructor.setAccessible(true);
    SQLiteOpenHelper first = (SQLiteOpenHelper) constructor.newInstance(context, name, null, 32);
    SmsSendAttemptDatabase ledger = new SmsSendAttemptDatabase(first);
    SQLiteDatabase db = first.getWritableDatabase();
    db.execSQL("INSERT INTO sms (_id, type, body) VALUES (91, 21, 'retained')");
    db.execSQL("INSERT INTO sms (_id, type, body) VALUES (92, 20, 'changed')");
    db.execSQL("INSERT INTO sms (_id, type, body) VALUES (93, 21, 'retry')");
    String terminal = ledger.createAttempt(91, 1, -1, 100);
    String changed = ledger.createAttempt(92, 1, -1, 100);
    String retry = ledger.createAttempt(93, 1, -1, 100);
    ledger.recordSentResult(terminal, 0, 1, Activity.RESULT_OK, 110);
    ledger.recordSentResult(changed, 0, 1, Activity.RESULT_OK, 120);
    ledger.recordSentResult(retry, 0, 1, SmsManager.RESULT_ERROR_RADIO_OFF, 130);
    first.close();

    SQLiteOpenHelper reopened = (SQLiteOpenHelper) constructor.newInstance(context, name, null, 32);
    try {
      ledger = new SmsSendAttemptDatabase(reopened);
      assertThat(ledger.replayPendingMessageStates(1)).isEqualTo(1);
      try (Cursor cursor = reopened.getReadableDatabase().rawQuery("SELECT type FROM sms WHERE _id = 91", null)) {
        assertThat(cursor.moveToFirst()).isTrue();
        assertThat(cursor.getLong(0)).isEqualTo(23);
      }
      assertThat(ledger.getPendingEffects(5)).hasSize(3);
      assertThatThrownBy(() -> new SmsSendAttemptDatabase(reopened).replayPendingMessageStates(5))
          .isInstanceOf(IllegalStateException.class);
      try (Cursor cursor = reopened.getReadableDatabase().rawQuery("SELECT type FROM sms WHERE _id = 92", null)) {
        assertThat(cursor.moveToFirst()).isTrue();
        assertThat(cursor.getLong(0)).isEqualTo(20);
      }
      try (Cursor cursor = reopened.getReadableDatabase().rawQuery("SELECT type FROM sms WHERE _id = 93", null)) {
        assertThat(cursor.moveToFirst()).isTrue();
        assertThat(cursor.getLong(0)).isEqualTo(21);
      }
      reopened.getWritableDatabase().execSQL("UPDATE sms SET type = 21 WHERE _id = 92");
      assertThat(ledger.replayPendingMessageStates(1)).isEqualTo(1);
      assertThat(ledger.replayPendingMessageStates(1)).isZero();
      assertThat(ledger.getPendingEffects(5)).hasSize(3);
    } finally {
      reopened.close();
      context.deleteDatabase(name);
    }
  }

  @Test
  public void replayRestoresSecureBitWhenBaseStateIsAlreadySent() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    String name = "secure-replay-" + UUID.randomUUID() + ".db";
    Class<?> helperClass = Class.forName("org.smssecure.smssecure.database.DatabaseFactory$DatabaseHelper");
    Constructor<?> constructor = helperClass.getDeclaredConstructor(Context.class, String.class,
        SQLiteDatabase.CursorFactory.class, int.class);
    constructor.setAccessible(true);
    SQLiteOpenHelper first = (SQLiteOpenHelper) constructor.newInstance(context, name, null, 32);
    first.getWritableDatabase().execSQL("INSERT INTO sms (_id, type, body) VALUES (94, 21, 'secure')");
    SmsSendAttemptDatabase ledger = new SmsSendAttemptDatabase(first);
    String attempt = ledger.createAttempt(94, 1, -1, 100);
    ledger.recordSentResultWithMessageState(attempt, 0, 1, Activity.RESULT_OK, 110, true);
    first.getWritableDatabase().execSQL("UPDATE sms SET type = 23 WHERE _id = 94");
    first.close();

    SQLiteOpenHelper reopened = (SQLiteOpenHelper) constructor.newInstance(context, name, null, 32);
    try {
      SmsSendAttemptDatabase replay = new SmsSendAttemptDatabase(reopened);
      assertThat(replay.acknowledgeEffect(attempt, SmsSendAttempt.Decision.SUCCEEDED)).isFalse();
      assertThat(replay.replayPendingMessageStates(1)).isEqualTo(1);
      try (Cursor cursor = reopened.getReadableDatabase().rawQuery("SELECT type FROM sms WHERE _id = 94", null)) {
        assertThat(cursor.moveToFirst()).isTrue();
        assertThat(cursor.getLong(0)).isEqualTo(23 | MmsSmsColumns.Types.SECURE_MESSAGE_BIT);
      }
      assertThat(replay.acknowledgeEffect(attempt, SmsSendAttempt.Decision.SUCCEEDED)).isTrue();
    } finally {
      reopened.close();
      context.deleteDatabase(name);
    }
  }

  @Test
  public void version32To30To32RetainsPendingAndAcknowledgedState() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    String name = shortDatabaseName("r32-");
    Constructor<?> constructor = databaseHelperConstructor();
    SQLiteOpenHelper version32 = (SQLiteOpenHelper) constructor.newInstance(context, name, null, 32);
    SQLiteDatabase db = version32.getWritableDatabase();
    db.execSQL("INSERT INTO sms (_id, type, body) VALUES " +
        "(90, 21, 'retry'), (91, 21, 'secure-parts'), (92, ?, 'end-session'), " +
        "(93, 21, 'acknowledged-delivery'), (94, 21, 'leased-delivery')",
        new Object[]{21 | MmsSmsColumns.Types.END_SESSION_BIT});
    SmsSendAttemptDatabase ledger = new SmsSendAttemptDatabase(version32);

    String retry = ledger.createAttempt(90, 1, 1, 100);
    ledger.recordSentResult(retry, 0, 1, SmsManager.RESULT_ERROR_NO_SERVICE, 110);
    assertThat(ledger.scheduleRetry(retry, 500)).isTrue();

    String secure = ledger.createAttempt(91, 2, 1, 120);
    ledger.recordSentResultWithMessageState(secure, 0, 2, Activity.RESULT_OK, 130, true);
    ledger.recordSentResultWithMessageState(secure, 1, 2, Activity.RESULT_OK, 140, true);

    String endSession = ledger.createAttempt(92, 1, 2, 150);
    assertThat(ledger.stageEndSessionSnapshot(endSession, "123.2:retained")).isTrue();
    ledger.recordSentResultWithMessageState(endSession, 0, 1, Activity.RESULT_OK, 160, false);

    String acknowledged = ledger.createAttempt(93, 1, 1, 170);
    ledger.recordSentResultWithMessageState(acknowledged, 0, 1, Activity.RESULT_OK, 180, false);
    assertThat(ledger.recordDeliveryResult(acknowledged, 0, 1, 0, 190)).isTrue();
    SmsSendAttemptDatabase.PendingDelivery acknowledgedDelivery = ledger.claimPendingDelivery(1_000, 2_000);
    assertThat(acknowledgedDelivery).isNotNull();
    assertThat(acknowledgedDelivery.attemptId).isEqualTo(acknowledged);
    assertThat(ledger.acknowledgeDelivery(acknowledgedDelivery)).isTrue();

    String leased = ledger.createAttempt(94, 1, 1, 200);
    ledger.recordSentResultWithMessageState(leased, 0, 1, Activity.RESULT_OK, 210, false);
    assertThat(ledger.recordDeliveryResult(leased, 0, 1, 0, 220)).isTrue();
    SmsSendAttemptDatabase.PendingDelivery lease = ledger.claimPendingDelivery(1_000, 2_000);
    assertThat(lease).isNotNull();
    assertThat(lease.attemptId).isEqualTo(leased);
    version32.close();

    SQLiteOpenHelper version30 = (SQLiteOpenHelper) constructor.newInstance(context, name, null, 30);
    version30.getWritableDatabase();
    version30.close();

    SQLiteOpenHelper reopened = (SQLiteOpenHelper) constructor.newInstance(context, name, null, 32);
    try {
      db = reopened.getWritableDatabase();
      assertThat(db.getVersion()).isEqualTo(32);
      assertCompleteSchema(db);
      ledger = new SmsSendAttemptDatabase(reopened);
      assertThat(ledger.getDueRetries(500, 5)).containsExactly(retry);
      assertThat(ledger.getPendingEndSessions(5)).extracting(effect -> effect.snapshot)
          .containsExactly("123.2:retained");
      assertThat(ledger.getPendingDeliveredMessages(5)).containsExactly(94L);
      assertThat(ledger.claimPendingDelivery(1_999, 3_000)).isNull();

      try (Cursor cursor = db.rawQuery(
          "SELECT state, effect_pending, secure_intent FROM sms_send_attempt WHERE attempt_id = ?",
          new String[]{secure})) {
        assertThat(cursor.moveToFirst()).isTrue();
        assertThat(cursor.getString(0)).isEqualTo("SUCCEEDED");
        assertThat(cursor.getInt(1)).isEqualTo(1);
        assertThat(cursor.getInt(2)).isEqualTo(1);
      }
      try (Cursor cursor = db.rawQuery(
          "SELECT part_index, sent_result, sent_at FROM sms_send_part WHERE attempt_id = ? ORDER BY part_index",
          new String[]{secure})) {
        assertThat(cursor.moveToNext()).isTrue();
        assertThat(cursor.getInt(0)).isZero();
        assertThat(cursor.getInt(1)).isEqualTo(Activity.RESULT_OK);
        assertThat(cursor.getLong(2)).isEqualTo(130);
        assertThat(cursor.moveToNext()).isTrue();
        assertThat(cursor.getInt(0)).isEqualTo(1);
        assertThat(cursor.getInt(1)).isEqualTo(Activity.RESULT_OK);
        assertThat(cursor.getLong(2)).isEqualTo(140);
      }
      try (Cursor cursor = db.rawQuery(
          "SELECT delivery_effect_pending, delivery_effect_claim_token, delivery_effect_claim_expires_at " +
          "FROM sms_send_attempt WHERE attempt_id IN (?, ?) ORDER BY message_id",
          new String[]{acknowledged, leased})) {
        assertThat(cursor.moveToNext()).isTrue();
        assertThat(cursor.getInt(0)).isZero();
        assertThat(cursor.isNull(1)).isTrue();
        assertThat(cursor.isNull(2)).isTrue();
        assertThat(cursor.moveToNext()).isTrue();
        assertThat(cursor.getInt(0)).isEqualTo(1);
        assertThat(cursor.getString(1)).isEqualTo(lease.claimToken);
        assertThat(cursor.getLong(2)).isEqualTo(2_000);
      }
      try (Cursor cursor = db.rawQuery(
          "SELECT sent_result, sent_at, delivered_result, delivered_at FROM sms_send_part " +
          "WHERE attempt_id = ? AND part_index = 0", new String[]{leased})) {
        assertThat(cursor.moveToFirst()).isTrue();
        assertThat(cursor.getInt(0)).isEqualTo(Activity.RESULT_OK);
        assertThat(cursor.getLong(1)).isEqualTo(210);
        assertThat(cursor.getInt(2)).isZero();
        assertThat(cursor.getLong(3)).isEqualTo(220);
      }
      SmsSendAttemptDatabase.PendingDelivery recovered = ledger.claimPendingDelivery(2_000, 3_000);
      assertThat(recovered).isNotNull();
      assertThat(recovered.attemptId).isEqualTo(leased);
      assertThat(ledger.acknowledgeDelivery(recovered)).isTrue();
      assertThat(ledger.getPendingDeliveredMessages(5)).isEmpty();
    } finally {
      reopened.close();
      context.deleteDatabase(name);
    }
  }

}