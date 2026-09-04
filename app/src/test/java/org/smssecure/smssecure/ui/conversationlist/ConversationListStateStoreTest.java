package org.smssecure.smssecure.ui.conversationlist;

import static org.assertj.core.api.Assertions.assertThat;

import androidx.lifecycle.SavedStateHandle;

import org.junit.Test;

import java.util.Set;

public class ConversationListStateStoreTest {
  @Test
  public void savesOnlyApprovedPrimitiveAndIdentifierKeys() {
    SavedStateHandle handle = new SavedStateHandle();
    ConversationListStateStore store = new ConversationListStateStore(handle, true);

    store.save("+15551234567", Set.of(4L, 9L));

    assertThat(handle.keys()).containsExactlyInAnyOrder(
        ConversationListStateStore.KEY_ARCHIVED,
        ConversationListStateStore.KEY_SELECTED_THREAD_IDS);
    assertThat((Boolean) handle.get(ConversationListStateStore.KEY_ARCHIVED)).isTrue();
    assertThat(store.getFilter()).isEmpty();
    assertThat((long[]) handle.get(ConversationListStateStore.KEY_SELECTED_THREAD_IDS))
        .containsExactlyInAnyOrder(4L, 9L);
  }

  @Test
  public void ignoresInvalidRestoredThreadIds() {
    SavedStateHandle handle = new SavedStateHandle();
    handle.set(ConversationListStateStore.KEY_SELECTED_THREAD_IDS, new long[] {-1L, 0L, 3L});

    ConversationListStateStore store = new ConversationListStateStore(handle, false);

    assertThat(store.getSelectedThreadIds()).containsExactly(3L);
  }
}
