/**
 * Copyright (C) 2015 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.smssecure.smssecure;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.Configuration;
import android.database.Cursor;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.appcompat.app.AlertDialog;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.Loader;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.database.CursorRecyclerViewAdapter;
import org.smssecure.smssecure.database.DatabaseFactory;
import org.smssecure.smssecure.database.ImageDatabase.ImageRecord;
import org.smssecure.smssecure.recipients.Recipient;
import org.smssecure.smssecure.recipients.Recipient.RecipientModifiedListener;
import org.smssecure.smssecure.recipients.RecipientFactory;
import org.smssecure.smssecure.util.AbstractCursorLoader;
import org.smssecure.smssecure.util.DynamicLanguage;
import org.smssecure.smssecure.util.SaveAttachmentTask;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor.TaskHandle;

import java.util.ArrayList;
import java.util.List;

/**
 * Activity for displaying media attachments in-app
 */
public class MediaOverviewActivity extends PassphraseRequiredActionBarActivity implements LoaderManager.LoaderCallbacks<Cursor> {
  private final static String TAG = MediaOverviewActivity.class.getSimpleName();

  public static final String RECIPIENT_EXTRA = "recipient";
  public static final String THREAD_ID_EXTRA = "thread_id";

  private final DynamicLanguage dynamicLanguage = new DynamicLanguage();

  private MasterSecret masterSecret;

  private RecyclerView      gridView;
  private GridLayoutManager gridManager;
  private TextView          noImages;
  private Recipient         recipient;
  private long              threadId;
  private TaskHandle        collectAttachmentsTask;
  private TaskHandle        saveAttachmentsTask;
  private AlertDialog       progressDialog;

  @Override
  protected void onPreCreate() {
    this.setTheme(R.style.Silence_DarkTheme);
    dynamicLanguage.onCreate(this);
  }

  @Override
  protected void onCreate(Bundle bundle, @NonNull MasterSecret masterSecret) {
    this.masterSecret = masterSecret;
    setFullscreenIfPossible();

    getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    setContentView(R.layout.media_overview_activity);

    initializeResources();
    initializeActionBar();
    LoaderManager.getInstance(this).initLoader(0, null, this);
  }

  @Override
  public void onConfigurationChanged(Configuration newConfig) {
    super.onConfigurationChanged(newConfig);
    if (gridManager != null) gridManager.setSpanCount(getResources().getInteger(R.integer.media_overview_cols));
  }

  private void setFullscreenIfPossible() {
    WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .hide(WindowInsetsCompat.Type.statusBars());
  }

  @Override
  public void onResume() {
    super.onResume();
    dynamicLanguage.onResume(this);
  }

  private void initializeActionBar() {
    getSupportActionBar().setTitle(recipient == null
                                   ? getString(R.string.AndroidManifest__media_overview)
                                   : getString(R.string.AndroidManifest__media_overview_named, recipient.toShortString()));
  }

  @Override
  public void onPause() {
    super.onPause();
  }

  private void initializeResources() {
    threadId = getIntent().getLongExtra(THREAD_ID_EXTRA, -1);

    noImages = (TextView    ) findViewById(R.id.no_images );
    gridView = (RecyclerView) findViewById(R.id.media_grid);
    gridManager = new GridLayoutManager(this, getResources().getInteger(R.integer.media_overview_cols));
    gridView.setLayoutManager(gridManager);
    gridView.setHasFixedSize(true);

    final long recipientId = getIntent().getLongExtra(RECIPIENT_EXTRA, -1);
    if (recipientId > -1) {
      recipient = RecipientFactory.getRecipientForId(this, recipientId, true);
    } else if (threadId > -1){
      recipient = DatabaseFactory.getThreadDatabase(this).getRecipientsForThreadId(threadId).getPrimaryRecipient();
    } else {
      recipient = null;
    }

    if (recipient != null) {
      recipient.addListener(new RecipientModifiedListener() {
        @Override
        public void onModified(Recipient recipient) {
          initializeActionBar();
        }
      });
    }
  }

  private void saveToDisk() {
    SaveAttachmentTask.showWarningDialog(this, new DialogInterface.OnClickListener() {
      @Override
      public void onClick(DialogInterface dialogInterface, int i) {
        final Context context = getApplicationContext();
        showProgressDialog(getString(R.string.ConversationFragment_collecting_attahments),
                           getString(R.string.please_wait));
        collectAttachmentsTask = AppTaskExecutor.getInstance().submitSerial(
            () -> collectAttachments(context),
            attachments -> {
              if (!isActivityActive()) return;
              collectAttachmentsTask = null;
              if (attachments.isEmpty()) {
                Log.w(TAG, "No attachments found to save");
                dismissProgressDialog();
                SaveAttachmentTask.showResultToast(context, SaveAttachmentTask.FAILURE, 0);
                return;
              }

              int count = attachments.size();
              showProgressDialog(
                  getResources().getQuantityString(R.plurals.ConversationFragment_saving_n_attachments, count, count),
                  getResources().getQuantityString(R.plurals.ConversationFragment_saving_n_attachments_to_sd_card, count, count));
              SaveAttachmentTask.Attachment[] attachmentArray = attachments.toArray(new SaveAttachmentTask.Attachment[count]);
              saveAttachmentsTask = AppTaskExecutor.getInstance().submitSerial(
                  () -> SaveAttachmentTask.save(context, masterSecret, attachmentArray),
                  result -> {
                    if (!isActivityActive()) return;
                    dismissProgressDialog();
                    saveAttachmentsTask = null;
                    if (result != SaveAttachmentTask.SUCCESS) Log.w(TAG, "Unable to save attachments, result: " + result);
                    SaveAttachmentTask.showResultToast(context, result, count);
                  },
                  exception -> {
                    Log.w(TAG, "Unable to save attachments", exception);
                    if (!isActivityActive()) return;
                    dismissProgressDialog();
                    saveAttachmentsTask = null;
                    SaveAttachmentTask.showResultToast(context, SaveAttachmentTask.FAILURE, count);
                  });
            },
            exception -> {
              Log.w(TAG, "Unable to collect attachments", exception);
              if (!isActivityActive()) return;
              dismissProgressDialog();
              collectAttachmentsTask = null;
              SaveAttachmentTask.showResultToast(context, SaveAttachmentTask.FAILURE, 1);
            });
      }
    }, gridView.getAdapter().getItemCount());
  }

  private List<SaveAttachmentTask.Attachment> collectAttachments(Context context) {
    Cursor cursor = DatabaseFactory.getImageDatabase(context).getImagesForThread(threadId);
    try {
      List<SaveAttachmentTask.Attachment> attachments = new ArrayList<>(cursor.getCount());
      while (cursor.moveToNext()) {
        ImageRecord record = ImageRecord.from(cursor);
        attachments.add(new SaveAttachmentTask.Attachment(record.getAttachment().getDataUri(),
                                                           record.getContentType(),
                                                           record.getDate()));
      }
      return attachments;
    } finally {
      cursor.close();
    }
  }

  private void showProgressDialog(CharSequence title, CharSequence message) {
    dismissProgressDialog();
    progressDialog = new AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage(message)
        .setView(new ProgressBar(this))
        .setCancelable(false)
        .create();
    progressDialog.show();
  }

  private void dismissProgressDialog() {
    if (progressDialog != null) progressDialog.dismiss();
    progressDialog = null;
  }

  private boolean isActivityActive() {
    return !isFinishing() && !isDestroyed();
  }

  @Override
  protected void onDestroy() {
    if (collectAttachmentsTask != null) collectAttachmentsTask.cancel();
    if (saveAttachmentsTask != null) saveAttachmentsTask.cancel();
    collectAttachmentsTask = null;
    saveAttachmentsTask = null;
    dismissProgressDialog();
    super.onDestroy();
  }

  @Override
  public boolean onPrepareOptionsMenu(Menu menu) {
    super.onPrepareOptionsMenu(menu);

    menu.clear();
    if (gridView.getAdapter() != null && gridView.getAdapter().getItemCount() > 0) {
      MenuInflater inflater = this.getMenuInflater();
      inflater.inflate(R.menu.media_overview, menu);
    }

    return true;
  }

  @Override
  @SuppressLint("NonConstantResourceId")
  public boolean onOptionsItemSelected(MenuItem item) {
    super.onOptionsItemSelected(item);

    int itemId = item.getItemId();
    if      (itemId == R.id.save)         { saveToDisk(); return true; }
    else if (itemId == android.R.id.home) { finish();     return true; }

    return false;
  }

  @Override
  public Loader<Cursor> onCreateLoader(int i, Bundle bundle) {
    return new ThreadMediaLoader(this, threadId);
  }

  @Override
  public void onLoadFinished(Loader<Cursor> cursorLoader, Cursor cursor) {
    Log.w(TAG, "onLoadFinished()");
    gridView.setAdapter(new ImageMediaAdapter(this, masterSecret, cursor, threadId));
    noImages.setVisibility(gridView.getAdapter().getItemCount() > 0 ? View.GONE : View.VISIBLE);
    invalidateOptionsMenu();
  }

  @Override
  public void onLoaderReset(Loader<Cursor> cursorLoader) {
    ((CursorRecyclerViewAdapter)gridView.getAdapter()).changeCursor(null);
  }

  public static class ThreadMediaLoader extends AbstractCursorLoader {
    private final long threadId;

    public ThreadMediaLoader(Context context, long threadId) {
      super(context);
      this.threadId = threadId;
    }

    @Override
    public Cursor getCursor() {
      return DatabaseFactory.getImageDatabase(getContext()).getImagesForThread(threadId);
    }
  }
}
