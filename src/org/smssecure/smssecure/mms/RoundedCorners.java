package org.smssecure.smssecure.mms;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader.TileMode;

import androidx.annotation.NonNull;

import com.bumptech.glide.load.Key;
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool;
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation;
import com.bumptech.glide.load.resource.bitmap.TransformationUtils;

import org.smssecure.smssecure.util.ResUtil;

import java.security.MessageDigest;

public class RoundedCorners extends BitmapTransformation {
  private final boolean crop;
  private final int     radius;
  private final int     colorHint;

  public RoundedCorners(boolean crop, int radius, int colorHint) {
    this.crop      = crop;
    this.radius    = radius;
    this.colorHint = colorHint;
  }

  public RoundedCorners(@NonNull Context context, int radius) {
    this(true, radius, ResUtil.getColor(context, android.R.attr.windowBackground));
  }

  @Override
  protected Bitmap transform(@NonNull BitmapPool pool,
                             @NonNull Bitmap toTransform,
                             int outWidth,
                             int outHeight) {
    final Bitmap toRound = crop
                           ? TransformationUtils.centerCrop(pool, toTransform, outWidth, outHeight)
                           : TransformationUtils.fitCenter(pool, toTransform, outWidth, outHeight);

    final Bitmap rounded = round(pool, toRound);

    if (toRound != rounded && toRound != toTransform) {
      pool.put(toRound);
    }

    return rounded;
  }

  private Bitmap round(@NonNull BitmapPool pool, @NonNull Bitmap toRound) {
    Bitmap result = pool.get(toRound.getWidth(), toRound.getHeight(), getSafeConfig(toRound));

    if (result == null) {
      result = Bitmap.createBitmap(toRound.getWidth(), toRound.getHeight(), getSafeConfig(toRound));
    }

    result.setHasAlpha(true);

    Canvas canvas = new Canvas(result);

    if (Config.RGB_565.equals(result.getConfig())) {
      Paint  cornerPaint = new Paint();
      cornerPaint.setColor(colorHint);

      canvas.drawRect(0, 0, radius, radius, cornerPaint);
      canvas.drawRect(0, toRound.getHeight() - radius, radius, toRound.getHeight(), cornerPaint);
      canvas.drawRect(toRound.getWidth() - radius, 0, toRound.getWidth(), radius, cornerPaint);
      canvas.drawRect(toRound.getWidth() - radius, toRound.getHeight() - radius, toRound.getWidth(), toRound.getHeight(), cornerPaint);
    }

    Paint shaderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    shaderPaint.setShader(new BitmapShader(toRound, TileMode.CLAMP, TileMode.CLAMP));

    canvas.drawRoundRect(new RectF(0, 0, toRound.getWidth(), toRound.getHeight()), radius, radius, shaderPaint);

    return result;
  }

  private static Bitmap.Config getSafeConfig(Bitmap bitmap) {
    return bitmap.getConfig() != null ? bitmap.getConfig() : Bitmap.Config.ARGB_8888;
  }

  @Override
  public boolean equals(Object o) {
    if (o instanceof RoundedCorners) {
      RoundedCorners other = (RoundedCorners) o;
      return other.crop == crop && other.radius == radius && other.colorHint == colorHint;
    }
    return false;
  }

  @Override
  public int hashCode() {
    int result = crop ? 1 : 0;
    result = 31 * result + radius;
    result = 31 * result + colorHint;
    return result;
  }

  @Override
  public void updateDiskCacheKey(@NonNull MessageDigest messageDigest) {
    messageDigest.update((RoundedCorners.class.getName() + crop + radius + colorHint).getBytes(Key.CHARSET));
  }
}
