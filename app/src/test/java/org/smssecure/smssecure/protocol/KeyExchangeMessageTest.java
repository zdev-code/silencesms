package org.smssecure.smssecure.protocol;

import org.junit.Test;
import org.whispersystems.libsignal.InvalidMessageException;

public class KeyExchangeMessageTest {

  @Test(expected = InvalidMessageException.class)
  public void emptyIsInvalid() throws Exception {
    new KeyExchangeMessage(new byte[0]);
  }

  @Test(expected = InvalidMessageException.class)
  public void versionOnlyIsInvalid() throws Exception {
    new KeyExchangeMessage(new byte[] {0x33});
  }
}
