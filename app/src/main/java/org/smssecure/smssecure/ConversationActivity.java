package org.smssecure.smssecure;

import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.core.view.WindowCompat;
import androidx.fragment.app.Fragment;

import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.domain.media.MediaPreviewDraftStore;
import org.smssecure.smssecure.recipients.Recipients;
import org.smssecure.smssecure.util.DynamicLanguage;
import org.smssecure.smssecure.util.DynamicTheme;
import org.smssecure.smssecure.util.concurrent.ListenableFuture;

import dagger.hilt.android.AndroidEntryPoint;
import javax.inject.Inject;

@AndroidEntryPoint
public class ConversationActivity extends PassphraseRequiredActionBarActivity
    implements ConversationScreenHost {
  public static final String RECIPIENTS_EXTRA        = "recipients";
  public static final String THREAD_ID_EXTRA         = "thread_id";
  public static final String IS_ARCHIVED_EXTRA       = "is_archived";
  public static final String TEXT_EXTRA              = "draft_text";
  public static final String DISTRIBUTION_TYPE_EXTRA = "distribution_type";
  public static final String TIMING_EXTRA            = "timing";
  public static final String LAST_SEEN_EXTRA         = "last_seen";

  private static final String SCREEN_TAG = "conversation-screen";
  private static final String PREVIEW_TAG = "conversation-media-preview";
  private static final String PREVIEW_BACK_STACK = "conversation-media-preview-back-stack";

  private final DynamicTheme dynamicTheme = new DynamicTheme();
  private final DynamicLanguage dynamicLanguage = new DynamicLanguage();
  private ConversationScreenFragment screen;
  @Inject MediaPreviewDraftStore mediaPreviewDraftStore;

  @Override
  protected boolean isActionBarOverlay() {
    return true;
  }

  @Override
  protected void onPreCreate() {
    dynamicTheme.onCreate(this);
    dynamicLanguage.onCreate(this);
  }

  @Override
  protected void onCreate(Bundle state, @NonNull MasterSecret masterSecret) {
    supportRequestWindowFeature(WindowCompat.FEATURE_ACTION_BAR_OVERLAY);
    getSupportFragmentManager().addOnBackStackChangedListener(this::onLocalBackStackChanged);
    screen = (ConversationScreenFragment) getSupportFragmentManager().findFragmentByTag(SCREEN_TAG);
    if (screen == null) {
      screen = new ConversationScreenFragment();
      screen.setArguments(ConversationScreenFragment.arguments(getIntent(), null));
      getSupportFragmentManager().beginTransaction()
                                 .replace(android.R.id.content, screen, SCREEN_TAG)
                                 .commitNow();
    }
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    getSupportFragmentManager().popBackStackImmediate(
        PREVIEW_BACK_STACK, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE);
    screen = (ConversationScreenFragment) getSupportFragmentManager().findFragmentByTag(SCREEN_TAG);
    if (screen != null) screen.updateArguments(ConversationScreenFragment.arguments(intent, null));
  }

  @Override
  public void onMasterSecretCleared() {
    mediaPreviewDraftStore.clear();
    MediaPreviewFragment preview = currentMediaPreview();
    if (preview != null) preview.clearSensitiveState();
    if (screen != null) screen.clearSensitiveState();
    super.onMasterSecretCleared();
  }

  @Override
  protected void onResume() {
    super.onResume();
    dynamicTheme.onResume(this);
    dynamicLanguage.onResume(this);
  }

  protected ListenableFuture<Long> saveDraft() {
    return screen.saveDraft();
  }

  protected Recipients getRecipients() {
    return screen.getRecipients();
  }

  protected void focusCompose() {
    if (isConversationScreenVisible() && screen != null) screen.focusCompose();
  }

  protected void disableTitleClick() {
    if (screen != null) screen.disableTitleClick();
  }

  protected boolean isConversationScreenVisible() {
    Fragment current = getSupportFragmentManager().findFragmentById(android.R.id.content);
    return current instanceof ConversationScreenFragment;
  }

  protected void onConversationScreenVisible() {}

  private void onLocalBackStackChanged() {
    screen = (ConversationScreenFragment) getSupportFragmentManager().findFragmentByTag(SCREEN_TAG);
    if (isConversationScreenVisible()) {
      if (getSupportActionBar() != null) getSupportActionBar().show();
      onConversationScreenVisible();
    }
    supportInvalidateOptionsMenu();
  }

  private MediaPreviewFragment currentMediaPreview() {
    Fragment current = getSupportFragmentManager().findFragmentById(android.R.id.content);
    return current instanceof MediaPreviewFragment ? (MediaPreviewFragment) current : null;
  }

  private void showMediaPreview(Bundle arguments) {
    ConversationListDestination.MEDIA_PREVIEW.requireAllowedArguments(arguments);
    MediaPreviewFragment preview = new MediaPreviewFragment();
    preview.setArguments(arguments);
    androidx.fragment.app.FragmentTransaction transaction = getSupportFragmentManager()
        .beginTransaction()
        .replace(android.R.id.content, preview, PREVIEW_TAG);
    if (currentMediaPreview() == null) transaction.addToBackStack(PREVIEW_BACK_STACK);
    transaction.commit();
  }

  @Override
  public boolean onPrepareOptionsMenu(Menu menu) {
    MediaPreviewFragment preview = currentMediaPreview();
    if (preview != null) {
      preview.populateOptionsMenu(menu);
      return true;
    }
    return super.onPrepareOptionsMenu(menu);
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    MediaPreviewFragment preview = currentMediaPreview();
    if (preview != null && preview.handleOptionsItem(item)) return true;
    return super.onOptionsItemSelected(item);
  }

  protected void sendComplete(long threadId) {}

  @Override
  public void finishConversationScreen() {
    finish();
  }

  @Override
  public boolean isConversationHostFinishing() {
    return isFinishing();
  }

  @Override
  public void onConversationSendComplete(long threadId) {
    sendComplete(threadId);
  }

  @Override
  public void openVerifyIdentity(long recipientId, int subscriptionId) {
    startActivity(HostNavigationCommand.createVerifyIdentityIntent(
        this, recipientId, subscriptionId));
  }

  @Override
  public void openRecipientPreferences(long[] recipientIds) {
    startActivity(HostNavigationCommand.createRecipientPreferencesIntent(this, recipientIds));
  }

  @Override
  public void openMediaOverview(long threadId, long recipientId) {
    startActivity(HostNavigationCommand.createMediaOverviewIntent(this, threadId, recipientId));
  }

  @Override
  public void openMediaPreview(long partRowId, long partUniqueId, long messageId, long threadId,
                               long recipientId, long date, long size) {
    showMediaPreview(MediaPreviewFragment.persistedArguments(
      partRowId, partUniqueId, messageId, threadId, recipientId, date, size));
  }

  @Override
  public void openDraftMediaPreview(android.net.Uri uri, String contentType, long size) {
    mediaPreviewDraftStore.put(MediaPreviewFragment.DRAFT_OWNER, uri, contentType, size);
    showMediaPreview(MediaPreviewFragment.draftArguments(size));
  }

  @Override
  public void closeMediaPreview() {
    if (!getSupportFragmentManager().popBackStackImmediate()) finish();
  }

}
