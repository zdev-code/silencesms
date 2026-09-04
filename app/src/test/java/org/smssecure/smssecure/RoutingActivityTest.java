package org.smssecure.smssecure;

import static org.assertj.core.api.Assertions.assertThat;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;

import java.util.Locale;

@RunWith(RobolectricTestRunner.class)
public class RoutingActivityTest {
  @Test
  public void launcherForwardsOnlyToAuthenticatedApplicationHost() {
    Intent launcher = new Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        .putExtra("launcher_metadata", "must not be forwarded");
    RoutingActivity activity = Robolectric.buildActivity(RoutingActivity.class, launcher).create().get();

    Intent forwarded = Shadows.shadowOf(activity).getNextStartedActivity();

    assertThat(forwarded.getComponent().getClassName())
        .isEqualTo(ConversationListActivity.class.getName());
    assertThat(forwarded.getExtras()).isNull();
    assertThat(forwarded.getAction()).isNull();
    assertThat(forwarded.getData()).isNull();
    assertThat(activity.getIntent().getExtras()).isNull();
    assertThat(activity.getIntent().getData()).isNull();
    assertThat(activity.isFinishing()).isTrue();
  }

  @Test
  public void malformedLauncherInputsAreRejectedWithoutForwarding() {
    for (Intent malformed : new Intent[] {
        new Intent(Intent.ACTION_VIEW).addCategory(Intent.CATEGORY_LAUNCHER),
        new Intent(Intent.ACTION_MAIN),
        new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .addCategory(Intent.CATEGORY_DEFAULT),
        new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setData(Uri.parse("sms:+15551234567")),
        launcherWithClipData()
    }) {
      RoutingActivity activity = Robolectric.buildActivity(RoutingActivity.class, malformed).create().get();

      assertThat(Shadows.shadowOf(activity).getNextStartedActivity()).isNull();
      assertThat(activity.getIntent().getExtras()).isNull();
      assertThat(activity.getIntent().getData()).isNull();
      assertThat(activity.isFinishing()).isTrue();
    }
  }

  private static Intent launcherWithClipData() {
    Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
    intent.setClipData(ClipData.newPlainText("unexpected", "plaintext"));
    return intent;
  }

  @Test
  public void conversationDestinationsPersistOnlyModeAndLocale() {
    assertThat(ConversationListDestination.ARCHIVE.arguments(Locale.CANADA).keySet())
        .containsExactlyInAnyOrder(PassphraseRequiredActionBarActivity.LOCALE_EXTRA,
                                  ConversationListFragment.ARCHIVE);
  }
}