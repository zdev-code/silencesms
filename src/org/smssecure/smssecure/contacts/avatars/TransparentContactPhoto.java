package org.smssecure.smssecure.contacts.avatars;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;

import com.makeramen.roundedimageview.RoundedDrawable;

import androidx.appcompat.content.res.AppCompatResources;

public class TransparentContactPhoto implements ContactPhoto {

  TransparentContactPhoto() {}

  @Override
  public Drawable asDrawable(Context context, int color) {
    return asDrawable(context, color, false);
  }

  @Override
  public Drawable asDrawable(Context context, int color, boolean inverted) {
    Drawable source = AppCompatResources.getDrawable(context, android.R.color.transparent);
    if (source == null) {
      source = new ColorDrawable(Color.TRANSPARENT);
    }

    return RoundedDrawable.fromDrawable(source);
  }
}
