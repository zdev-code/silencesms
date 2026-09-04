package org.smssecure.smssecure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.domain.security.UnlockSession;
import org.smssecure.smssecure.util.Base64;

import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.spec.SecretKeySpec;

public class IdentityScanBindingTest {
  @Test
  public void verifiesOnlyMatchingBoundIdentityAndConsumesOnce() {
    byte[] identity = new byte[] {1, 2, 3};
    IdentityScanBinding binding = new IdentityScanBinding(session(), 7L, 3, identity);
    identity[0] = 9;

    assertThat(binding.consume(7L, 3, new byte[] {1, 2, 3},
                               Base64.encodeBytes(new byte[] {1, 2, 3})))
        .isEqualTo(IdentityScanBinding.Result.VERIFIED);
    assertThat(binding.consume(7L, 3, new byte[] {1, 2, 3},
                               Base64.encodeBytes(new byte[] {1, 2, 3})))
        .isEqualTo(IdentityScanBinding.Result.REJECTED);
  }

  @Test
  public void rejectsCrossRecipientSubscriptionAndIdentityResults() {
    assertThat(new IdentityScanBinding(session(), 7L, 3, new byte[] {1})
        .consume(8L, 3, new byte[] {1}, Base64.encodeBytes(new byte[] {1})))
        .isEqualTo(IdentityScanBinding.Result.REJECTED);
    assertThat(new IdentityScanBinding(session(), 7L, 3, new byte[] {1})
        .consume(7L, 4, new byte[] {1}, Base64.encodeBytes(new byte[] {1})))
        .isEqualTo(IdentityScanBinding.Result.REJECTED);
    assertThat(new IdentityScanBinding(session(), 7L, 3, new byte[] {1})
        .consume(7L, 3, new byte[] {2}, Base64.encodeBytes(new byte[] {1})))
        .isEqualTo(IdentityScanBinding.Result.REJECTED);
  }

  @Test
  public void rejectsResultAfterRelock() {
    AtomicReference<UnlockSession.Snapshot> snapshot =
        new AtomicReference<>(new UnlockSession.Snapshot(4L, secret()));
    IdentityScanBinding binding = new IdentityScanBinding(
        new UnlockSession(4L, snapshot::get), 7L, 3, new byte[] {1});
    snapshot.set(new UnlockSession.Snapshot(5L, secret()));

    assertThat(binding.consume(7L, 3, new byte[] {1}, Base64.encodeBytes(new byte[] {1})))
        .isEqualTo(IdentityScanBinding.Result.REJECTED);
  }

  private static UnlockSession session() {
    MasterSecret secret = secret();
    return new UnlockSession(4L, () -> new UnlockSession.Snapshot(4L, secret));
  }

  private static MasterSecret secret() {
    return new MasterSecret(new SecretKeySpec(new byte[16], "AES"),
                            new SecretKeySpec(new byte[16], "HmacSHA1"));
  }
}