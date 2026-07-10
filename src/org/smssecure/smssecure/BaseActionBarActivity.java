package org.smssecure.smssecure;

import android.app.ActivityOptions;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.app.ActivityOptionsCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.appcompat.app.AppCompatActivity;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.AnimationUtils;
import android.widget.FrameLayout;

import org.smssecure.smssecure.util.SilencePreferences;

public abstract class BaseActionBarActivity extends AppCompatActivity {
  private static final String TAG = BaseActionBarActivity.class.getSimpleName();

  private View statusBarScrim;
  private View navigationBarScrim;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
  }

  @Override
  protected void onResume() {
    super.onResume();
    initializeScreenshotSecurity();
    // Some activities add a fragment straight into android.R.id.content and never call
    // setContentView(), so onContentChanged() never fires for them. Apply here too; the work is
    // idempotent (absolute padding, create-once scrims).
    applyWindowInsets();
  }

  @Override
  public void onContentChanged() {
    super.onContentChanged();
    applyWindowInsets();
  }

  /**
   * Makes this activity render correctly under the edge-to-edge layout that Android 15+
   * (targetSdk 35+) enforces, where content is drawn behind the status and navigation bars and
   * {@code android:statusBarColor}/{@code android:navigationBarColor} are ignored.
   *
   * <p>Two AppCompat decor layouts need different handling:
   * <ul>
   *   <li><b>Theme-provided action bar</b> (the {@code action_bar_container} exists): its
   *       {@code ActionBarOverlayLayout} root positions the action bar at the very top and ignores
   *       padding, so we pad the action-bar container instead. Its coloured background then fills the
   *       status-bar region and the content is laid out below it. Content only needs bottom/side
   *       padding.</li>
   *   <li><b>Toolbar in the layout</b> (no {@code action_bar_container}): the toolbar lives inside
   *       {@code android.R.id.content}, so padding the content view drops it below the status bar.</li>
   * </ul>
   *
   * <p>The navigation-bar region (and the status-bar region when there is no action bar) is repainted
   * with the colours the theme declares for the system bars, and the bar icon (light/dark) appearance
   * is taken from the theme. On API &lt; 35 the decor still fits system windows, so the reported
   * insets are zero and this is a no-op. Fullscreen activities (e.g. media viewers) override
   * {@link #applyDefaultWindowInsets()} to opt out entirely.
   */
  private void applyWindowInsets() {
    if (!applyDefaultWindowInsets()) return;

    final View content = findViewById(android.R.id.content);
    if (content == null) return;

    // Attach to the DecorView: it is where window-inset dispatch begins and always invokes the
    // listener. AppCompat's ActionBarOverlayLayout (parent of the content on theme-action-bar
    // screens) consumes insets via its legacy fitSystemWindows path, so a listener on the content or
    // on action_bar_root never receives them. From the DecorView we pad the specific views directly
    // and consume, so nothing downstream re-applies the insets.
    final View decorView          = getWindow().getDecorView();
    final View actionBarContainer = findViewById(androidx.appcompat.R.id.action_bar_container);

    final int statusBarColor     = resolveThemeColor(android.R.attr.statusBarColor, Color.TRANSPARENT);
    final int navigationBarColor = resolveThemeColor(android.R.attr.navigationBarColor, Color.TRANSPARENT);
    ensureBarScrims(statusBarColor, navigationBarColor);

    ViewCompat.setOnApplyWindowInsetsListener(decorView, (view, windowInsets) -> {
      Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() |
                                           WindowInsetsCompat.Type.displayCutout());
      Insets ime  = windowInsets.getInsets(WindowInsetsCompat.Type.ime());

      // When the soft keyboard is open, pad the content up by the IME height (edge-to-edge windows
      // are not resized for the keyboard) so the text input rises above it. Use the larger of the
      // navigation-bar and IME insets so we never double-count.
      int contentBottom = Math.max(bars.bottom, ime.bottom);

      if (actionBarContainer != null) {
        // Theme action bar: pad the action-bar container so the toolbar drops below the status bar
        // (its background fills that region). The action bar overlay layout then lays the content
        // out below it, so the content only needs bottom/side padding — unless the action bar
        // overlays the content, in which case the content also needs the top inset.
        actionBarContainer.setPadding(bars.left, bars.top, bars.right, 0);
        if (isActionBarOverlay()) {
          // Overlay action bar: the content is drawn behind a transparent action bar and AppCompat
          // does not manage the content view's insets in overlay mode, so padding the content view
          // itself sticks. Give it the top inset so its own content clears the status bar.
          content.setPadding(bars.left, bars.top, bars.right, contentBottom);
        } else {
          // Non-overlay theme action bar: ActionBarOverlayLayout manages the content view's own
          // padding (and would overwrite ours), and it lays the content frame out full-screen with
          // the action bar drawn over the content's top. Pad the content's children (the fragment /
          // root views, which AOL does not touch) so they clear the whole padded action bar at the
          // top and the navigation bar at the bottom. Pad every child so fragments that are being
          // swapped in/out during a transition are all handled.
          int childTop = bars.top + resolveActionBarSize();
          if (content instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) content;
            for (int i = 0; i < group.getChildCount(); i++) {
              group.getChildAt(i).setPadding(bars.left, childTop, bars.right, contentBottom);
            }
          } else {
            content.setPadding(bars.left, childTop, bars.right, contentBottom);
          }
        }
        setScrimHeight(statusBarScrim, 0);
      } else {
        // Toolbar (or plain) content: pad the content view for every edge.
        content.setPadding(bars.left, bars.top, bars.right, contentBottom);
        setScrimHeight(statusBarScrim, bars.top);
      }
      setScrimHeight(navigationBarScrim, bars.bottom);
      return WindowInsetsCompat.CONSUMED;
    });
    ViewCompat.requestApplyInsets(decorView);

    // Fragments swapped into android.R.id.content (e.g. preference sub-screens) add a fresh child
    // view that has no padding, and a fragment transaction does not trigger a new inset dispatch.
    // Re-request insets whenever a child is added so the new fragment gets padded too.
    if (content instanceof ViewGroup) {
      ((ViewGroup) content).setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
        @Override public void onChildViewAdded(View parent, View child) {
          ViewCompat.requestApplyInsets(decorView);
        }
        @Override public void onChildViewRemoved(View parent, View child) {}
      });
    }

    applyBarIconAppearance();
  }

  /**
   * Whether {@link #applyWindowInsets()} should manage this activity's system-bar insets.
   * Fullscreen activities should override this to return {@code false} and handle insets themselves.
   */
  protected boolean applyDefaultWindowInsets() {
    return true;
  }

  /**
   * Whether this activity uses an overlay action bar ({@code FEATURE_ACTION_BAR_OVERLAY}), where the
   * content is drawn behind the action bar. Such activities need the top inset applied to their
   * content as well; the default (non-overlay) case does not.
   */
  protected boolean isActionBarOverlay() {
    return false;
  }

  private void ensureBarScrims(int statusBarColor, int navigationBarColor) {
    final View decorView = getWindow().getDecorView();
    if (!(decorView instanceof ViewGroup)) return;
    final ViewGroup decor = (ViewGroup) decorView;

    if (statusBarScrim == null) {
      statusBarScrim = new View(this);
      statusBarScrim.setVisibility(View.GONE);
      decor.addView(statusBarScrim, new FrameLayout.LayoutParams(
          FrameLayout.LayoutParams.MATCH_PARENT, 0, Gravity.TOP));
    }
    statusBarScrim.setBackgroundColor(statusBarColor);

    if (navigationBarScrim == null) {
      navigationBarScrim = new View(this);
      navigationBarScrim.setVisibility(View.GONE);
      decor.addView(navigationBarScrim, new FrameLayout.LayoutParams(
          FrameLayout.LayoutParams.MATCH_PARENT, 0, Gravity.BOTTOM));
    }
    navigationBarScrim.setBackgroundColor(navigationBarColor);
  }

  private static void setScrimHeight(View scrim, int height) {
    if (scrim == null) return;
    ViewGroup.LayoutParams params = scrim.getLayoutParams();
    if (params.height != height) {
      params.height = height;
      scrim.setLayoutParams(params);
    }
    scrim.setVisibility(height > 0 ? View.VISIBLE : View.GONE);
  }

  private void applyBarIconAppearance() {
    WindowInsetsControllerCompat controller =
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
    controller.setAppearanceLightStatusBars(resolveThemeBool(android.R.attr.windowLightStatusBar, false));
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
      controller.setAppearanceLightNavigationBars(resolveThemeBool(android.R.attr.windowLightNavigationBar, false));
    }
  }

  private int resolveThemeColor(int attr, int fallback) {
    TypedValue value = new TypedValue();
    if (getTheme().resolveAttribute(attr, value, true)) {
      if (value.resourceId != 0) return ContextCompat.getColor(this, value.resourceId);
      return value.data;
    }
    return fallback;
  }

  /** Resolves the theme's action-bar height in pixels (e.g. {@code ?attr/actionBarSize}). */
  private int resolveActionBarSize() {
    TypedValue value = new TypedValue();
    if (getTheme().resolveAttribute(androidx.appcompat.R.attr.actionBarSize, value, true)) {
      return TypedValue.complexToDimensionPixelSize(value.data, getResources().getDisplayMetrics());
    }
    return 0;
  }

  private boolean resolveThemeBool(int attr, boolean fallback) {
    TypedValue value = new TypedValue();
    if (getTheme().resolveAttribute(attr, value, true)) return value.data != 0;
    return fallback;
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
