package org.smssecure.smssecure.util;

import android.content.res.Configuration;
import android.content.res.Resources;

import org.junit.Test;
import org.smssecure.smssecure.BaseUnitTest;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class DynamicThemeTest extends BaseUnitTest {

  @Test
  public void systemThemeFollowsNightConfiguration() {
    setTheme(DynamicTheme.SYSTEM, Configuration.UI_MODE_NIGHT_YES);
    assertTrue(DynamicTheme.isDarkTheme(context));

    setTheme(DynamicTheme.SYSTEM, Configuration.UI_MODE_NIGHT_NO);
    assertFalse(DynamicTheme.isDarkTheme(context));
  }

  @Test
  public void explicitThemeOverridesNightConfiguration() {
    setTheme(DynamicTheme.LIGHT, Configuration.UI_MODE_NIGHT_YES);
    assertFalse(DynamicTheme.isDarkTheme(context));

    setTheme(DynamicTheme.DARK, Configuration.UI_MODE_NIGHT_NO);
    assertTrue(DynamicTheme.isDarkTheme(context));
  }

  @Test
  public void missingPreferenceDefaultsToSystemTheme() {
    when(sharedPreferences.getString(SilencePreferences.THEME_PREF, DynamicTheme.SYSTEM))
        .thenReturn(DynamicTheme.SYSTEM);

    assertTrue(DynamicTheme.SYSTEM.equals(SilencePreferences.getTheme(context)));
  }

  private void setTheme(String theme, int nightMode) {
    Configuration configuration = new Configuration();
    configuration.uiMode = nightMode;
    Resources resources = mock(Resources.class);
    when(resources.getConfiguration()).thenReturn(configuration);
    when(context.getResources()).thenReturn(resources);
    when(sharedPreferences.getString(SilencePreferences.THEME_PREF, DynamicTheme.SYSTEM))
        .thenReturn(theme);
  }
}