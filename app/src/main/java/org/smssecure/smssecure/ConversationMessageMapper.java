package org.smssecure.smssecure;

import android.content.Context;
import android.database.MatrixCursor;

import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.data.conversationthread.ConversationMessageRow;
import org.smssecure.smssecure.database.DatabaseFactory;
import org.smssecure.smssecure.database.MmsSmsDatabase;
import org.smssecure.smssecure.database.model.MessageRecord;

import java.util.Objects;

public final class ConversationMessageMapper {
  private final MmsSmsDatabase database;
  private MasterSecret masterSecret;

  public ConversationMessageMapper(Context context, MasterSecret masterSecret) {
    this.database = DatabaseFactory.getMmsSmsDatabase(
        Objects.requireNonNull(context).getApplicationContext());
    this.masterSecret = Objects.requireNonNull(masterSecret);
  }

  public MessageRecord map(ConversationMessageRow row) {
    MasterSecret secret = masterSecret;
    if (secret == null) throw new IllegalStateException("Mapper has been cleared");
    try (MatrixCursor cursor = new MatrixCursor(row.copyColumnNames(), 1)) {
      cursor.addRow(row.copyValues());
      if (!cursor.moveToFirst()) throw new IllegalStateException("Unable to position message row");
      return database.readerFor(cursor, secret).getCurrent();
    }
  }

  public void clear() {
    masterSecret = null;
  }
}
