package org.smssecure.smssecure.contacts.avatars;

import android.content.Context;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;

import com.amulyakhare.textdrawable.TextDrawable;

import androidx.annotation.DrawableRes;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.graphics.BlendModeColorFilterCompat;
import androidx.core.graphics.BlendModeCompat;
import androidx.core.graphics.ColorUtils;

public class ResourceContactPhoto implements ContactPhoto {

  private final int resourceId;

  ResourceContactPhoto(@DrawableRes int resourceId) {
    this.resourceId = resourceId;
  }

  @Override
  public Drawable asDrawable(Context context, int color) {
    return asDrawable(context, color, false);
  }

  @Override
  public Drawable asDrawable(Context context, int color, boolean inverted) {
    Drawable background = TextDrawable.builder().buildRound(" ", inverted ? Color.WHITE : color);
    Drawable source     = AppCompatResources.getDrawable(context, resourceId);

    if (source == null) {
      return background;
    }

    source = source.mutate();

    if (inverted) {
      source.setColorFilter(BlendModeColorFilterCompat.createBlendModeColorFilterCompat(color, BlendModeCompat.SRC_ATOP));
    }

    // Draw the icon centred at its intrinsic size over the round background (replaces the former
    // RoundedDrawable ScaleType.CENTER behaviour with a native LayerDrawable, API 23+).
    ExpandingLayerDrawable layers = new ExpandingLayerDrawable(new Drawable[] {background, source});
    layers.setLayerGravity(1, Gravity.CENTER);
    layers.setLayerSize(1, source.getIntrinsicWidth(), source.getIntrinsicHeight());
    return layers;
  }

  private static class ExpandingLayerDrawable extends LayerDrawable {
    public ExpandingLayerDrawable(Drawable[] layers) {
      super(layers);
    }

    @Override
    public int getIntrinsicWidth() {
      return -1;
    }

    @Override
    public int getIntrinsicHeight() {
      return -1;
    }
  }

}
