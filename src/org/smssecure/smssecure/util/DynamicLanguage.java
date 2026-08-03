package org.smssecure.smssecure.util;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Build;
import android.os.LocaleList;
import android.text.TextUtils;

import androidx.core.os.ConfigurationCompat;

import java.util.Locale;

public class DynamicLanguage {

  private static final String DEFAULT = "zz";

  private Locale currentLocale;

  public void onCreate(Activity activity) {
    currentLocale = getSelectedLocale(activity);
    setContextLocale(activity, currentLocale);
  }

  public void onResume(Activity activity) {
    if (!currentLocale.equals(getSelectedLocale(activity))) {
      Intent intent = activity.getIntent();
      activity.finish();
      OverridePendingTransition.invoke(activity);
      activity.startActivity(intent);
      OverridePendingTransition.invoke(activity);
    }
  }

  public void updateServiceLocale(Service service) {
    currentLocale = getSelectedLocale(service);
    setContextLocale(service, currentLocale);
  }

  public Locale getCurrentLocale() {
    return currentLocale;
  }

  public static int getLayoutDirection(Context context) {
    Configuration configuration = context.getResources().getConfiguration();
    return configuration.getLayoutDirection();
  }

  @SuppressLint("AppBundleLocaleChanges")
  private static void setContextLocale(Context context, Locale selectedLocale) {
    Configuration configuration = context.getResources().getConfiguration();
    Locale current = getConfigurationLocale(configuration);

    if (!selectedLocale.equals(current)) {
      configuration.setLocale(selectedLocale);
      configuration.setLocales(new LocaleList(selectedLocale));
      configuration.setLayoutDirection(selectedLocale);
      context.createConfigurationContext(configuration);
    }
  }

  private static Locale getActivityLocale(Activity activity) {
    return getConfigurationLocale(activity.getResources().getConfiguration());
  }

  // new Locale(...) is deprecated in favour of Locale.of(...) (JDK 19+), but is retained to preserve
  // exact legacy language-code resolution (e.g. iw/in) that matches the values-* resource folders.
  @SuppressWarnings("deprecation")
  private static Locale getSelectedLocale(Context context) {
    String language[] = TextUtils.split(SilencePreferences.getLanguage(context), "-r");

    if (language[0].equals(DEFAULT)) {
      return Locale.getDefault();
    } else if (language.length == 2) {
      return new Locale(language[0], language[1]);
    } else {
      return new Locale(language[0]);
    }
  }

  private static Locale getConfigurationLocale(Configuration configuration) {
    return ConfigurationCompat.getLocales(configuration).get(0);
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
