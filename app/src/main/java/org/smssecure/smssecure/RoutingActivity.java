package org.smssecure.smssecure;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

public final class RoutingActivity extends Activity {
  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    Intent source = getIntent();
    setIntent(new Intent());
    if (isLauncherIntent(source)) {
      startActivity(new Intent(this, ConversationListActivity.class));
    }
    finish();
  }

  private static boolean isLauncherIntent(Intent intent) {
    return intent != null && Intent.ACTION_MAIN.equals(intent.getAction()) &&
        intent.hasCategory(Intent.CATEGORY_LAUNCHER) && intent.getCategories().size() == 1 &&
        intent.getData() == null && intent.getClipData() == null &&
        (intent.getExtras() == null || intent.getExtras().isEmpty());
  }
}