package com.takisoft.colorpicker;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import androidx.core.graphics.drawable.DrawableCompat;

import java.util.Objects;

/**
 * A very small substitute for the original library's {@code ColorStateDrawable}.
 * <p>
 * It simply wraps the provided drawables and applies a tint so the widget can
 * preview the selected color.
 */
public class ColorStateDrawable extends LayerDrawable {

    private int color;

    public ColorStateDrawable(Drawable[] layers, int color) {
        super(Objects.requireNonNull(layers, "layers == null"));
        setColor(color);
    }

    public void setColor(int color) {
        this.color = color;
        for (int i = 0; i < getNumberOfLayers(); i++) {
            Drawable drawable = getDrawable(i);
            if (drawable != null) {
                drawable = drawable.mutate();
                DrawableCompat.setTint(drawable, color);
            }
        }
        invalidateSelf();
    }

    public int getColor() {
        return color;
    }

    @Override
    public void draw(Canvas canvas) {
        super.draw(canvas);
    }

    @Override
    public void setAlpha(int alpha) {
        for (int i = 0; i < getNumberOfLayers(); i++) {
            Drawable drawable = getDrawable(i);
            if (drawable != null) {
                drawable.setAlpha(alpha);
            }
        }
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        for (int i = 0; i < getNumberOfLayers(); i++) {
            Drawable drawable = getDrawable(i);
            if (drawable != null) {
                drawable.setColorFilter(colorFilter);
            }
        }
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

}
