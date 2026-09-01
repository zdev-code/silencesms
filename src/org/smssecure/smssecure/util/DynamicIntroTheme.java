package org.smssecure.smssecure.util;

import android.app.Activity;

import org.smssecure.smssecure.R;

public class DynamicIntroTheme extends DynamicTheme {
  @Override
  protected int getSelectedTheme(Activity activity) {
    if (isDarkTheme(activity)) return R.style.Silence_DarkIntroTheme;

    return R.style.Silence_LightIntroTheme;
  }
}
