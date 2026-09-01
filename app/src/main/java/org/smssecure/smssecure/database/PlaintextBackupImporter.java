package org.smssecure.smssecure.database;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

import org.smssecure.smssecure.crypto.MasterCipher;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.recipients.RecipientFactory;
import org.smssecure.smssecure.recipients.RecipientFormattingException;
import org.smssecure.smssecure.recipients.Recipients;
import org.xmlpull.v1.XmlPullParserException;
import org.signal.libsignal.protocol.InvalidMessageException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;


public class PlaintextBackupImporter {

  private static final String TAG = PlaintextBackupImporter.class.getSimpleName();
  private static String backupPath;

  public enum ImportResult {
    IMPORTED,
    ALREADY_IMPORTED
  }

  public static ImportResult importPlaintextFromSd(Context context, MasterSecret masterSecret)
      throws NoExternalStorageException, IOException
  {
    Log.w("PlaintextBackupImporter", "Importing plaintext...");
    backupPath = getPlaintextExportDirectoryPath();
    verifyExternalStorageForPlaintextImport();

    try (XmlBackup backup = new XmlBackup(backupPath)) {
      return importPlaintext(context, masterSecret, backup);
    } catch (XmlPullParserException e) {
      Log.w("PlaintextBackupImporter", e);
      throw new IOException("Unable to open plaintext backup", e);
    }
  }

  public static ImportResult importPlaintextFromUri(Context context, MasterSecret masterSecret, Uri uri)
      throws IOException
  {
    Log.w("PlaintextBackupImporter", "Importing plaintext from uri..." + uri);

    try (InputStream inputStream = context.getContentResolver().openInputStream(uri)) {
      if (inputStream == null) throw new IOException("Unable to open plaintext backup input stream");
      try (XmlBackup backup = new XmlBackup(inputStream)) {
        return importPlaintext(context, masterSecret, backup);
      } catch (XmlPullParserException e) {
        Log.w("PlaintextBackupImporter", e);
        throw new IOException("Unable to parse plaintext backup", e);
      }
    }
  }

  private static void verifyExternalStorageForPlaintextImport() throws NoExternalStorageException {
    if (!Environment.getExternalStorageDirectory().canRead())
      throw new NoExternalStorageException();
  }

  private static String getPlaintextExportDirectoryPath() throws NoExternalStorageException {
    File sdDirectory = Environment.getExternalStorageDirectory();
    String[] files = {"SilencePlaintextBackup.xml", "TextSecurePlaintextBackup.xml", "SMSSecurePlaintextBackup.xml", "SignalPlaintextBackup.xml"};
    String path;

    for (String s : files){
      path = sdDirectory.getAbsolutePath() + File.separator + s;
      if (new File(path).exists()) {
        Log.i(TAG, "Importing backup from file '" + path + "'");
        return path;
      }
    }

    throw new NoExternalStorageException();
  }

  private static ImportResult importPlaintext(Context context, MasterSecret masterSecret,
                                              XmlBackup backup)
      throws IOException
  {
    Log.w("PlaintextBackupImporter", "importPlaintext()");
    SmsDatabase    db          = DatabaseFactory.getSmsDatabase(context);
    SQLiteDatabase transaction = db.beginTransaction();

    try {
      ThreadDatabase threads         = DatabaseFactory.getThreadDatabase(context);
      MasterCipher   masterCipher    = new MasterCipher(masterSecret);
      Set<Long>      modifiedThreads = new HashSet<Long>();
      BackupMessageDeduplicator deduplicator = getExistingMessages(transaction, masterCipher);
      boolean         imported       = false;
      XmlBackup.XmlBackupItem item;

      while ((item = backup.getNext()) != null) {
        if (item.getAddress() == null || item.getAddress().equals("null"))
          continue;

        if (!isAppropriateTypeForImport(item.getType()))
          continue;

        String fingerprint = BackupMessageFingerprint.fromBackup(item);
        if (!deduplicator.shouldImport(fingerprint))
          continue;

        Recipients      recipients = RecipientFactory.getRecipientsFromString(context, item.getAddress(), false);
        long            threadId   = threads.getThreadIdFor(recipients);
        SQLiteStatement statement  = db.createInsertStatement(transaction);

        addStringToStatement(statement, 1, item.getAddress());
        addNullToStatement(statement, 2);
        addLongToStatement(statement, 3, item.getDate());
        addLongToStatement(statement, 4, item.getDate());
        addLongToStatement(statement, 5, item.getProtocol());
        addLongToStatement(statement, 6, item.getRead());
        addLongToStatement(statement, 7, item.getStatus());
        addTranslatedTypeToStatement(statement, 8, item.getType());
        addNullToStatement(statement, 9);
        addStringToStatement(statement, 10, item.getSubject());
        addEncryptedStingToStatement(masterCipher, statement, 11, item.getBody());
        addStringToStatement(statement, 12, item.getServiceCenter());
        addLongToStatement(statement, 13, threadId);
        modifiedThreads.add(threadId);
        statement.execute();
        imported = true;
      }

      for (long threadId : modifiedThreads) {
        threads.update(threadId, true);
      }

      Log.w("PlaintextBackupImporter", "Exited loop");
      transaction.setTransactionSuccessful();
      return imported ? ImportResult.IMPORTED : ImportResult.ALREADY_IMPORTED;
    } catch (XmlPullParserException e) {
      Log.w("PlaintextBackupImporter", e);
      throw new IOException("XML Parsing error!");
    } finally {
      transaction.endTransaction();
    }
  }

  private static BackupMessageDeduplicator getExistingMessages(SQLiteDatabase database,
                                                                 MasterCipher masterCipher)
  {
    BackupMessageDeduplicator deduplicator = new BackupMessageDeduplicator();
    String[] projection = {SmsDatabase.ADDRESS, SmsDatabase.DATE_RECEIVED, SmsDatabase.TYPE,
                           SmsDatabase.PROTOCOL, SmsDatabase.SUBJECT, SmsDatabase.BODY,
                           SmsDatabase.SERVICE_CENTER};

    try (Cursor cursor = database.query(SmsDatabase.TABLE_NAME, projection, null, null,
                                        null, null, null)) {
      while (cursor.moveToNext()) {
        long internalType = cursor.getLong(cursor.getColumnIndexOrThrow(SmsDatabase.TYPE));
        int type = MmsSmsColumns.Types.translateToSystemBaseType(internalType);
        String body = cursor.getString(cursor.getColumnIndexOrThrow(SmsDatabase.BODY));

        try {
          if (body != null && MmsSmsColumns.Types.isSymmetricEncryption(internalType)) {
            body = masterCipher.decryptBody(body);
          }
        } catch (InvalidMessageException e) {
          Log.w(TAG, "Unable to fingerprint existing message", e);
          continue;
        }

        String fingerprint = BackupMessageFingerprint.create(
            cursor.getString(cursor.getColumnIndexOrThrow(SmsDatabase.ADDRESS)),
            cursor.getLong(cursor.getColumnIndexOrThrow(SmsDatabase.DATE_RECEIVED)),
            type,
            cursor.getInt(cursor.getColumnIndexOrThrow(SmsDatabase.PROTOCOL)),
            cursor.getString(cursor.getColumnIndexOrThrow(SmsDatabase.SUBJECT)),
            body,
            cursor.getString(cursor.getColumnIndexOrThrow(SmsDatabase.SERVICE_CENTER)));
        deduplicator.addExisting(fingerprint);
      }
    }

    return deduplicator;
  }

  private static void addEncryptedStingToStatement(MasterCipher masterCipher, SQLiteStatement statement, int index, String value) {
    if (value == null || value.equals("null")) {
      statement.bindNull(index);
    } else {
      statement.bindString(index, masterCipher.encryptBody(value));
    }
  }

  private static void addTranslatedTypeToStatement(SQLiteStatement statement, int index, int type) {
    statement.bindLong(index, SmsDatabase.Types.translateFromSystemBaseType(type) | SmsDatabase.Types.ENCRYPTION_SYMMETRIC_BIT);
  }

  private static void addStringToStatement(SQLiteStatement statement, int index, String value) {
    if (value == null || value.equals("null")) statement.bindNull(index);
    else                                       statement.bindString(index, value);
  }

  private static void addNullToStatement(SQLiteStatement statement, int index) {
    statement.bindNull(index);
  }

  private static void addLongToStatement(SQLiteStatement statement, int index, long value) {
    statement.bindLong(index, value);
  }

  private static boolean isAppropriateTypeForImport(long theirType) {
    long ourType = SmsDatabase.Types.translateFromSystemBaseType(theirType);

    return ourType == MmsSmsColumns.Types.BASE_INBOX_TYPE ||
           ourType == MmsSmsColumns.Types.BASE_SENT_TYPE ||
           ourType == MmsSmsColumns.Types.BASE_SENT_FAILED_TYPE;
  }


}
