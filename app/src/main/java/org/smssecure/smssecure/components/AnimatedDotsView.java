package org.smssecure.smssecure.components;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatTextView;

/**
 * A small, self-contained animated ellipsis used as the "pending" indicator on outgoing messages.
 * Replaces the third-party pl.tajchert:waitingdots DotsTextView with native functionality: it cycles
 * ".", "..", "..." while attached to the window.
 */
public class AnimatedDotsView extends AppCompatTextView {

  private static final long     PERIOD_MS = 450L;
  private static final String[] FRAMES    = {".", "..", "..."};

  private final Handler handler = new Handler(Looper.getMainLooper());
  private int index = 0;

  private final Runnable ticker = new Runnable() {
    @Override
    public void run() {
      setText(FRAMES[index % FRAMES.length]);
      index++;
      handler.postDelayed(this, PERIOD_MS);
    }
  };

  public AnimatedDotsView(Context context) {
    super(context);
  }

  public AnimatedDotsView(Context context, AttributeSet attrs) {
    super(context, attrs);
  }

  public AnimatedDotsView(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
  }

  public void setDotsColor(int color) {
    setTextColor(color);
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    index = 0;
    handler.post(ticker);
  }

  @Override
  protected void onDetachedFromWindow() {
    handler.removeCallbacks(ticker);
    super.onDetachedFromWindow();
  }
}
