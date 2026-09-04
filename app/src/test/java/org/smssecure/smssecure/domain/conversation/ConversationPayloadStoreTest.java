package org.smssecure.smssecure.domain.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import android.net.Uri;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.service.KeyCachingService;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.spec.SecretKeySpec;

@RunWith(RobolectricTestRunner.class)
public class ConversationPayloadStoreTest {
  private long now;
  private ConversationPayloadStore store;
  private AtomicInteger cleanups;
  private AtomicReference<org.smssecure.smssecure.domain.security.UnlockSession.Snapshot> snapshot;

  @Before
  public void setUp() {
    KeyCachingService.primeMasterSecret(secret((byte) 1));
    snapshot = new AtomicReference<>(KeyCachingService.getSecretSnapshot());
    store = new ConversationPayloadStore(() -> now, snapshot::get, 100L);
    cleanups = new AtomicInteger();
  }

  @After
  public void tearDown() {
    store.clear();
  }

  @Test
  public void payloadIsOwnerBoundAndConsumedOnce() throws Exception {
    ConversationPayload payload = payload();
    String token = store.put("conversation", payload, cleanups::incrementAndGet);

    assertThat(store.consume(token, "conversation")).isSameAs(payload);
    assertThatThrownBy(() -> store.consume(token, "conversation"))
        .isInstanceOf(ConversationPayloadStore.InvalidPayloadException.class);
    assertThat(cleanups).hasValue(0);
  }

  @Test
  public void wrongOwnerDiscardsPayload() {
    String token = store.put("conversation", payload(), cleanups::incrementAndGet);

    assertThatThrownBy(() -> store.consume(token, "details"))
        .isInstanceOf(ConversationPayloadStore.InvalidPayloadException.class);
    assertThat(cleanups).hasValue(1);
  }

  @Test
  public void expiryDiscardsPayload() {
    String token = store.put("conversation", payload(), cleanups::incrementAndGet);
    now = 101L;

    assertThatThrownBy(() -> store.consume(token, "conversation"))
        .isInstanceOf(ConversationPayloadStore.InvalidPayloadException.class);
    assertThat(cleanups).hasValue(1);
  }

  @Test
  public void relockGenerationDiscardsPayload() {
    String token = store.put("conversation", payload(), cleanups::incrementAndGet);
    snapshot.set(new org.smssecure.smssecure.domain.security.UnlockSession.Snapshot(
      snapshot.get().getGeneration() + 1L, secret((byte) 2)));

    assertThatThrownBy(() -> store.consume(token, "conversation"))
        .isInstanceOf(ConversationPayloadStore.InvalidPayloadException.class);
    assertThat(cleanups).hasValue(1);
  }

  @Test
  public void retargetPreservesPayloadCleanupExpiryAndGeneration() throws Exception {
    String token = store.put("selector", payload(), cleanups::incrementAndGet);

    store.retarget(token, "selector", "conversation", 19L, new long[] {8L}, 2);
    ConversationPayload retargeted = store.consume(token, "conversation");

    assertThat(retargeted.getThreadId()).isEqualTo(19L);
    assertThat(retargeted.getRecipientIds()).containsExactly(8L);
    assertThat(retargeted.getDistributionType()).isEqualTo(2);
    assertThat(retargeted.getText()).isEqualTo("secret");
    assertThat(retargeted.getMedia()).isEqualTo(Uri.parse("content://local/part"));
    assertThat(cleanups).hasValue(0);
  }

  @Test
  public void retargetRejectsWrongOwnerAndRunsCleanup() {
    String token = store.put("selector", payload(), cleanups::incrementAndGet);

    assertThatThrownBy(() ->
        store.retarget(token, "other", "conversation", 19L, new long[] {8L}, 2))
        .isInstanceOf(ConversationPayloadStore.InvalidPayloadException.class);
    assertThat(cleanups).hasValue(1);
  }

  @Test
  public void validationFailsClosedForMissingColdProcessToken() {
    assertThatThrownBy(() -> store.requireValid("missing", "selector"))
        .isInstanceOf(ConversationPayloadStore.InvalidPayloadException.class);
  }

  @Test
  public void lockedPayloadBindsOnFirstAuthenticatedHostValidation() throws Exception {
    snapshot.set(new org.smssecure.smssecure.domain.security.UnlockSession.Snapshot(4L, null));
    String token = store.put("selector", payload(), cleanups::incrementAndGet);
    snapshot.set(new org.smssecure.smssecure.domain.security.UnlockSession.Snapshot(
        5L, secret((byte) 3)));

    store.requireValid(token, "selector");
    snapshot.set(new org.smssecure.smssecure.domain.security.UnlockSession.Snapshot(
        6L, secret((byte) 4)));

    assertThatThrownBy(() -> store.inspect(token, "selector"))
        .isInstanceOf(ConversationPayloadStore.InvalidPayloadException.class);
    assertThat(cleanups).hasValue(1);
  }

  @Test
  public void mediaReplacementTransfersCleanupAndPreservesRoutingFields() throws Exception {
    String token = store.put("selector", payload(), cleanups::incrementAndGet);
    AtomicInteger replacementCleanups = new AtomicInteger();
    Uri replacement = Uri.parse("content://org.smssecure.smssecure/capture/9");

    store.replaceMedia(token, "selector", replacement, "video/mp4",
                       replacementCleanups::incrementAndGet);
    ConversationPayload inspected = store.inspect(token, "selector");

    assertThat(cleanups).hasValue(1);
    assertThat(inspected.getThreadId()).isEqualTo(7L);
    assertThat(inspected.getRecipientIds()).containsExactly(3L);
    assertThat(inspected.getText()).isEqualTo("secret");
    assertThat(inspected.getMedia()).isEqualTo(replacement);
    store.discard(token);
    assertThat(replacementCleanups).hasValue(1);
  }

  private static ConversationPayload payload() {
    return new ConversationPayload(7L, new long[] {3L}, 0, "secret",
                                   Uri.parse("content://local/part"), "image/png");
  }

  private static MasterSecret secret(byte value) {
    byte[] key = new byte[16];
    key[0] = value;
    return new MasterSecret(new SecretKeySpec(key, "AES"),
                            new SecretKeySpec(key, "HmacSHA1"));
  }
}