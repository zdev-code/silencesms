package org.smssecure.smssecure.database.loaders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.database.Cursor;

import org.junit.Before;
import org.junit.Test;
import org.smssecure.smssecure.BaseUnitTest;

public class ConversationListLoaderTest extends BaseUnitTest {
  private ConversationListLoader.DataSource dataSource;
  private ConversationListLoader.InboxCursorFactory inboxCursorFactory;

  @Before
  public void configureContext() {
    when(context.getApplicationContext()).thenReturn(context);
    dataSource         = mock(ConversationListLoader.DataSource.class);
    inboxCursorFactory = mock(ConversationListLoader.InboxCursorFactory.class);
  }

  @Test
  public void archivedModeUsesArchivedQuery() {
    Cursor archivedCursor = mock(Cursor.class);
    when(dataSource.getArchivedConversationList()).thenReturn(archivedCursor);

    Cursor result = new ConversationListLoader(context, null, true, dataSource, inboxCursorFactory).getCursor();

    assertThat(result).isSameAs(archivedCursor);
    verify(dataSource).getArchivedConversationList();
    verify(dataSource, never()).getConversationList();
  }

  @Test
  public void nonBlankFilterTakesPrecedenceOverArchiveMode() {
    Cursor filteredCursor = mock(Cursor.class);
    when(dataSource.getFilteredConversationList("Alice")).thenReturn(filteredCursor);

    Cursor result = new ConversationListLoader(context, "Alice", true, dataSource, inboxCursorFactory).getCursor();

    assertThat(result).isSameAs(filteredCursor);
    verify(dataSource).getFilteredConversationList("Alice");
    verify(dataSource, never()).getArchivedConversationList();
  }

  @Test
  public void blankFilterUsesInboxQuery() {
    Cursor inboxCursor = mock(Cursor.class);
    Cursor mergedCursor = mock(Cursor.class);
    when(dataSource.getConversationList()).thenReturn(inboxCursor);
    when(dataSource.getArchivedConversationListCount()).thenReturn(0);
    when(inboxCursorFactory.create(inboxCursor, 0)).thenReturn(mergedCursor);

    Cursor result = new ConversationListLoader(context, "  ", false, dataSource, inboxCursorFactory).getCursor();

    assertThat(result).isSameAs(mergedCursor);
    verify(dataSource).getConversationList();
    verify(dataSource).getArchivedConversationListCount();
    verify(inboxCursorFactory).create(inboxCursor, 0);
    verify(dataSource, never()).getFilteredConversationList("  ");
  }

  @Test
  public void inboxPassesArchivedCountToCursorFactory() {
    Cursor inboxCursor = mock(Cursor.class);
    Cursor mergedCursor = mock(Cursor.class);
    when(dataSource.getConversationList()).thenReturn(inboxCursor);
    when(dataSource.getArchivedConversationListCount()).thenReturn(3);
    when(inboxCursorFactory.create(inboxCursor, 3)).thenReturn(mergedCursor);

    Cursor result = new ConversationListLoader(context, null, false, dataSource, inboxCursorFactory).getCursor();

    assertThat(result).isSameAs(mergedCursor);
    verify(inboxCursorFactory).create(inboxCursor, 3);
  }
}