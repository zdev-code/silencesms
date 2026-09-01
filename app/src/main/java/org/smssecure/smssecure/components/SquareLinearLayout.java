package org.smssecure.smssecure.components;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.LinearLayout;

public class SquareLinearLayout extends LinearLayout {
  @SuppressWarnings("unused")
  public SquareLinearLayout(Context context) {
    super(context);
  }

  @SuppressWarnings("unused")
  public SquareLinearLayout(Context context, AttributeSet attrs) {
    super(context, attrs);
  }

  @SuppressWarnings("unused")
  public SquareLinearLayout(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
  }

  @SuppressWarnings("unused")
  public SquareLinearLayout(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
    super(context, attrs, defStyleAttr, defStyleRes);
  }

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    //noinspection SuspiciousNameCombination
    super.onMeasure(widthMeasureSpec, widthMeasureSpec);
  }
}
