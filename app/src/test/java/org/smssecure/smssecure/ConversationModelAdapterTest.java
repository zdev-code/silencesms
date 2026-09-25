package org.smssecure.smssecure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.FrameLayout;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.data.conversationthread.ConversationMessageRow;
import org.smssecure.smssecure.database.model.MediaMmsMessageRecord;
import org.smssecure.smssecure.database.model.MessageRecord;
import org.smssecure.smssecure.mms.AudioSlide;
import org.smssecure.smssecure.mms.Slide;
import org.smssecure.smssecure.mms.SlideDeck;
import org.smssecure.smssecure.recipients.Recipients;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@RunWith(RobolectricTestRunner.class)
public class ConversationModelAdapterTest {
  private ConversationModelAdapter adapter;
  private ConversationMessageMapper mapper;

  @Before
  public void setUp() {
    Context context = ApplicationProvider.getApplicationContext();
    mapper = mock(ConversationMessageMapper.class);
    adapter = new ConversationModelAdapter(
        context, mock(MasterSecret.class), Locale.US, null, mock(Recipients.class),
      mapper);
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

  @Test
  public void refreshedSnapshotReplacesCachedRecordsWithTheSameStableIds() {
    ConversationMessageRow sending = mockRow("SMS::1::1");
    ConversationMessageRow decrypting = mockRow("SMS::2::1");
    ConversationMessageRow sent = mockRow("SMS::1::1");
    ConversationMessageRow decrypted = mockRow("SMS::2::1");
    MessageRecord sendingRecord = mock(MessageRecord.class);
    MessageRecord decryptingRecord = mock(MessageRecord.class);
    MessageRecord sentRecord = mock(MessageRecord.class);
    MessageRecord decryptedRecord = mock(MessageRecord.class);
    when(mapper.map(sending)).thenReturn(sendingRecord);
    when(mapper.map(decrypting)).thenReturn(decryptingRecord);
    when(mapper.map(sent)).thenReturn(sentRecord);
    when(mapper.map(decrypted)).thenReturn(decryptedRecord);
    Set<String> selected = Set.of("SMS::1::1", "SMS::2::1");

    adapter.setMessages(List.of(sending, decrypting), selected);
    assertThat(adapter.getSelectedItems()).containsExactlyInAnyOrder(sendingRecord, decryptingRecord);
    long outgoingId = adapter.getItemId(0);
    long incomingId = adapter.getItemId(1);

    adapter.setMessages(List.of(sent, decrypted), selected);

    assertThat(adapter.getSelectedItems()).containsExactlyInAnyOrder(sentRecord, decryptedRecord);
    assertThat(adapter.getItemId(0)).isEqualTo(outgoingId);
    assertThat(adapter.getItemId(1)).isEqualTo(incomingId);
  }

  private static ConversationMessageRow mockRow(String stableId) {
    ConversationMessageRow row = mock(ConversationMessageRow.class);
    when(row.getStableId()).thenReturn(stableId);
    return row;
  }

  @Test
  public void imageMessagesUseTheirOwnRowTypes() {
    adapter.setMessages(List.of(mapped("SMS::1::1", mock(MessageRecord.class)),
                                mapped("MMS::2::1", media(false, true)),
                                mapped("MMS::3::1", media(true, false))), Set.of());

    int text = adapter.getItemViewType(0);
    int image = adapter.getItemViewType(1);
    int audio = adapter.getItemViewType(2);
    assertThat(image).isNotEqualTo(text).isNotEqualTo(audio);
  }

  @Test
  public void mediaRowsReceiveItemClickHandling() {
    Context themed = new ContextThemeWrapper(ApplicationProvider.getApplicationContext(),
                                             R.style.Silence_LightTheme);
    adapter = new ConversationModelAdapter(themed, mock(MasterSecret.class), Locale.US, null,
                                           mock(Recipients.class), mapper);
    adapter.setMessages(List.of(mapped("MMS::1::1", media(false, true)),
                                mapped("MMS::2::1", media(true, false))), Set.of());
    FrameLayout parent = new FrameLayout(themed);

    for (int position = 0; position < 2; position++) {
      View item = adapter.onCreateViewHolder(parent, adapter.getItemViewType(position)).itemView;
      assertThat(item.hasOnClickListeners()).isTrue();
      assertThat(item.isLongClickable()).isTrue();
    }
  }

  private ConversationMessageRow mapped(String stableId, MessageRecord record) {
    ConversationMessageRow row = mockRow(stableId);
    when(mapper.map(row)).thenReturn(record);
    return row;
  }

  private static MediaMmsMessageRecord media(boolean audio, boolean image) {
    SlideDeck deck = mock(SlideDeck.class);
    if (audio) when(deck.getAudioSlide()).thenReturn(mock(AudioSlide.class));
    if (image) when(deck.getThumbnailSlide()).thenReturn(mock(Slide.class));
    MediaMmsMessageRecord record = mock(MediaMmsMessageRecord.class);
    when(record.isMms()).thenReturn(true);
    when(record.getSlideDeck()).thenReturn(deck);
    return record;
  }
}