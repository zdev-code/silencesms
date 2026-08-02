package org.smssecure.smssecure.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import android.widget.ProgressBar;

import androidx.appcompat.app.AlertDialog;

import org.smssecure.smssecure.R;
import org.smssecure.smssecure.database.DatabaseFactory;
import org.smssecure.smssecure.database.ThreadDatabase;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor;

import java.lang.ref.WeakReference;

public class Trimmer {

  public static void trimAllThreads(Context context, int threadLengthLimit) {
    TrimmingProgressController controller = new TrimmingProgressController(context);
    controller.start(threadLengthLimit);
  }

  private static class TrimmingProgressController implements ThreadDatabase.ProgressListener {
    private final WeakReference<Context> contextReference;
    private final Context                appContext;
    private final Handler                mainHandler = new Handler(Looper.getMainLooper());
    private final ProgressBar            progressBar;
    private final AlertDialog            progressDialog;

    private TrimmingProgressController(Context context) {
      contextReference = new WeakReference<>(context);
      appContext = context.getApplicationContext();
      progressBar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
      progressBar.setIndeterminate(false);
      progressBar.setMax(100);
      progressDialog = new AlertDialog.Builder(context)
          .setTitle(R.string.trimmer__deleting)
          .setMessage(R.string.trimmer__deleting_old_messages)
          .setView(progressBar)
          .setCancelable(false)
          .create();
    }

    private void start(int threadLengthLimit) {
      progressDialog.show();
      AppTaskExecutor.getInstance().submitSerial(
          () -> {
            DatabaseFactory.getThreadDatabase(appContext).trimAllThreads(threadLengthLimit, this);
            return null;
          },
          ignored -> finish(true),
          exception -> finish(false));
    }

    @Override
    public void onProgress(int complete, int total) {
      int progress = total <= 0 ? 100 : (int) Math.round((complete / (double) total) * 100.0);
      mainHandler.post(() -> progressBar.setProgress(Math.max(0, Math.min(100, progress))));
    }

    private void finish(boolean succeeded) {
      progressDialog.dismiss();
      Context context = contextReference.get();
      if (succeeded && context != null) {
        Toast.makeText(context, R.string.trimmer__old_messages_successfully_deleted, Toast.LENGTH_LONG).show();
      }
    }
  }
}
