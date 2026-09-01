package com.takisoft.colorpicker;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ScrollView;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Minimal dialog that mimics the essential API of the legacy Takisoft color picker.
 */
public class ColorPickerDialog extends AlertDialog {

    public static final int SIZE_LARGE = 1;
    public static final int SIZE_SMALL = 2;

    @IntDef({SIZE_SMALL, SIZE_LARGE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Size {}

    private final OnColorSelectedListener listener;
    private final Params params;
    private final int[] colors;
    private final CharSequence[] descriptions;

    private int selectedColor;

    public ColorPickerDialog(@NonNull Activity activity,
                             @NonNull OnColorSelectedListener listener,
                             @NonNull Params params) {
        super(activity);
        this.listener = listener;
        this.params = params.copy();

        this.colors = normaliseColors(this.params);
        this.descriptions = normaliseDescriptions(this.params, this.colors.length);
        this.selectedColor = determineInitialColor(this.params.selectedColor, this.colors);

        setButton(DialogInterface.BUTTON_NEGATIVE,
                activity.getString(android.R.string.cancel),
                (dialog, which) -> cancel());
        setButton(DialogInterface.BUTTON_POSITIVE,
                activity.getString(android.R.string.ok),
                (dialog, which) -> {
                    if (ColorPickerDialog.this.listener != null) {
                        ColorPickerDialog.this.listener.onColorSelected(selectedColor);
                    }
                });

        setCanceledOnTouchOutside(true);
        setView(buildContentView(activity));
    }

    private static int[] normaliseColors(Params params) {
        int[] base = params.colors != null ? params.colors.clone() : new int[0];
        if (base.length == 0) {
            int fallback = params.selectedColor != 0 ? params.selectedColor : Color.BLUE;
            base = new int[]{fallback};
        }

        if (params.sortColors && base.length > 1) {
            Integer[] values = new Integer[base.length];
            for (int i = 0; i < base.length; i++) {
                values[i] = base[i];
            }
            Arrays.sort(values, new Comparator<Integer>() {
                @Override
                public int compare(Integer left, Integer right) {
                    int hueComparison = Double.compare(calculateHue(left), calculateHue(right));
                    if (hueComparison != 0) {
                        return hueComparison;
                    }

                    int saturationComparison = Double.compare(calculateSaturation(left), calculateSaturation(right));
                    if (saturationComparison != 0) {
                        return saturationComparison;
                    }

                    return Double.compare(calculateValue(left), calculateValue(right));
                }
            });
            for (int i = 0; i < base.length; i++) {
                base[i] = values[i];
            }
        }
        return base;
    }

    private static CharSequence[] normaliseDescriptions(Params params, int count) {
        CharSequence[] copy = params.colorDescriptions != null
                ? params.colorDescriptions.clone()
                : new CharSequence[count];
        if (copy.length < count) {
            CharSequence[] extended = new CharSequence[count];
            System.arraycopy(copy, 0, extended, 0, copy.length);
            copy = extended;
        }
        return copy;
    }

    private static int determineInitialColor(int requested, int[] colors) {
        if (requested != 0) {
            return requested;
        }
        return colors[0];
    }

    private View buildContentView(Context context) {
        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);

        GridLayout grid = new GridLayout(context);
        int columns = params.columns > 0 ? params.columns : Math.max(1, (int) Math.ceil(Math.sqrt(colors.length)));
        grid.setColumnCount(columns);
        grid.setUseDefaultMargins(true);
        int padding = spacingPx(context);
        grid.setPadding(padding, padding, padding, padding);

        for (int i = 0; i < colors.length; i++) {
            int color = colors[i];
            CharSequence description = descriptions.length > i ? descriptions[i] : null;
            grid.addView(createSwatch(context, color, description));
        }

        scrollView.addView(grid,
                new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
        return scrollView;
    }

    private View createSwatch(Context context, int color, CharSequence description) {
        FrameLayout container = new FrameLayout(context);
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        int size = resolveSwatchSize(context, params.size);
        lp.width = size;
        lp.height = size;
        lp.setGravity(Gravity.CENTER);
        container.setLayoutParams(lp);

        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        drawable.setStroke(Math.max(2, spacingPx(context) / 4), Color.WHITE);

        View swatch = new View(context);
        swatch.setBackground(drawable);
        swatch.setContentDescription(description);
        swatch.setFocusable(true);
        swatch.setClickable(true);
        swatch.setOnClickListener(v -> {
            selectedColor = color;
            if (listener != null) {
                listener.onColorSelected(color);
            }
            dismiss();
        });

        FrameLayout.LayoutParams childParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        childParams.gravity = Gravity.CENTER;
        int margin = Math.max(2, spacingPx(context) / 4);
        childParams.setMargins(margin, margin, margin, margin);
        container.addView(swatch, childParams);

        return container;
    }

    private static int spacingPx(Context context) {
        return Math.round(dpToPx(context, 8));
    }

    private static int resolveSwatchSize(Context context, @Size int size) {
        int dp = size == SIZE_LARGE ? 56 : 40;
        return Math.round(dpToPx(context, dp));
    }

    private static float dpToPx(Context context, int dp) {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, metrics);
    }

    private static float calculateHue(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        return hsv[0];
    }

    private static float calculateSaturation(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        return hsv[1];
    }

    private static float calculateValue(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        return hsv[2];
    }

    public static class Params {
        int selectedColor;
        int[] colors;
        CharSequence[] colorDescriptions;
        @Size
        int size = SIZE_SMALL;
        boolean sortColors;
        int columns = 4;

        Params copy() {
            Params copy = new Params();
            copy.selectedColor = this.selectedColor;
            copy.colors = this.colors != null ? this.colors.clone() : null;
            copy.colorDescriptions = this.colorDescriptions != null ? this.colorDescriptions.clone() : null;
            copy.size = this.size;
            copy.sortColors = this.sortColors;
            copy.columns = this.columns;
            return copy;
        }

        public static class Builder {
            private final Params params = new Params();

            public Builder(Context context) {
                // Context kept for parity with the original API.
            }

            public Builder setSelectedColor(int color) {
                params.selectedColor = color;
                return this;
            }

            public Builder setColors(int[] colors) {
                params.colors = colors;
                return this;
            }

            public Builder setColorContentDescriptions(CharSequence[] descriptions) {
                params.colorDescriptions = descriptions;
                return this;
            }

            public Builder setSize(@Size int size) {
                params.size = size;
                return this;
            }

            public Builder setSortColors(boolean sort) {
                params.sortColors = sort;
                return this;
            }

            public Builder setColumns(int columns) {
                params.columns = columns;
                return this;
            }

            public Params build() {
                return params.copy();
            }
        }
    }
}
