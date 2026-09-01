package org.smssecure.smssecure.util;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Build;

import org.smssecure.smssecure.R;

public class DynamicTheme {

  public static final String DARK  = "dark";
  public static final String LIGHT = "light";
  public static final String SYSTEM = "system";

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
    if (isDarkTheme(activity)) return R.style.Silence_DarkTheme;

    return R.style.Silence_LightTheme;
  }

  public static boolean isDarkTheme(Context context) {
    String theme = SilencePreferences.getTheme(context);
    if (DARK.equals(theme)) return true;
    if (LIGHT.equals(theme)) return false;

    int nightMode = context.getResources().getConfiguration().uiMode &
                    Configuration.UI_MODE_NIGHT_MASK;
    return nightMode == Configuration.UI_MODE_NIGHT_YES;
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
