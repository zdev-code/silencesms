package org.smssecure.smssecure.domain.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.Test;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.domain.security.UnlockSession;

import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.spec.SecretKeySpec;

public class ConversationUnlockCapabilityTest {
  @Test
  public void usesCurrentEquivalentSecret() throws Exception {
    MasterSecret expected = secret((byte) 1);
    MasterSecret current = secret((byte) 1);
    ConversationUnlockCapability capability =
        new ConversationUnlockCapability(expected, () -> current);

    MasterSecret used = capability.use(value -> value);

    assertThat(used).isSameAs(current);
  }

  @Test
  public void rejectsAfterRelock() {
    MasterSecret expected = secret((byte) 1);
    AtomicReference<MasterSecret> current = new AtomicReference<>(secret((byte) 1));
    ConversationUnlockCapability capability =
        new ConversationUnlockCapability(expected, current::get);
    current.set(null);

    assertThatThrownBy(() -> capability.use(value -> value))
        .isInstanceOf(ConversationUnlockCapability.LockedException.class);
  }

  @Test
  public void rejectsReplacementSession() {
    MasterSecret expected = secret((byte) 1);
    ConversationUnlockCapability capability =
        new ConversationUnlockCapability(expected, () -> secret((byte) 2));

    assertThatThrownBy(() -> capability.use(value -> value))
        .isInstanceOf(ConversationUnlockCapability.LockedException.class);
  }

      @Test
      public void generationSessionRejectsRelock() {
      MasterSecret secret = secret((byte) 1);
      AtomicReference<UnlockSession.Snapshot> current =
        new AtomicReference<>(new UnlockSession.Snapshot(4L, secret));
      ConversationUnlockCapability capability = new ConversationUnlockCapability(
        new UnlockSession(4L, current::get));
      current.set(new UnlockSession.Snapshot(5L, null));

      assertThatThrownBy(() -> capability.use(value -> value))
        .isInstanceOf(ConversationUnlockCapability.LockedException.class);
      }

  private static MasterSecret secret(byte value) {
    byte[] encryption = new byte[16];
    byte[] mac = new byte[16];
    java.util.Arrays.fill(encryption, value);
    java.util.Arrays.fill(mac, value);
    return new MasterSecret(new SecretKeySpec(encryption, "AES"),
                            new SecretKeySpec(mac, "HmacSHA1"));
  }
}
