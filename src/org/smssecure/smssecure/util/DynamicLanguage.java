package org.smssecure.smssecure.util;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.LocaleList;
import android.text.TextUtils;

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
    LocaleList locales = configuration.getLocales();
    if (locales != null && !locales.isEmpty()) {
      return locales.get(0);
    }
    Locale legacyLocale = configuration.locale;
    return legacyLocale != null ? legacyLocale : Locale.getDefault();
  }

  private static final class OverridePendingTransition {
    static void invoke(Activity activity) {
      activity.overridePendingTransition(0, 0);
    }
  }
}
