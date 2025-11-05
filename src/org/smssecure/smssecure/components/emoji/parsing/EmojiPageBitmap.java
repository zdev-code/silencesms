package org.smssecure.smssecure.components.emoji.parsing;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import android.util.Log;

import org.smssecure.smssecure.components.emoji.EmojiPageModel;
import org.smssecure.smssecure.util.BitmapDecodingException;
import org.smssecure.smssecure.util.BitmapUtil;
import org.smssecure.smssecure.util.ListenableFutureTask;
import org.smssecure.smssecure.util.Util;

import java.io.IOException;
import java.lang.ref.SoftReference;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class EmojiPageBitmap {

  private static final String TAG = EmojiPageBitmap.class.getName();

  private final Context        context;
  private final EmojiPageModel model;
  private final float          decodeScale;

  private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
    Thread thread = new Thread(r, "emoji-page-loader");
    thread.setDaemon(true);
    return thread;
  });
  private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

  private SoftReference<Bitmap>        bitmapReference;
  private ListenableFutureTask<Bitmap> task;

  public EmojiPageBitmap(@NonNull Context context, @NonNull EmojiPageModel model, float decodeScale) {
    this.context     = context.getApplicationContext();
    this.model       = model;
    this.decodeScale = decodeScale;
  }

  public ListenableFutureTask<Bitmap> get() {
    Util.assertMainThread();

    if (bitmapReference != null && bitmapReference.get() != null) {
      return new ListenableFutureTask<>(bitmapReference.get());
    } else if (task != null) {
      return task;
    } else {
      Callable<Bitmap> callable = new Callable<Bitmap>() {
        @Override public Bitmap call() throws Exception {
          try {
            Log.w(TAG, "loading page " + model.getSprite());
            return loadPage();
          } catch (IOException ioe) {
            Log.w(TAG, ioe);
          }
          return null;
        }
      };
      task = new ListenableFutureTask<>(callable);
      EXECUTOR.execute(() -> {
        try {
          task.run();
        } finally {
          MAIN_HANDLER.post(() -> task = null);
        }
      });
    }
    return task;
  }

  private Bitmap loadPage() throws IOException {
    if (bitmapReference != null && bitmapReference.get() != null) return bitmapReference.get();

    try {
      final Bitmap bitmap = BitmapUtil.createScaledBitmap(context,
                                                          "file:///android_asset/" + model.getSprite(),
                                                          decodeScale);
      bitmapReference = new SoftReference<>(bitmap);
      Log.w(TAG, "onPageLoaded(" + model.getSprite() + ")");
      return bitmap;
    } catch (BitmapDecodingException e) {
      Log.w(TAG, e);
      throw new IOException(e);
    }
  }

  @Override
  public String toString() {
    return model.getSprite();
  }
}
