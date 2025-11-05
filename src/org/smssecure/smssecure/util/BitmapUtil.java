package org.smssecure.smssecure.util;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.NonNull;

import org.smssecure.smssecure.crypto.AttachmentCipherInputStream;
import org.smssecure.smssecure.mms.AttachmentStreamUriLoader;
import org.smssecure.smssecure.mms.DecryptableStreamUriLoader;
import org.smssecure.smssecure.mms.MediaConstraints;
import org.smssecure.smssecure.mms.PartAuthority;
import org.whispersystems.libsignal.InvalidMessageException;
import org.whispersystems.libsignal.util.guava.Optional;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;

public class BitmapUtil {

  private static final String TAG = BitmapUtil.class.getSimpleName();

  private static final int MAX_COMPRESSION_QUALITY          = 95;
  private static final int MIN_COMPRESSION_QUALITY          = 45;
  private static final int COMPRESSION_QUALITY_DECREASE     = 2;

  public static <T> byte[] createScaledBytes(Context context, T model, MediaConstraints constraints)
      throws BitmapDecodingException
  {
    CompressFormat compressFormat = CompressFormat.PNG;

    int    quality  = 100;
    int    attempts = 0;
    byte[] bytes;
    Bitmap scaledBitmap = null;

    try {
      Pair<Integer, Integer> originalDimensions = getDimensions(context, model);
      Pair<Integer, Integer> targetDimensions   = clampDimensions(originalDimensions.first,
                                                                  originalDimensions.second,
                                                                  constraints.getImageMaxWidth(context),
                                                                  constraints.getImageMaxHeight(context));

      scaledBitmap = decodeScaledBitmap(context, model, targetDimensions.first, targetDimensions.second, originalDimensions);

      do {
        attempts++;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        scaledBitmap.compress(compressFormat, quality, baos);
        bytes = baos.toByteArray();

        Log.w(TAG, "iteration with quality " + quality + "; size " + (bytes.length / 1024) + "kb; attempts " + attempts);

        compressFormat = CompressFormat.JPEG;

        if (quality > MAX_COMPRESSION_QUALITY) {
          quality = MAX_COMPRESSION_QUALITY;
        } else {
          quality = quality - COMPRESSION_QUALITY_DECREASE;
        }
      }
      while (bytes.length > constraints.getImageMaxSize(context));
      return bytes;
    } finally {
      if (scaledBitmap != null) scaledBitmap.recycle();
    }
  }

  public static <T> Bitmap createScaledBitmap(Context context, T model, int maxWidth, int maxHeight)
      throws BitmapDecodingException
  {
    final Pair<Integer, Integer> dimensions = getDimensions(context, model);
    final Pair<Integer, Integer> clamped    = clampDimensions(dimensions.first, dimensions.second,
                                                              maxWidth, maxHeight);
    return decodeScaledBitmap(context, model, clamped.first, clamped.second);
  }

  public static <T> Bitmap createScaledBitmap(Context context, T model, float scale)
      throws BitmapDecodingException
  {
    Pair<Integer, Integer> dimens = getDimensions(context, model);
    return decodeScaledBitmap(context, model,
                                  (int)(dimens.first * scale), (int)(dimens.second * scale), dimens);
  }

  private static <T> Pair<Integer, Integer> getDimensions(Context context, T model) throws BitmapDecodingException {
    try (InputStream inputStream = new BufferedInputStream(openInputStream(context, model))) {
      return getDimensions(inputStream);
    } catch (IOException e) {
      throw new BitmapDecodingException(e);
    }
  }

  private static <T> Bitmap decodeScaledBitmap(Context context, T model, int targetWidth, int targetHeight)
      throws BitmapDecodingException {
    return decodeScaledBitmap(context, model, targetWidth, targetHeight, null);
  }

  private static <T> Bitmap decodeScaledBitmap(Context context, T model, int targetWidth, int targetHeight,
                                               Pair<Integer, Integer> originalDimensions)
      throws BitmapDecodingException {
    if (targetWidth <= 0 || targetHeight <= 0) {
      throw new BitmapDecodingException("Invalid target dimensions: " + targetWidth + "x" + targetHeight);
    }

    Pair<Integer, Integer> dimensions = originalDimensions != null
                                        ? originalDimensions
                                        : getDimensions(context, model);

    int inSampleSize = calculateSampleSize(dimensions.first, dimensions.second,
                                           targetWidth, targetHeight);

    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inSampleSize = Math.max(1, inSampleSize);
    options.inPreferredConfig = Bitmap.Config.RGB_565;

    Bitmap decoded;

    try (InputStream decodeStream = new BufferedInputStream(openInputStream(context, model))) {
      decoded = BitmapFactory.decodeStream(decodeStream, null, options);
    } catch (IOException e) {
      throw new BitmapDecodingException(e);
    }

    if (decoded == null) {
      throw new BitmapDecodingException("Unable to decode image");
    }

    if (decoded.getWidth() <= 0 || decoded.getHeight() <= 0) {
      decoded.recycle();
      throw new BitmapDecodingException("Decoded bitmap has invalid dimensions");
    }

    float scale = Math.min(targetWidth / (float) decoded.getWidth(), targetHeight / (float) decoded.getHeight());

    if (scale < 1f) {
      int scaledWidth  = Math.max(1, Math.round(decoded.getWidth() * scale));
      int scaledHeight = Math.max(1, Math.round(decoded.getHeight() * scale));
      Bitmap scaled    = Bitmap.createScaledBitmap(decoded, scaledWidth, scaledHeight, true);
      if (scaled != decoded) {
        decoded.recycle();
      }
      decoded = scaled;
    }

    return decoded;
  }

  private static int calculateSampleSize(int originalWidth, int originalHeight, int targetWidth, int targetHeight) {
    int inSampleSize = 1;

    if (originalHeight > targetHeight || originalWidth > targetWidth) {
      final int halfHeight = originalHeight / 2;
      final int halfWidth  = originalWidth / 2;

      while ((halfHeight / inSampleSize) >= targetHeight && (halfWidth / inSampleSize) >= targetWidth) {
        inSampleSize *= 2;
      }
    }

    return Math.max(1, inSampleSize);
  }

  private static <T> InputStream openInputStream(Context context, T model) throws IOException {
    if (model instanceof DecryptableStreamUriLoader.DecryptableUri) {
      DecryptableStreamUriLoader.DecryptableUri decryptableUri = (DecryptableStreamUriLoader.DecryptableUri) model;
      InputStream stream = PartAuthority.getAttachmentStream(context, decryptableUri.masterSecret, decryptableUri.uri);
      if (stream == null) throw new IOException("Unable to open decryptable uri stream");
      return stream;
    } else if (model instanceof AttachmentStreamUriLoader.AttachmentModel) {
      AttachmentStreamUriLoader.AttachmentModel attachmentModel = (AttachmentStreamUriLoader.AttachmentModel) model;
      try {
        return new AttachmentCipherInputStream(attachmentModel.attachment, attachmentModel.key, Optional.<byte[]>absent());
      } catch (InvalidMessageException e) {
        throw new IOException("Unable to decrypt attachment stream", e);
      }
    } else if (model instanceof Uri) {
      Uri uri = (Uri) model;
      InputStream stream = context.getContentResolver().openInputStream(uri);
      if (stream == null) throw new IOException("Unable to open uri stream for " + uri);
      return stream;
    } else if (model instanceof String) {
      String path = (String) model;
      if (path.startsWith("file:///android_asset/")) {
        AssetManager assetManager = context.getAssets();
        return assetManager.open(path.substring("file:///android_asset/".length()));
      }

      Uri uri = Uri.parse(path);
      if (uri.getScheme() == null) {
        return new FileInputStream(new File(path));
      }

      InputStream stream = context.getContentResolver().openInputStream(uri);
      if (stream == null) throw new IOException("Unable to open uri stream for " + uri);
      return stream;
    } else if (model instanceof File) {
      return new FileInputStream((File) model);
    } else if (model instanceof InputStream) {
      return (InputStream) model;
    } else if (model instanceof byte[]) {
      return new ByteArrayInputStream((byte[]) model);
    }

    throw new IOException("Unsupported model type: " + (model == null ? "null" : model.getClass().getName()));
  }

  private static BitmapFactory.Options getImageDimensions(InputStream inputStream)
      throws BitmapDecodingException
  {
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inJustDecodeBounds    = true;
    BufferedInputStream fis       = new BufferedInputStream(inputStream);
    BitmapFactory.decodeStream(fis, null, options);
    try {
      fis.close();
    } catch (IOException ioe) {
      Log.w(TAG, "failed to close the InputStream after reading image dimensions");
    }

    if (options.outWidth == -1 || options.outHeight == -1) {
      throw new BitmapDecodingException("Failed to decode image dimensions: " + options.outWidth + ", " + options.outHeight);
    }

    return options;
  }

  public static Pair<Integer, Integer> getDimensions(InputStream inputStream) throws BitmapDecodingException {
    BitmapFactory.Options options = getImageDimensions(inputStream);
    return new Pair<>(options.outWidth, options.outHeight);
  }

  public static InputStream toCompressedJpeg(Bitmap bitmap) {
    ByteArrayOutputStream thumbnailBytes = new ByteArrayOutputStream();
    bitmap.compress(CompressFormat.JPEG, 85, thumbnailBytes);
    return new ByteArrayInputStream(thumbnailBytes.toByteArray());
  }

  public static byte[] toByteArray(Bitmap bitmap) {
    ByteArrayOutputStream stream = new ByteArrayOutputStream();
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
    return stream.toByteArray();
  }

  public static byte[] createFromNV21(@NonNull final byte[] data,
                                      final int width,
                                      final int height,
                                      int rotation,
                                      final Rect croppingRect)
      throws IOException
  {
    byte[] rotated = rotateNV21(data, width, height, rotation);
    final int rotatedWidth  = rotation % 180 > 0 ? height : width;
    final int rotatedHeight = rotation % 180 > 0 ? width  : height;
    YuvImage previewImage = new YuvImage(rotated, ImageFormat.NV21,
                                         rotatedWidth, rotatedHeight, null);

    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    previewImage.compressToJpeg(croppingRect, 80, outputStream);
    byte[] bytes = outputStream.toByteArray();
    outputStream.close();
    return bytes;
  }

  public static byte[] rotateNV21(@NonNull final byte[] yuv,
                                  final int width,
                                  final int height,
                                  final int rotation)
  {
    if (rotation == 0) return yuv;
    if (rotation % 90 != 0 || rotation < 0 || rotation > 270) {
      throw new IllegalArgumentException("0 <= rotation < 360, rotation % 90 == 0");
    }

    final byte[]  output    = new byte[yuv.length];
    final int     frameSize = width * height;
    final boolean swap      = rotation % 180 != 0;
    final boolean xflip     = rotation % 270 != 0;
    final boolean yflip     = rotation >= 180;

    for (int j = 0; j < height; j++) {
      for (int i = 0; i < width; i++) {
        final int yIn = j * width + i;
        final int uIn = frameSize + (j >> 1) * width + (i & ~1);
        final int vIn = uIn       + 1;

        final int wOut     = swap ? height              : width;
        final int hOut     = swap ? width               : height;
        final int iSwapped = swap ? j                   : i;
        final int jSwapped = swap ? i                   : j;
        final int iOut     = xflip ? wOut - iSwapped - 1 : iSwapped;
        final int jOut     = yflip ? hOut - jSwapped - 1 : jSwapped;

        final int yOut = jOut * wOut + iOut;
        final int uOut = frameSize + (jOut >> 1) * wOut + (iOut & ~1);
        final int vOut = uOut + 1;

        output[yOut] = (byte)(0xff & yuv[yIn]);
        output[uOut] = (byte)(0xff & yuv[uIn]);
        output[vOut] = (byte)(0xff & yuv[vIn]);
      }
    }
    return output;
  }

  private static Pair<Integer, Integer> clampDimensions(int inWidth, int inHeight, int maxWidth, int maxHeight) {
    if (inWidth > maxWidth || inHeight > maxHeight) {
      final float aspectWidth, aspectHeight;

      if (inWidth == 0 || inHeight == 0) {
        aspectWidth  = maxWidth;
        aspectHeight = maxHeight;
      } else if (inWidth >= inHeight) {
        aspectWidth  = maxWidth;
        aspectHeight = (aspectWidth / inWidth) * inHeight;
      } else {
        aspectHeight = maxHeight;
        aspectWidth  = (aspectHeight / inHeight) * inWidth;
      }

      return new Pair<>(Math.round(aspectWidth), Math.round(aspectHeight));
    } else {
      return new Pair<>(inWidth, inHeight);
    }
  }

  public static Bitmap createFromDrawable(final Drawable drawable, final int width, final int height) {
    final AtomicBoolean created = new AtomicBoolean(false);
    final Bitmap[]      result  = new Bitmap[1];

    Runnable runnable = new Runnable() {
      @Override
      public void run() {
        if (drawable instanceof BitmapDrawable) {
          result[0] = ((BitmapDrawable) drawable).getBitmap();
        } else {
          int canvasWidth = drawable.getIntrinsicWidth();
          if (canvasWidth <= 0) canvasWidth = width;

          int canvasHeight = drawable.getIntrinsicHeight();
          if (canvasHeight <= 0) canvasHeight = height;

          Bitmap bitmap;

          try {
            bitmap = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
            drawable.draw(canvas);
          } catch (Exception e) {
            Log.w(TAG, e);
            bitmap = null;
          }

          result[0] = bitmap;
        }

        synchronized (result) {
          created.set(true);
          result.notifyAll();
        }
      }
    };

    Util.runOnMain(runnable);

    synchronized (result) {
      while (!created.get()) Util.wait(result, 0);
      return result[0];
    }
  }

  public static int getMaxTextureSize() {
    final int IMAGE_MAX_BITMAP_DIMENSION = 2048;

    EGL10 egl = (EGL10) EGLContext.getEGL();
    EGLDisplay display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);

    int[] version = new int[2];
    egl.eglInitialize(display, version);

    int[] totalConfigurations = new int[1];
    egl.eglGetConfigs(display, null, 0, totalConfigurations);

    EGLConfig[] configurationsList = new EGLConfig[totalConfigurations[0]];
    egl.eglGetConfigs(display, configurationsList, totalConfigurations[0], totalConfigurations);

    int[] textureSize = new int[1];
    int maximumTextureSize = 0;

    for (int i = 0; i < totalConfigurations[0]; i++) {
      egl.eglGetConfigAttrib(display, configurationsList[i], EGL10.EGL_MAX_PBUFFER_WIDTH, textureSize);

      if (maximumTextureSize < textureSize[0])
        maximumTextureSize = textureSize[0];
    }

    egl.eglTerminate(display);

    return Math.max(maximumTextureSize, IMAGE_MAX_BITMAP_DIMENSION);
  }
}
