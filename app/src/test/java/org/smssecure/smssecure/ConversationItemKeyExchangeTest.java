package org.smssecure.smssecure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.smssecure.smssecure.database.model.MessageRecord;

public class ConversationItemKeyExchangeTest {

  @Test
  public void pendingKeyExchangeIsIntercepted() {
    assertThat(ConversationItem.shouldInterceptKeyExchangeMessage(keyExchange())).isTrue();
  }

  @Test
  public void corruptKeyExchangeIsNotIntercepted() {
    MessageRecord record = keyExchange();
    when(record.isCorruptedKeyExchange()).thenReturn(true);

    assertThat(ConversationItem.shouldInterceptKeyExchangeMessage(record)).isFalse();
  }

  @Test
  public void badVersionKeyExchangeIsNotIntercepted() {
    MessageRecord record = keyExchange();
    when(record.isInvalidVersionKeyExchange()).thenReturn(true);

    assertThat(ConversationItem.shouldInterceptKeyExchangeMessage(record)).isFalse();
  }

  private static MessageRecord keyExchange() {
    MessageRecord record = mock(MessageRecord.class);
    when(record.isKeyExchange()).thenReturn(true);
    return record;
  }
}
