package org.smssecure.smssecure.contacts.avatars;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import androidx.annotation.Nullable;

final class ContactPhotoDrawable extends Drawable {
  private final String text;
  private final int intrinsicSize;
  private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

  ContactPhotoDrawable(@Nullable String text, int backgroundColor, int textColor, int intrinsicSize) {
    this.text = text;
    this.intrinsicSize = intrinsicSize;
    backgroundPaint.setColor(backgroundColor);
    textPaint.setColor(textColor);
    textPaint.setTextAlign(Paint.Align.CENTER);
    textPaint.setTypeface(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL));
  }

  @Override
  public void draw(Canvas canvas) {
    Rect bounds = getBounds();
    float centerX = bounds.exactCenterX();
    float centerY = bounds.exactCenterY();
    canvas.drawCircle(centerX, centerY, Math.min(bounds.width(), bounds.height()) / 2f, backgroundPaint);

    if (text != null) {
      textPaint.setTextSize(Math.min(bounds.width(), bounds.height()) / 2f);
      float baseline = centerY - (textPaint.ascent() + textPaint.descent()) / 2f;
      canvas.drawText(text, centerX, baseline, textPaint);
    }
  }

  @Override
  public void setAlpha(int alpha) {
    backgroundPaint.setAlpha(alpha);
    textPaint.setAlpha(alpha);
    invalidateSelf();
  }

  @Override
  public void setColorFilter(@Nullable ColorFilter colorFilter) {
    backgroundPaint.setColorFilter(colorFilter);
    textPaint.setColorFilter(colorFilter);
    invalidateSelf();
  }

  @Override
  public int getOpacity() {
    return PixelFormat.TRANSLUCENT;
  }

  @Override
  public int getIntrinsicWidth() {
    return intrinsicSize;
  }

  @Override
  public int getIntrinsicHeight() {
    return intrinsicSize;
  }
}