package org.smssecure.smssecure.database.loaders;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.MergeCursor;

import org.smssecure.smssecure.contacts.ContactAccessor;
import org.smssecure.smssecure.database.DatabaseFactory;
import org.smssecure.smssecure.database.ThreadDatabase;
import org.smssecure.smssecure.util.AbstractCursorLoader;

import java.util.LinkedList;
import java.util.List;

public class ConversationListLoader extends AbstractCursorLoader {

  private final String filter;
  private final boolean archived;
  private final DataSource dataSource;
  private final InboxCursorFactory inboxCursorFactory;

  public ConversationListLoader(Context context, String filter, boolean archived) {
    this(context, filter, archived, new DefaultDataSource(context.getApplicationContext()),
         ConversationListLoader::createInboxCursor);
  }

  ConversationListLoader(Context context, String filter, boolean archived, DataSource dataSource,
                         InboxCursorFactory inboxCursorFactory) {
    super(context);
    this.filter             = filter;
    this.archived           = archived;
    this.dataSource         = dataSource;
    this.inboxCursorFactory = inboxCursorFactory;
  }

  @Override
  public Cursor getCursor() {
    if      (filter != null && filter.trim().length() != 0) return getFilteredConversationList(filter);
    else if (!archived)                                     return getUnarchivedConversationList();
    else                                                    return getArchivedConversationList();
  }

  private Cursor getUnarchivedConversationList() {
    Cursor inboxCursor = dataSource.getConversationList();
    int archivedCount = dataSource.getArchivedConversationListCount();
    return inboxCursorFactory.create(inboxCursor, archivedCount);
  }

  private static Cursor createInboxCursor(Cursor inboxCursor, int archivedCount) {
    List<Cursor> cursorList = new LinkedList<>();
    cursorList.add(inboxCursor);

    if (archivedCount > 0) {
      MatrixCursor switchToArchiveCursor = new MatrixCursor(new String[] {
          ThreadDatabase.ID, ThreadDatabase.DATE, ThreadDatabase.MESSAGE_COUNT,
          ThreadDatabase.RECIPIENT_IDS, ThreadDatabase.SNIPPET, ThreadDatabase.READ,
          ThreadDatabase.TYPE, ThreadDatabase.SNIPPET_TYPE, ThreadDatabase.SNIPPET_URI,
          ThreadDatabase.ARCHIVED, ThreadDatabase.STATUS, ThreadDatabase.LAST_SEEN}, 1);

      switchToArchiveCursor.addRow(new Object[] {-1L, System.currentTimeMillis(), archivedCount,
                                                 "-1", null, 1, ThreadDatabase.DistributionTypes.ARCHIVE,
                                                 0, null, 0, -1, 0});

      cursorList.add(switchToArchiveCursor);
    }

    return new MergeCursor(cursorList.toArray(new Cursor[0]));
  }

  private Cursor getArchivedConversationList() {
    return dataSource.getArchivedConversationList();
  }

  private Cursor getFilteredConversationList(String filter) {
    return dataSource.getFilteredConversationList(filter);
  }

  interface DataSource {
    Cursor getConversationList();
    Cursor getArchivedConversationList();
    int getArchivedConversationListCount();
    Cursor getFilteredConversationList(String filter);
  }

  interface InboxCursorFactory {
    Cursor create(Cursor inboxCursor, int archivedCount);
  }

  private static final class DefaultDataSource implements DataSource {
    private final Context context;

    private DefaultDataSource(Context context) {
      this.context = context;
    }

    @Override
    public Cursor getConversationList() {
      return DatabaseFactory.getThreadDatabase(context).getConversationList();
    }

    @Override
    public Cursor getArchivedConversationList() {
      return DatabaseFactory.getThreadDatabase(context).getArchivedConversationList();
    }

    @Override
    public int getArchivedConversationListCount() {
      return DatabaseFactory.getThreadDatabase(context).getArchivedConversationListCount();
    }

    @Override
    public Cursor getFilteredConversationList(String filter) {
      List<String> numbers = ContactAccessor.getInstance().getNumbersForThreadSearchFilter(context, filter);
      return DatabaseFactory.getThreadDatabase(context).getFilteredConversationList(numbers);
    }
  }
}
