package org.smssecure.smssecure.domain.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.domain.security.UnlockSession;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.spec.SecretKeySpec;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class MediaPreviewDraftStoreTest {
  private static final String OWNER = "preview";

  @Test
  public void consumesPayloadOnceUnderCapturedGeneration() throws Exception {
    Fixture fixture = new Fixture();
    fixture.store.put(OWNER, Uri.parse("content://draft/image"), "image/png", 42L);

    MediaPreviewDraftStore.Payload payload = fixture.store.consume(OWNER);

    assertThat(payload.getUri()).isEqualTo(Uri.parse("content://draft/image"));
    assertThat(payload.getContentType()).isEqualTo("image/png");
    assertThat(payload.getSize()).isEqualTo(42L);
    assertThatThrownBy(() -> fixture.store.consume(OWNER))
        .isInstanceOf(MediaPreviewDraftStore.InvalidPayloadException.class);
  }

  @Test
  public void staleGenerationAndExpiredPayloadFailClosed() {
    Fixture stale = new Fixture();
    stale.store.put(OWNER, Uri.parse("content://draft/stale"), "image/png", 1L);
    stale.snapshot.set(new UnlockSession.Snapshot(8L, secret()));
    assertThatThrownBy(() -> stale.store.consume(OWNER))
        .isInstanceOf(MediaPreviewDraftStore.InvalidPayloadException.class);

    Fixture expired = new Fixture();
    expired.store.put(OWNER, Uri.parse("content://draft/expired"), "video/mp4", 2L);
    expired.now.set(151L);
    assertThatThrownBy(() -> expired.store.consume(OWNER))
        .isInstanceOf(MediaPreviewDraftStore.InvalidPayloadException.class);
  }

  @Test
  public void clearAndNewProcessStoreCannotRecoverDraftUri() {
    Fixture fixture = new Fixture();
    fixture.store.put(OWNER, Uri.parse("content://draft/secret"), "image/jpeg", 3L);
    fixture.store.clear();
    assertThatThrownBy(() -> fixture.store.consume(OWNER))
        .isInstanceOf(MediaPreviewDraftStore.InvalidPayloadException.class);

    MediaPreviewDraftStore recreated = fixture.newStore();
    assertThatThrownBy(() -> recreated.consume(OWNER))
        .isInstanceOf(MediaPreviewDraftStore.InvalidPayloadException.class);
  }

  private static MasterSecret secret() {
    byte[] key = new byte[16];
    return new MasterSecret(new SecretKeySpec(key, "AES"),
                            new SecretKeySpec(key, "HmacSHA1"));
  }

  private static final class Fixture {
    private final AtomicLong now = new AtomicLong(100L);
    private final AtomicReference<UnlockSession.Snapshot> snapshot =
        new AtomicReference<>(new UnlockSession.Snapshot(7L, secret()));
    private final MediaPreviewDraftStore store = newStore();

    private MediaPreviewDraftStore newStore() {
      return new MediaPreviewDraftStore(now::get,
          () -> new UnlockSession(snapshot.get().getGeneration(), snapshot::get), 50L);
    }
  }
}