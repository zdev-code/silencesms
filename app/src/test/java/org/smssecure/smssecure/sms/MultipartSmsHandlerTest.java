package org.smssecure.smssecure.sms;

import org.junit.Test;
import org.smssecure.smssecure.BaseUnitTest;
import org.smssecure.smssecure.protocol.SecureMessageWirePrefix;
import org.smssecure.smssecure.util.Base64;
import org.smssecure.smssecure.util.Conversions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MultipartSmsHandlerTest extends BaseUnitTest {

  private static IncomingTextMessage prefixed(byte[] payload) {
    String encoded = Base64.encodeBytesWithoutPadding(payload);
    String body    = new SecureMessageWirePrefix().calculatePrefix(encoded) + encoded;
    return new IncomingTextMessage("+15550100", 1, 0L, body, -1);
  }

  @Test
  public void oneByteFallsBack() {
    IncomingTextMessage message = prefixed(new byte[] {0x33});
    IncomingTextMessage result  = new MultipartSmsMessageHandler().processPotentialMultipartMessage(message);

    assertEquals(message.getMessageBody(), result.getMessageBody());
    assertFalse(result.isSecureMessage());
  }

  @Test
  public void shortMultipartFallsBack() {
    IncomingTextMessage message = prefixed(new byte[] {0x33, Conversions.intsToByteHighAndLow(0, 2)});
    IncomingTextMessage result  = new MultipartSmsMessageHandler().processPotentialMultipartMessage(message);

    assertEquals(message.getMessageBody(), result.getMessageBody());
    assertFalse(result.isSecureMessage());
  }

  @Test
  public void singlePartStillDecodes() {
    byte[] payload = {0x33, Conversions.intsToByteHighAndLow(0, 1), 0x0A, 0x0B};
    IncomingTextMessage result =
        new MultipartSmsMessageHandler().processPotentialMultipartMessage(prefixed(payload));

    assertTrue(result.isSecureMessage());
    assertEquals(Base64.encodeBytesWithoutPadding(new byte[] {0x33, 0x0A, 0x0B}), result.getMessageBody());
  }
}
