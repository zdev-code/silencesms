package org.smssecure.smssecure.contacts.avatars;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;

import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;

public class BitmapContactPhoto implements ContactPhoto {

  private final Bitmap bitmap;

  BitmapContactPhoto(Bitmap bitmap) {
    this.bitmap = bitmap;
  }

  @Override
  public Drawable asDrawable(Context context, int color) {
    return asDrawable(context, color, false);
  }

  @Override
  public Drawable asDrawable(Context context, int color, boolean inverted) {
    RoundedBitmapDrawable drawable = RoundedBitmapDrawableFactory.create(context.getResources(), bitmap);
    drawable.setCircular(true);
    return drawable;
  }
}
