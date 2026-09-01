package org.smssecure.smssecure.ui.conversationthread;

import static org.assertj.core.api.Assertions.assertThat;

import androidx.lifecycle.SavedStateHandle;

import org.junit.Test;

import java.util.Map;
import java.util.Set;

public class ConversationThreadStateStoreTest {
  @Test
  public void storesOnlyTypedNavigationSelectionAndPagingState() {
    SavedStateHandle handle = new SavedStateHandle();
    ConversationThreadStateStore store = new ConversationThreadStateStore(handle, 8L, -1L);

    store.save(42L, true, Set.of("SMS::3::10"));

    assertThat(handle.keys()).containsExactlyInAnyOrder(
        ConversationThreadStateStore.KEY_THREAD_ID,
        ConversationThreadStateStore.KEY_LAST_SEEN,
        ConversationThreadStateStore.KEY_SELECTED_MESSAGE_IDS,
        ConversationThreadStateStore.KEY_FULL_HISTORY);
    assertThat(store.getThreadId()).isEqualTo(8L);
    assertThat(store.getLastSeen()).isEqualTo(42L);
    assertThat(store.isFullHistory()).isTrue();
    assertThat(store.getSelectedMessageIds()).containsExactly("SMS::3::10");
  }

  @Test
  public void restoresExistingValuesInsteadOfOverwritingThem() {
    SavedStateHandle handle = new SavedStateHandle(Map.of(
        ConversationThreadStateStore.KEY_THREAD_ID, 9L,
        ConversationThreadStateStore.KEY_LAST_SEEN, 50L,
        ConversationThreadStateStore.KEY_FULL_HISTORY, true));

    ConversationThreadStateStore store = new ConversationThreadStateStore(handle, 8L, -1L);

    assertThat(store.getThreadId()).isEqualTo(9L);
    assertThat(store.getLastSeen()).isEqualTo(50L);
    assertThat(store.isFullHistory()).isTrue();
  }
}
