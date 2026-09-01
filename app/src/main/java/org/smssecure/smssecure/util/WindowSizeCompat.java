package org.smssecure.smssecure.util;

import android.app.Activity;
import android.content.Context;
import android.graphics.Point;
import android.os.Build;
import android.view.Display;
import android.view.WindowManager;

public final class WindowSizeCompat {

  private WindowSizeCompat() {}

  public static Point getWindowSize(Activity activity) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      android.graphics.Rect bounds = activity.getWindowManager().getCurrentWindowMetrics().getBounds();
      return new Point(bounds.width(), bounds.height());
    }

    return getLegacyDisplaySize(activity);
  }

  public static int getWindowWidth(Context context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      WindowManager windowManager = context.getSystemService(WindowManager.class);
      return windowManager.getCurrentWindowMetrics().getBounds().width();
    }

    return getLegacyDisplaySize(context).x;
  }

  @SuppressWarnings("deprecation")
  private static Point getLegacyDisplaySize(Context context) {
    Display display = ServiceUtil.getWindowManager(context).getDefaultDisplay();
    Point size = new Point();
    display.getSize(size);
    return size;
  }
}