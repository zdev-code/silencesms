package org.smssecure.smssecure;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Point;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityOptionsCompat;
import android.util.Log;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;

import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.util.ActivityTransitionCompat;
import org.smssecure.smssecure.util.WindowSizeCompat;
import org.smssecure.smssecure.util.concurrent.ListenableFuture;

import java.util.concurrent.ExecutionException;

public class ConversationPopupActivity extends ConversationActivity {

  private static final String TAG = ConversationPopupActivity.class.getSimpleName();

  @Override
  protected void onPreCreate() {
    super.onPreCreate();
    ActivityTransitionCompat.overrideOpen(this, R.anim.slide_from_top, R.anim.slide_to_top);
  }

  @Override
  protected void onCreate(Bundle bundle, @NonNull MasterSecret masterSecret) {
    getWindow().setFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND,
                         WindowManager.LayoutParams.FLAG_DIM_BEHIND);

    WindowManager.LayoutParams params = getWindow().getAttributes();
    params.alpha     = 1.0f;
    params.dimAmount = 0.1f;
    params.gravity   = Gravity.TOP;
    getWindow().setAttributes(params);

    Point windowSize = WindowSizeCompat.getWindowSize(this);
    int width = windowSize.x;
    int height = windowSize.y;

    if (height > width) getWindow().setLayout((int) (width * .85), (int) (height * .5));
    else                getWindow().setLayout((int) (width * .7), (int) (height * .75));

    super.onCreate(bundle, masterSecret);

    getSupportActionBar().setDisplayHomeAsUpEnabled(false);
    disableTitleClick();
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (isConversationScreenVisible()) focusCompose();
  }

  @Override
  protected void onConversationScreenVisible() {
    if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(false);
    disableTitleClick();
    focusCompose();
  }

  @Override
  protected void onPause() {
    super.onPause();
    if (isFinishing()) ActivityTransitionCompat.overrideClose(this, R.anim.slide_from_top, R.anim.slide_to_top);
  }

  @Override
  public boolean onPrepareOptionsMenu(Menu menu) {
    if (!isConversationScreenVisible()) return super.onPrepareOptionsMenu(menu);
    MenuInflater inflater = this.getMenuInflater();
    menu.clear();

    inflater.inflate(R.menu.conversation_popup, menu);
    return true;
  }

  @Override
  @SuppressLint("NonConstantResourceId")
  public boolean onOptionsItemSelected(MenuItem item) {
    if (!isConversationScreenVisible()) return super.onOptionsItemSelected(item);
    if (item.getItemId() == R.id.menu_expand) {
        saveDraft().addListener(new ListenableFuture.Listener<Long>() {
          @Override
          public void onSuccess(Long result) {
            ActivityOptionsCompat transition = ActivityOptionsCompat.makeScaleUpAnimation(getWindow().getDecorView(), 0, 0, getWindow().getAttributes().width, getWindow().getAttributes().height);
            Intent intent = HostNavigationCommand.createConversationIntent(
              ConversationPopupActivity.this, getRecipients().getIds(), result,
              org.smssecure.smssecure.database.ThreadDatabase.DistributionTypes.DEFAULT,
              false, System.currentTimeMillis(), 0L, null);

            if (VERSION.SDK_INT >= VERSION_CODES.JELLY_BEAN) {
              startActivity(intent, transition.toBundle());
            } else {
              startActivity(intent);
              ActivityTransitionCompat.overrideOpen(ConversationPopupActivity.this, R.anim.fade_scale_in, R.anim.slide_to_right);
            }

            finish();
          }

          @Override
          public void onFailure(ExecutionException e) {
            Log.w(TAG, e);
          }
        });
        return true;
    }

    return false;
  }

  @Override
  protected void sendComplete(long threadId) {
    super.sendComplete(threadId);
    finish();
  }

  @Override
  public void openMediaOverview(long threadId, long recipientId) {
    super.openMediaOverview(threadId, recipientId);
    finish();
  }
}
