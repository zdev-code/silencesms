package org.smssecure.smssecure.util.dualsim;

import static org.assertj.core.api.Assertions.assertThat;

import android.app.Application;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.smssecure.smssecure.crypto.IdentityKeyUtil;
import org.smssecure.smssecure.crypto.storage.VendoredSessionStore;

import java.io.File;
import java.nio.file.Files;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 35)
public class DualSimUtilRobolectricTest {
  private Context context;
  private File sessions;

  @Before
  public void setUp() {
    context = ApplicationProvider.getApplicationContext();
    sessions = VendoredSessionStore.getSessionDirectory(context);
  }

  @After
  public void tearDown() {
    File[] files = sessions.listFiles();
    if (files != null) for (File file : files) file.delete();
  }

  @Test
  public void rerunDoesNotOverwriteMovedIdentityKeysWithMissingSource() {
    IdentityKeyUtil.save(context, IdentityKeyUtil.getIdentityPublicKeyDjbPref(4), "public");
    IdentityKeyUtil.save(context, IdentityKeyUtil.getIdentityPrivateKeyDjbPref(4), "private");

    DualSimUtil.moveIdentityKeysAndSessionsToSubscriptionId(context, -1, 4);
    DualSimUtil.moveIdentityKeysAndSessionsToSubscriptionId(context, -1, 4);

    assertThat(IdentityKeyUtil.retrieve(context, IdentityKeyUtil.getIdentityPublicKeyDjbPref(4)))
        .isEqualTo("public");
    assertThat(IdentityKeyUtil.retrieve(context, IdentityKeyUtil.getIdentityPrivateKeyDjbPref(4)))
        .isEqualTo("private");
  }

  @Test
  public void rerunDoesNotAppendSubscriptionSuffixTwice() throws Exception {
    Files.write(new File(sessions, "12").toPath(), new byte[]{1, 2, 3});

    DualSimUtil.moveIdentityKeysAndSessionsToSubscriptionId(context, -1, 4);
    DualSimUtil.moveIdentityKeysAndSessionsToSubscriptionId(context, -1, 4);

    assertThat(new File(sessions, "12.4")).exists();
    assertThat(new File(sessions, "12.4.4")).doesNotExist();
  }
}
