package org.smssecure.smssecure.data.contact;

import android.content.Context;
import android.database.Cursor;
import android.database.MergeCursor;
import android.text.TextUtils;

import org.smssecure.smssecure.contacts.ContactsDatabase;
import org.smssecure.smssecure.database.DatabaseFactory;
import org.smssecure.smssecure.util.NumberUtil;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor.TaskHandle;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class DefaultContactRepository implements ContactRepository {
  private final Context context;
  private final AppTaskExecutor executor;

  public DefaultContactRepository(Context context, AppTaskExecutor executor) {
    this.context = Objects.requireNonNull(context).getApplicationContext();
    this.executor = Objects.requireNonNull(executor);
  }

  @Override public TaskHandle load(String filter, Callback callback) {
    Objects.requireNonNull(callback);
    String normalized = filter == null ? "" : filter;
    return executor.submitParallel(() -> read(normalized), callback::onSuccess, callback::onFailure);
  }

  private List<ContactEntry> read(String filter) {
    ContactsDatabase database = DatabaseFactory.getContactsDatabase(context);
    List<Cursor> cursors = new ArrayList<>(3);
    cursors.add(database.querySilenceContacts(filter));
    cursors.add(database.querySystemContacts(filter));
    if (!TextUtils.isEmpty(filter) && NumberUtil.isValidSmsOrEmail(filter)) {
      cursors.add(database.getNewNumberCursor(filter));
    }

    List<ContactEntry> entries = new ArrayList<>();
    try (MergeCursor cursor = new MergeCursor(cursors.toArray(new Cursor[0]))) {
      while (cursor.moveToNext()) {
        entries.add(new ContactEntry(
            cursor.getLong(cursor.getColumnIndexOrThrow(ContactsDatabase.ID_COLUMN)),
            cursor.getInt(cursor.getColumnIndexOrThrow(ContactsDatabase.CONTACT_TYPE_COLUMN)),
            cursor.getString(cursor.getColumnIndexOrThrow(ContactsDatabase.NAME_COLUMN)),
            cursor.getString(cursor.getColumnIndexOrThrow(ContactsDatabase.NUMBER_COLUMN)),
            cursor.getInt(cursor.getColumnIndexOrThrow(ContactsDatabase.NUMBER_TYPE_COLUMN)),
            cursor.getString(cursor.getColumnIndexOrThrow(ContactsDatabase.LABEL_COLUMN))));
      }
    }
    return List.copyOf(entries);
  }
}