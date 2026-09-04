package org.smssecure.smssecure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import android.content.Intent;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.ecc.Curve;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.domain.identity.ConflictIdentityStore;
import org.smssecure.smssecure.service.KeyCachingService;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.crypto.spec.SecretKeySpec;

import androidx.fragment.app.FragmentActivity;

@RunWith(RobolectricTestRunner.class)
public class VerifyIdentityFragmentTest {
  private ConflictIdentityStore store;
  private IdentityKey identityKey;
  private ActivityController<FragmentActivity> activityController;
  private FragmentActivity activity;

  @Before
  public void setUp() {
    KeyCachingService.primeMasterSecret(secret((byte) 1));
    store = ConflictIdentityStore.getInstance();
    store.clear();
    identityKey = new IdentityKey(Curve.generateKeyPair().getPublicKey());
    activityController = Robolectric.buildActivity(FragmentActivity.class).create();
    activity = activityController.get();
  }

  @After
  public void tearDown() {
    store.clear();
    activityController.destroy();
  }

  @Test
  public void conflictTokenIsConsumedOnceAndSensitiveStateClears() throws Exception {
    String token = store.put(VerifyIdentityFragment.CONFLICT_OWNER, 11L, 2, identityKey);
    VerifyIdentityFragment fragment = new VerifyIdentityFragment();
    fragment.setArguments(VerifyIdentityFragment.conflictArguments(token));

    attach(fragment);

    assertThat(field(fragment, "recipientId")).isEqualTo(11L);
    assertThat(field(fragment, "subscriptionId")).isEqualTo(2);
    assertThat(field(fragment, "injectedRemoteIdentity")).isSameAs(identityKey);
    assertThatThrownBy(() -> store.consume(token, VerifyIdentityFragment.CONFLICT_OWNER))
        .isInstanceOf(ConflictIdentityStore.InvalidPayloadException.class);

    fragment.clearSensitiveState();
    assertThat(field(fragment, "injectedRemoteIdentity")).isNull();
    assertThat(field(fragment, "invalidConflict")).isEqualTo(true);
  }

  @Test
  public void missingColdProcessTokenFailsClosed() throws Exception {
    VerifyIdentityFragment fragment = new VerifyIdentityFragment();
    fragment.setArguments(VerifyIdentityFragment.conflictArguments("missing-token"));

    attach(fragment);

    assertThat(field(fragment, "injectedRemoteIdentity")).isNull();
    assertThat(field(fragment, "invalidConflict")).isEqualTo(true);
  }

  @Test
  public void centralRelockClearsUnconsumedConflictEntry() throws Exception {
    String token = store.put(VerifyIdentityFragment.CONFLICT_OWNER, 11L, 2, identityKey);
    ServiceController<KeyCachingService> serviceController =
        Robolectric.buildService(KeyCachingService.class).create();

    serviceController.get().onStartCommand(
        new Intent(KeyCachingService.CLEAR_KEY_ACTION), 0, 1);

    assertThatThrownBy(() -> store.consume(token, VerifyIdentityFragment.CONFLICT_OWNER))
        .isInstanceOf(ConflictIdentityStore.InvalidPayloadException.class);
    serviceController.destroy();
  }

  @Test
  public void receiveKeyDialogDoesNotParcelIdentityMaterial() throws Exception {
    Path project = Path.of(System.getProperty("user.dir"));
    if (project.endsWith("app")) project = project.getParent();
    String source = new String(Files.readAllBytes(
      project.resolve("app/src/main/java/org/smssecure/smssecure/ReceiveKeyDialog.java")),
      StandardCharsets.UTF_8);

    assertThat(source)
        .contains("HostNavigationCommand.createConflictVerifyIdentityIntent")
        .doesNotContain("remote_identity")
        .doesNotContain("IdentityKeyParcelable")
        .doesNotContain("putExtra(");
  }

  private static Object field(VerifyIdentityFragment fragment, String name) throws Exception {
    Field field = VerifyIdentityFragment.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(fragment);
  }

  private void attach(VerifyIdentityFragment fragment) {
    activity.getSupportFragmentManager().beginTransaction().add(fragment, "verify-test").commitNow();
  }

  private static MasterSecret secret(byte value) {
    byte[] key = new byte[16];
    key[0] = value;
    return new MasterSecret(new SecretKeySpec(key, "AES"),
                            new SecretKeySpec(key, "HmacSHA1"));
  }
}