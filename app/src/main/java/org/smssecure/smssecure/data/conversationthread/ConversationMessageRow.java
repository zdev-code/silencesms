package org.smssecure.smssecure.data.conversationthread;

import android.database.Cursor;

import org.smssecure.smssecure.database.MmsSmsColumns;
import org.smssecure.smssecure.database.MmsSmsDatabase;

import java.util.Arrays;

public final class ConversationMessageRow {
  private final String[] columnNames;
  private final Object[] values;
  private final long     messageId;
  private final String   transport;
  private final String   stableId;

  private ConversationMessageRow(String[] columnNames, Object[] values, long messageId,
                                 String transport, String stableId) {
    this.columnNames = columnNames;
    this.values      = values;
    this.messageId   = messageId;
    this.transport   = transport;
    this.stableId    = stableId;
  }

  public static ConversationMessageRow copyCurrent(Cursor cursor) {
    String[] columnNames = cursor.getColumnNames().clone();
    Object[] values = new Object[columnNames.length];
    for (int index = 0; index < columnNames.length; index++) {
      switch (cursor.getType(index)) {
        case Cursor.FIELD_TYPE_NULL:
          values[index] = null;
          break;
        case Cursor.FIELD_TYPE_INTEGER:
          values[index] = cursor.getLong(index);
          break;
        case Cursor.FIELD_TYPE_FLOAT:
          values[index] = cursor.getDouble(index);
          break;
        case Cursor.FIELD_TYPE_BLOB:
          byte[] blob = cursor.getBlob(index);
          values[index] = blob == null ? null : blob.clone();
          break;
        case Cursor.FIELD_TYPE_STRING:
        default:
          values[index] = cursor.getString(index);
          break;
      }
    }
    return new ConversationMessageRow(
        columnNames,
        values,
        cursor.getLong(cursor.getColumnIndexOrThrow(MmsSmsColumns.ID)),
        cursor.getString(cursor.getColumnIndexOrThrow(MmsSmsDatabase.TRANSPORT)),
        cursor.getString(cursor.getColumnIndexOrThrow(MmsSmsColumns.UNIQUE_ROW_ID)));
  }

  public long getMessageId() { return messageId; }
  public String getTransport() { return transport; }
  public String getStableId() { return stableId; }
  public boolean isMms() { return MmsSmsDatabase.MMS_TRANSPORT.equals(transport); }

  public String[] copyColumnNames() {
    return columnNames.clone();
  }

  public Object[] copyValues() {
    Object[] copy = values.clone();
    for (int index = 0; index < copy.length; index++) {
      if (copy[index] instanceof byte[]) copy[index] = ((byte[]) copy[index]).clone();
    }
    return copy;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof ConversationMessageRow && stableId.equals(((ConversationMessageRow) other).stableId);
  }

  @Override
  public int hashCode() {
    return stableId.hashCode();
  }

  @Override
  public String toString() {
    return "ConversationMessageRow{" + stableId + ", columns=" + Arrays.toString(columnNames) + "}";
  }
}
