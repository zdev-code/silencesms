package org.smssecure.smssecure;

import android.app.ActivityOptions;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.app.ActivityOptionsCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.appcompat.app.AppCompatActivity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AnimationUtils;

import org.smssecure.smssecure.util.SilencePreferences;

public abstract class BaseActionBarActivity extends AppCompatActivity {
  private static final String TAG = BaseActionBarActivity.class.getSimpleName();

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
  }

  @Override
  protected void onResume() {
    super.onResume();
    initializeScreenshotSecurity();
  }

  @Override
  public void onContentChanged() {
    super.onContentChanged();
    applyWindowInsets();
  }

  /**
   * Pads the activity content with system-bar and display-cutout insets so that nothing is hidden
   * behind the status/navigation bars under the edge-to-edge layout that Android 15+ (targetSdk 35+)
   * enforces. On older releases the reported insets are zero, so this is a no-op there. Fullscreen
   * activities (e.g. media viewers) override {@link #applyDefaultWindowInsets()} to opt out.
   */
  private void applyWindowInsets() {
    if (!applyDefaultWindowInsets()) return;

    final View content = findViewById(android.R.id.content);
    if (content == null) return;

    ViewCompat.setOnApplyWindowInsetsListener(content, (view, windowInsets) -> {
      Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() |
                                           WindowInsetsCompat.Type.displayCutout());
      view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
      return windowInsets;
    });
    ViewCompat.requestApplyInsets(content);
  }

  /**
   * Whether {@link #applyWindowInsets()} should pad this activity's content with system-bar insets.
   * Fullscreen activities should override this to return {@code false} and handle insets themselves.
   */
  protected boolean applyDefaultWindowInsets() {
    return true;
  }

  @Override
  public boolean onKeyDown(int keyCode, KeyEvent event) {
    return (keyCode == KeyEvent.KEYCODE_MENU && BaseActivity.isMenuWorkaroundRequired()) || super.onKeyDown(keyCode, event);
  }

  @Override
  public boolean onKeyUp(int keyCode, @NonNull KeyEvent event) {
    if (keyCode == KeyEvent.KEYCODE_MENU && BaseActivity.isMenuWorkaroundRequired()) {
      openOptionsMenu();
      return true;
    }
    return super.onKeyUp(keyCode, event);
  }

  private void initializeScreenshotSecurity() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH &&
            SilencePreferences.isScreenSecurityEnabled(this))
    {
      getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    } else {
      getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }
  }

  protected void startActivitySceneTransition(Intent intent, View sharedView, String transitionName) {
    Bundle bundle = ActivityOptionsCompat.makeSceneTransitionAnimation(this, sharedView, transitionName)
                                         .toBundle();
    ActivityCompat.startActivity(this, intent, bundle);
  }
}
