package org.smssecure.smssecure.database;


import android.content.Context;
import android.net.Uri;
import android.os.Environment;

import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.database.model.SmsMessageRecord;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;

public class PlaintextBackupExporter {

  public static void exportPlaintextToSd(Context context, MasterSecret masterSecret)
      throws NoExternalStorageException, IOException
  {
    verifyExternalStorageForPlaintextExport();
    exportPlaintext(context, masterSecret, new XmlBackup.Writer(getPlaintextExportDirectoryPath(),
                                   getMessageCount(context)));
    }

    public static void exportPlaintextToUri(Context context, MasterSecret masterSecret, Uri uri)
      throws IOException
    {
    OutputStream outputStream = context.getContentResolver().openOutputStream(uri, "wt");
    if (outputStream == null) throw new IOException("Unable to open plaintext backup output stream");

    exportPlaintext(context, masterSecret, new XmlBackup.Writer(outputStream, getMessageCount(context)));
  }

  private static void verifyExternalStorageForPlaintextExport() throws NoExternalStorageException {
    if (!Environment.getExternalStorageDirectory().canWrite())
      throw new NoExternalStorageException();
  }

  private static String getPlaintextExportDirectoryPath() {
    File sdDirectory = Environment.getExternalStorageDirectory();
    return sdDirectory.getAbsolutePath() + File.separator + "SilencePlaintextBackup.xml";
  }

  private static int getMessageCount(Context context) {
    return DatabaseFactory.getSmsDatabase(context).getMessageCount();
  }

  private static void exportPlaintext(Context context, MasterSecret masterSecret,
                                      XmlBackup.Writer writer)
      throws IOException
  {
    SmsMessageRecord record;
    EncryptingSmsDatabase.Reader reader = null;
    int skip                            = 0;
    int ROW_LIMIT                       = 500;

    try {
      do {
        if (reader != null)
          reader.close();

        reader = DatabaseFactory.getEncryptingSmsDatabase(context).getMessages(masterSecret, skip, ROW_LIMIT);

        while ((record = reader.getNext()) != null) {
          XmlBackup.XmlBackupItem item =
              new XmlBackup.XmlBackupItem(0, record.getIndividualRecipient().getNumber(),
                                          record.getDateReceived(),
                                          MmsSmsColumns.Types.translateToSystemBaseType(record.getType()),
                                          null, record.getDisplayBody().toString(), null,
                                          1, record.getDeliveryStatus());

          writer.writeItem(item);
        }

        skip += ROW_LIMIT;
      } while (reader.getCount() > 0);
    } finally {
      if (reader != null) reader.close();
      writer.close();
    }
  }
}
