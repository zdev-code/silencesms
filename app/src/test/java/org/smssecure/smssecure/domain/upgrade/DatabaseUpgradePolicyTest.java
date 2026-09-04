package org.smssecure.smssecure.domain.upgrade;

import static org.assertj.core.api.Assertions.assertThat;

import android.app.Application;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.smssecure.smssecure.util.SilencePreferences;
import org.smssecure.smssecure.util.Util;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 35)
public class DatabaseUpgradePolicyTest {
  @Test
  public void preservesExactUpgradeThresholds() {
    assertThat(DatabaseUpgradePolicy.ASK_FOR_SIM_CARD_VERSION).isEqualTo(143);
    assertThat(DatabaseUpgradePolicy.MULTI_SIM_MULTI_KEYS_VERSION).isEqualTo(200);
    assertThat(DatabaseUpgradePolicy.MERGE_EQUIVALENT_PHONE_THREADS_VERSION).isEqualTo(216);
  }

  @Test
  public void needsUpgradeOnlyWhenAnUpgradeThresholdWasNotSeen() {
    assertThat(DatabaseUpgradePolicy.needsUpgrade(142, 143)).isTrue();
    assertThat(DatabaseUpgradePolicy.needsUpgrade(143, 199)).isTrue();
    assertThat(DatabaseUpgradePolicy.needsUpgrade(199, 200)).isTrue();
    assertThat(DatabaseUpgradePolicy.needsUpgrade(200, 215)).isTrue();
    assertThat(DatabaseUpgradePolicy.needsUpgrade(215, 216)).isTrue();
    assertThat(DatabaseUpgradePolicy.needsUpgrade(216, 217)).isFalse();
    assertThat(DatabaseUpgradePolicy.needsUpgrade(217, 217)).isFalse();
    assertThat(DatabaseUpgradePolicy.needsUpgrade(218, 217)).isFalse();
  }

  @Test
  public void isUpdateComparesPersistedAndCurrentApplicationVersions() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    int currentVersion = Util.getCurrentApkReleaseVersion(context);

    SilencePreferences.setLastVersionCode(context, currentVersion - 1);
    assertThat(DatabaseUpgradePolicy.isUpdate(context)).isTrue();
    SilencePreferences.setLastVersionCode(context, currentVersion);
    assertThat(DatabaseUpgradePolicy.isUpdate(context)).isFalse();
  }
}