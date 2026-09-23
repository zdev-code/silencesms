package org.smssecure.smssecure.jobs.requirements;

import static org.assertj.core.api.Assertions.assertThat;

import android.app.Application;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class ServiceRequirementProviderTest {

  @Test
  @Config(sdk = {23, 27, 28, 30})
  public void constructorSupportsLegacyListenerSdks() {
    Context context = ApplicationProvider.getApplicationContext();

    assertThat(new ServiceRequirementProvider(context)).isNotNull();
  }

  @Test
  @Config(sdk = {31, 36})
  public void constructorSupportsTelephonyCallbackSdks() {
    Context context = ApplicationProvider.getApplicationContext();

    assertThat(new ServiceRequirementProvider(context)).isNotNull();
  }
}