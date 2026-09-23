package org.smssecure.smssecure.preferences.widgets;

import android.content.Context;
import android.net.Uri;
import android.util.AttributeSet;


public class AdvancedRingtonePreference extends RingtonePreference {

  private Uri currentRingtone;

  public AdvancedRingtonePreference(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
  }
  public AdvancedRingtonePreference(Context context, AttributeSet attrs) {
    super(context, attrs);
  }
  public AdvancedRingtonePreference(Context context) {
    super(context);
  }

  public AdvancedRingtonePreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
    super(context, attrs, defStyleAttr, defStyleRes);
  }

  @Override
  protected Uri onRestoreRingtone() {
    if (currentRingtone == null) return super.onRestoreRingtone();
    else                         return currentRingtone;
  }

  public void setCurrentRingtone(Uri uri) {
    currentRingtone = uri;
  }


}
