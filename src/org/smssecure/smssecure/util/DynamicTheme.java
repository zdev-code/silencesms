package org.smssecure.smssecure.util;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;

import org.smssecure.smssecure.R;

public class DynamicTheme {

  public static final String DARK  = "dark";
  public static final String LIGHT = "light";

  private int currentTheme;

  public void onCreate(Activity activity) {
    currentTheme = getSelectedTheme(activity);
    activity.setTheme(currentTheme);
  }

  public void onResume(Activity activity) {
    if (currentTheme != getSelectedTheme(activity)) {
      Intent intent = activity.getIntent();
      activity.finish();
      OverridePendingTransition.invoke(activity);
      activity.startActivity(intent);
      OverridePendingTransition.invoke(activity);
    }
  }

  protected int getSelectedTheme(Activity activity) {
    String theme = SilencePreferences.getTheme(activity);

    if (theme.equals(DARK)) return R.style.Silence_DarkTheme;

    return R.style.Silence_LightTheme;
  }

  private static final class OverridePendingTransition {
    @SuppressWarnings("deprecation")
    static void invoke(Activity activity) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0);
        activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0);
      } else {
        activity.overridePendingTransition(0, 0);
      }
    }
  }
}
