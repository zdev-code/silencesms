package org.smssecure.smssecure.domain.upgrade;

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

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 35)
public class UpgradeOperationStoreTest {
  private Context context;
  private UpgradeOperationStore.PreferencesStorage storage;

  @Before
  public void setUp() {
    context = ApplicationProvider.getApplicationContext();
    storage = new UpgradeOperationStore.PreferencesStorage(context);
    storage.clear();
  }

  @After
  public void tearDown() {
    storage.clear();
  }

  @Test
  public void recordSurvivesStorageReconstructionAndCanBeClaimed() {
    storage.write(new UpgradeOperationStore.Record(
        143, 216, UpgradeOperationStore.Stage.MULTI_SIM));

    UpgradeOperationStore.Record restored =
        new UpgradeOperationStore.PreferencesStorage(context).read();

    assertThat(restored).isNotNull();
    assertThat(restored.getFromVersion()).isEqualTo(143);
    assertThat(restored.getTargetVersion()).isEqualTo(216);
    assertThat(restored.getStage()).isEqualTo(UpgradeOperationStore.Stage.MULTI_SIM);

    new UpgradeOperationStore.PreferencesStorage(context).clear();
    assertThat(storage.read()).isNull();
  }
}
