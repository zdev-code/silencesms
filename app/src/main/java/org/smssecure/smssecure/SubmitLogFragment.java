/*
 * Copyright (C) 2014 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.smssecure.smssecure;

import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.fragment.app.Fragment;
import androidx.annotation.NonNull;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import org.smssecure.smssecure.util.FileProviderUtil;
import org.smssecure.smssecure.util.Scrubber;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A helper {@link Fragment} to preview and share scrubbed logcat information.
 * Activities that contain this fragment must implement the
 * {@link SubmitLogFragment.OnLogSubmittedListener} interface
 * to handle interaction events.
 * Use the {@link SubmitLogFragment#newInstance} factory method to
 * create an instance of this fragment.
 *
 */
public class SubmitLogFragment extends Fragment {
  private static final String TAG = SubmitLogFragment.class.getSimpleName();

  private EditText logPreview;
  private Button   okButton;
  private Button   cancelButton;
  private boolean  shareActivityWasStarted = false;

  private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
  private final Handler mainHandler = new Handler(Looper.getMainLooper());

  private OnLogSubmittedListener mListener;

  /**
   * Use this factory method to create a new instance of
   * this fragment using the provided parameters.
   *
   * @return A new instance of fragment SubmitLogFragment.
   */
  public static SubmitLogFragment newInstance()
  {
    return new SubmitLogFragment();
  }

  public SubmitLogFragment() { }

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
  }

  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container,
                           Bundle savedInstanceState) {
    // Inflate the layout for this fragment
    return inflater.inflate(R.layout.fragment_submit_log, container, false);
  }

  @Override
  public void onViewCreated(View view, Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);
    initializeResources();
  }

  @Override
  public void onAttach(@NonNull Context context) {
    super.onAttach(context);
    if (context instanceof OnLogSubmittedListener) {
      mListener = (OnLogSubmittedListener) context;
    } else {
      throw new ClassCastException(context.toString() + " must implement OnFragmentInteractionListener");
    }
  }

  @Override
  public void onResume() {
    super.onResume();

    if (shareActivityWasStarted && mListener != null)
      mListener.onSuccess();
  }

  @Override
  public void onDetach() {
    super.onDetach();
    mListener = null;
  }

  @Override
  public void onDestroy() {
    backgroundExecutor.shutdownNow();
    super.onDestroy();
  }

  private void initializeResources() {
    logPreview   = (EditText) getView().findViewById(R.id.log_preview);
    okButton     = (Button  ) getView().findViewById(R.id.ok         );
    cancelButton = (Button  ) getView().findViewById(R.id.cancel     );

    okButton.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        shareLog(logPreview.getText().toString());
      }
    });

    cancelButton.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        if (mListener != null) mListener.onCancel();
      }
    });
    populateLogPreviewAsync();
  }

  private static String grabLogcat() {
    try {
      final Process         process        = Runtime.getRuntime().exec("logcat -d");
      final BufferedReader  bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
      final StringBuilder   log            = new StringBuilder();
      final String          separator      = System.getProperty("line.separator");

      String line;
      while ((line = bufferedReader.readLine()) != null) {
        log.append(line);
        log.append(separator);
      }
      return log.toString();
    } catch (IOException ioe) {
      Log.w(TAG, "IOException when trying to read logcat.", ioe);
      return null;
    }
  }

  private void shareLog(String log) {
    Context appContext = requireContext().getApplicationContext();
    okButton.setEnabled(false);

    backgroundExecutor.execute(() -> {
      try {
        File cacheDirectory = appContext.getExternalCacheDir();
        if (cacheDirectory == null) {
          throw new IOException("External cache is unavailable.");
        }

        File logFile = new File(cacheDirectory, "silence-diagnostic-log.txt");
        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(logFile), StandardCharsets.UTF_8)) {
          writer.write(log);
        }

        Uri logUri = FileProviderUtil.getUriFor(appContext, logFile);
        mainHandler.post(() -> showShareChooser(logUri));
      } catch (IOException e) {
        Log.w(TAG, "Failed to prepare diagnostic log for sharing.", e);
        mainHandler.post(() -> {
          if (!isAdded()) {
            return;
          }
          okButton.setEnabled(true);
          Toast.makeText(requireContext(), R.string.log_submit_activity__share_failed, Toast.LENGTH_LONG).show();
        });
      }
    });
  }

  private void showShareChooser(Uri logUri) {
    if (!isAdded()) {
      return;
    }

    Intent shareIntent = new Intent(Intent.ACTION_SEND);
    shareIntent.putExtra(Intent.EXTRA_STREAM, logUri);
    shareIntent.setClipData(ClipData.newRawUri("diagnostic-log", logUri));
    shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    shareIntent.setType("text/plain");

    okButton.setEnabled(true);
    startActivity(Intent.createChooser(shareIntent, getString(R.string.log_submit_activity__choose_share_app)));
    shareActivityWasStarted = true;
  }

  private void populateLogPreviewAsync() {
    if (!isAdded()) {
      return;
    }

    logPreview.setText(R.string.log_submit_activity__loading_logs);
    okButton.setEnabled(false);

    final Context appContext = requireContext().getApplicationContext();

    backgroundExecutor.execute(() -> {
      String description = buildDescription(appContext);
      String logcat = new Scrubber().scrub(grabLogcat());
      final String combined = TextUtils.isEmpty(logcat) ? description : description + "\n" + logcat;

      mainHandler.post(() -> {
        if (!isAdded()) {
          return;
        }

        if (TextUtils.isEmpty(combined)) {
          if (mListener != null) mListener.onFailure();
          return;
        }

        logPreview.setText(combined);
        okButton.setEnabled(true);
      });
    });
  }

  private static long asMegs(long bytes) {
    return bytes / 1048576L;
  }

  public static String getMemoryUsage(Context context) {
    Runtime info = Runtime.getRuntime();
    info.totalMemory();
    return String.format(Locale.ENGLISH, "%dM (%.2f%% free, %dM max)",
                         asMegs(info.totalMemory()),
                         (float)info.freeMemory() / info.totalMemory() * 100f,
                         asMegs(info.maxMemory()));
  }

  @TargetApi(VERSION_CODES.KITKAT)
  public static String getMemoryClass(Context context) {
    ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
    String          lowMem          = "";

    if (VERSION.SDK_INT >= VERSION_CODES.KITKAT && activityManager.isLowRamDevice()) {
      lowMem = ", low-mem device";
    }
    return activityManager.getMemoryClass() + lowMem;
  }

  private static String buildDescription(Context context) {
    final PackageManager pm      = context.getPackageManager();
    final StringBuilder  builder = new StringBuilder();


    builder.append("Device  : ")
           .append(Build.MANUFACTURER).append(" ")
           .append(Build.MODEL).append(" (")
           .append(Build.PRODUCT).append(")\n");
    builder.append("Android : ").append(VERSION.RELEASE).append(" (")
                               .append(VERSION.INCREMENTAL).append(", ")
                               .append(Build.DISPLAY).append(")\n");
    builder.append("Memory  : ").append(getMemoryUsage(context)).append("\n");
    builder.append("Memclass: ").append(getMemoryClass(context)).append("\n");
    builder.append("OS Host : ").append(Build.HOST).append("\n");
    builder.append("App     : ");
    try {
      builder.append(pm.getApplicationLabel(pm.getApplicationInfo(context.getPackageName(), 0)))
             .append(" ")
             .append(pm.getPackageInfo(context.getPackageName(), 0).versionName)
             .append("\n");
    } catch (PackageManager.NameNotFoundException nnfe) {
      builder.append("Unknown\n");
    }

    return builder.toString();
  }

  /**
   * This interface must be implemented by activities that contain this
   * fragment to allow an interaction in this fragment to be communicated
   * to the activity and potentially other fragments contained in that
   * activity.
   * <p>
   * See the Android Training lesson <a href=
   * "http://developer.android.com/training/basics/fragments/communicating.html"
   * >Communicating with Other Fragments</a> for more information.
   */
  public interface OnLogSubmittedListener {
    public void onSuccess();
    public void onFailure();
    public void onCancel();
  }
}
