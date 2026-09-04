package org.smssecure.smssecure.domain.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.Before;
import org.junit.Test;
import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.ecc.Curve;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.domain.security.UnlockSession;

import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.spec.SecretKeySpec;

public class ConflictIdentityStoreTest {
  private long now;
  private AtomicReference<UnlockSession.Snapshot> snapshot;
  private ConflictIdentityStore store;
  private IdentityKey identityKey;

  @Before
  public void setUp() {
    snapshot = new AtomicReference<>(new UnlockSession.Snapshot(7L, secret((byte) 1)));
    store = new ConflictIdentityStore(() -> now, snapshot::get, 100L);
    identityKey = new IdentityKey(Curve.generateKeyPair().getPublicKey());
  }

  @Test
  public void payloadIsBoundToOwnerGenerationAndConsumedOnce() throws Exception {
    String token = store.put("verify-identity", 11L, 2, identityKey);

    ConflictIdentityStore.Payload payload = store.consume(token, "verify-identity");

    assertThat(payload.getRecipientId()).isEqualTo(11L);
    assertThat(payload.getSubscriptionId()).isEqualTo(2);
    assertThat(payload.getIdentityKey()).isSameAs(identityKey);
    assertThatThrownBy(() -> store.consume(token, "verify-identity"))
        .isInstanceOf(ConflictIdentityStore.InvalidPayloadException.class);
  }

  @Test
  public void wrongOwnerConsumesAndRejectsEntry() throws Exception {
    String token = store.put("verify-identity", 11L, 2, identityKey);

    assertThatThrownBy(() -> store.consume(token, "other"))
        .isInstanceOf(ConflictIdentityStore.InvalidPayloadException.class);
    assertThatThrownBy(() -> store.consume(token, "verify-identity"))
        .isInstanceOf(ConflictIdentityStore.InvalidPayloadException.class);
  }

  @Test
  public void staleGenerationConsumesAndRejectsEntry() throws Exception {
    String token = store.put("verify-identity", 11L, 2, identityKey);
    snapshot.set(new UnlockSession.Snapshot(8L, secret((byte) 2)));

    assertThatThrownBy(() -> store.consume(token, "verify-identity"))
        .isInstanceOf(ConflictIdentityStore.InvalidPayloadException.class);
    assertThatThrownBy(() -> store.consume(token, "verify-identity"))
        .isInstanceOf(ConflictIdentityStore.InvalidPayloadException.class);
  }

  @Test
  public void expiryConsumesAndRejectsEntry() throws Exception {
    String token = store.put("verify-identity", 11L, 2, identityKey);
    now = 100L;

    assertThatThrownBy(() -> store.consume(token, "verify-identity"))
        .isInstanceOf(ConflictIdentityStore.InvalidPayloadException.class);
  }

  @Test
  public void tamperedTokenDoesNotReplaceOrExposeEntry() throws Exception {
    String token = store.put("verify-identity", 11L, 2, identityKey);

    assertThatThrownBy(() -> store.consume(token + "x", "verify-identity"))
        .isInstanceOf(ConflictIdentityStore.InvalidPayloadException.class);
    assertThat(store.consume(token, "verify-identity").getIdentityKey()).isSameAs(identityKey);
  }

  @Test
  public void clearRemovesEntryAndLockedCreationFailsClosed() throws Exception {
    String token = store.put("verify-identity", 11L, 2, identityKey);
    store.clear();

    assertThatThrownBy(() -> store.consume(token, "verify-identity"))
        .isInstanceOf(ConflictIdentityStore.InvalidPayloadException.class);
    snapshot.set(new UnlockSession.Snapshot(8L, null));
    assertThatThrownBy(() -> store.put("verify-identity", 11L, 2, identityKey))
        .isInstanceOf(ConflictIdentityStore.InvalidPayloadException.class);
  }

  private static MasterSecret secret(byte value) {
    byte[] key = new byte[16];
    key[0] = value;
    return new MasterSecret(new SecretKeySpec(key, "AES"),
                            new SecretKeySpec(key, "HmacSHA1"));
  }
}