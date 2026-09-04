package org.smssecure.smssecure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.data.conversationthread.ConversationMessageRow;
import org.smssecure.smssecure.recipients.Recipients;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@RunWith(RobolectricTestRunner.class)
public class ConversationModelAdapterTest {
  private ConversationModelAdapter adapter;

  @Before
  public void setUp() {
    Context context = ApplicationProvider.getApplicationContext();
    adapter = new ConversationModelAdapter(
        context, mock(MasterSecret.class), Locale.US, null, mock(Recipients.class),
        mock(ConversationMessageMapper.class));
  }

  @Test
  public void itemIdsDistinguishTransportAndMessageIdentity() {
    ConversationMessageRow sms = mockRow("SMS::1::1");
    ConversationMessageRow firstMms = mockRow("MMS::1::1");
    ConversationMessageRow secondMms = mockRow("MMS::2::1");
    adapter.setMessages(List.of(sms, firstMms, secondMms), Set.of());

    assertThat(adapter.hasStableIds()).isTrue();
    assertThat(adapter.getItemId(0)).isNotEqualTo(adapter.getItemId(1));
    assertThat(adapter.getItemId(1)).isNotEqualTo(adapter.getItemId(2));
  }

  private static ConversationMessageRow mockRow(String stableId) {
    ConversationMessageRow row = mock(ConversationMessageRow.class);
    when(row.getStableId()).thenReturn(stableId);
    return row;
  }
}