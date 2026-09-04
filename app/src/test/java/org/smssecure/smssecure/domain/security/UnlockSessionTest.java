package org.smssecure.smssecure.domain.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.Test;
import org.smssecure.smssecure.crypto.MasterSecret;

import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.spec.SecretKeySpec;

public class UnlockSessionTest {
  @Test
  public void usesCurrentSecretFromCapturedGeneration() throws Exception {
    MasterSecret secret = secret((byte) 1);
    AtomicReference<UnlockSession.Snapshot> current =
        new AtomicReference<>(new UnlockSession.Snapshot(7L, secret));
    UnlockSession session = new UnlockSession(7L, current::get);

    MasterSecret used = session.use(value -> value);

    assertThat(used).isSameAs(secret);
  }

  @Test
  public void rejectsRelockEvenIfGenerationIsUnchanged() {
    AtomicReference<UnlockSession.Snapshot> current =
        new AtomicReference<>(new UnlockSession.Snapshot(7L, secret((byte) 1)));
    UnlockSession session = new UnlockSession(7L, current::get);
    current.set(new UnlockSession.Snapshot(7L, null));

    assertThatThrownBy(() -> session.use(value -> value))
        .isInstanceOf(UnlockSession.LockedException.class);
  }

  @Test
  public void rejectsReplacementUnlockGeneration() {
    AtomicReference<UnlockSession.Snapshot> current =
        new AtomicReference<>(new UnlockSession.Snapshot(7L, secret((byte) 1)));
    UnlockSession session = new UnlockSession(7L, current::get);
    current.set(new UnlockSession.Snapshot(8L, secret((byte) 1)));

    assertThatThrownBy(() -> session.use(value -> value))
        .isInstanceOf(UnlockSession.LockedException.class);
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