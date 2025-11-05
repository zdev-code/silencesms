package org.smssecure.smssecure.crypto;

import org.junit.Before;
import org.junit.Test;
import org.smssecure.smssecure.BaseUnitTest;
import org.whispersystems.libsignal.InvalidMessageException;

public class MasterCipherTest extends BaseUnitTest {
  private MasterCipher masterCipher;

  @Before
  @Override
  public void setUp() throws Exception {
    super.setUp();
    masterCipher = new MasterCipher(masterSecret);
  }

  @Test(expected = InvalidMessageException.class)
  public void testEncryptBytesWithZeroBody() throws Exception {
    masterCipher.decryptBytes(new byte[]{});
  }
}
