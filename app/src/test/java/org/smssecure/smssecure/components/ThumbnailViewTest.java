package org.smssecure.smssecure.components;

import static org.assertj.core.api.Assertions.assertThat;

import android.app.Activity;
import android.content.ContextWrapper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;

@RunWith(RobolectricTestRunner.class)
public class ThumbnailViewTest {
  @Test
  public void wrappedDestroyedActivityIsNotAValidGlideContext() {
    ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup();
    Activity activity = controller.get();
    ContextWrapper wrapped = new ContextWrapper(new ContextWrapper(activity));

    assertThat(ThumbnailView.isContextValid(wrapped)).isTrue();

    controller.destroy();

    assertThat(ThumbnailView.isContextValid(wrapped)).isFalse();
  }
}