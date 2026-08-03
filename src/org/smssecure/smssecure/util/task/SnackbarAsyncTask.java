package org.smssecure.smssecure.util.task;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.snackbar.Snackbar;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;

import org.smssecure.smssecure.util.concurrent.AppTaskExecutor;

public abstract class SnackbarAsyncTask<Params>
    implements View.OnClickListener
{
  private static final String TAG = SnackbarAsyncTask.class.getSimpleName();

  private final View    view;
  private final String  snackbarText;
  private final String  snackbarActionText;
  private final int     snackbarActionColor;
  private final int     snackbarDuration;
  private final boolean showProgress;

  private @Nullable Params         reversibleParameter;
  private @Nullable AlertDialog    progressDialog;

  public SnackbarAsyncTask(View view,
                           String snackbarText,
                           String snackbarActionText,
                           int snackbarActionColor,
                           int snackbarDuration,
                           boolean showProgress)
  {
    this.view                = view;
    this.snackbarText        = snackbarText;
    this.snackbarActionText  = snackbarActionText;
    this.snackbarActionColor = snackbarActionColor;
    this.snackbarDuration    = snackbarDuration;
    this.showProgress        = showProgress;
  }

  @SafeVarargs
  public final void execute(Params... parameters) {
    Params parameter = parameters != null && parameters.length > 0 ? parameters[0] : null;
    reversibleParameter = parameter;
    showProgress();
    AppTaskExecutor.getInstance().submitSerial(
        () -> {
          executeAction(parameter);
          return null;
        },
        ignored -> {
          dismissProgress();
          onPostExecute(null);
          if (view.isAttachedToWindow()) {
            Snackbar.make(view, snackbarText, snackbarDuration)
                    .setAction(snackbarActionText, this)
                    .setActionTextColor(snackbarActionColor)
                    .show();
          }
        },
        exception -> {
          dismissProgress();
          Log.w(TAG, "Unable to execute snackbar operation", exception);
        });
  }

  @Override
  public void onClick(View v) {
    showProgress();
    AppTaskExecutor.getInstance().submitSerial(
        () -> {
          reverseAction(reversibleParameter);
          return null;
        },
        ignored -> dismissProgress(),
        exception -> {
          dismissProgress();
          Log.w(TAG, "Unable to reverse snackbar operation", exception);
        });
  }

  private void showProgress() {
    if (!showProgress || !view.isAttachedToWindow()) return;
    progressDialog = new AlertDialog.Builder(view.getContext())
        .setView(new ProgressBar(view.getContext()))
        .setCancelable(false)
        .create();
    progressDialog.show();
  }

  private void dismissProgress() {
    if (progressDialog != null) progressDialog.dismiss();
    progressDialog = null;
  }

  protected void onPostExecute(Void result) {}

  protected abstract void executeAction(@Nullable Params parameter);
  protected abstract void reverseAction(@Nullable Params parameter);

}
