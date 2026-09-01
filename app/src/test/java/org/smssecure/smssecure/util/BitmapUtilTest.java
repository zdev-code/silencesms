package org.smssecure.smssecure.util;

import androidx.exifinterface.media.ExifInterface;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class BitmapUtilTest {

  @Test
  public void mapsExifOrientationToClockwiseDegrees() {
    assertThat(BitmapUtil.exifOrientationToDegrees(ExifInterface.ORIENTATION_NORMAL)).isEqualTo(0);
    assertThat(BitmapUtil.exifOrientationToDegrees(ExifInterface.ORIENTATION_ROTATE_90)).isEqualTo(90);
    assertThat(BitmapUtil.exifOrientationToDegrees(ExifInterface.ORIENTATION_ROTATE_180)).isEqualTo(180);
    assertThat(BitmapUtil.exifOrientationToDegrees(ExifInterface.ORIENTATION_ROTATE_270)).isEqualTo(270);
    assertThat(BitmapUtil.exifOrientationToDegrees(ExifInterface.ORIENTATION_UNDEFINED)).isEqualTo(0);
  }
}