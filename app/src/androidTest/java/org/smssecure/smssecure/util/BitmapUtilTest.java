package org.smssecure.smssecure.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import androidx.exifinterface.media.ExifInterface;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.smssecure.smssecure.mms.MediaConstraints;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

@RunWith(AndroidJUnit4.class)
public class BitmapUtilTest {

  @Test
  public void createScaledBytesBakesExifRotationIntoPixels() throws Exception {
    Context context = getInstrumentation().getTargetContext();
    File source = new File(context.getCacheDir(), "exif-rotated.jpg");
    Bitmap bitmap = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888);

    try (FileOutputStream outputStream = new FileOutputStream(source)) {
      bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream);
    } finally {
      bitmap.recycle();
    }

    ExifInterface exif = new ExifInterface(source);
    exif.setAttribute(ExifInterface.TAG_ORIENTATION,
                      String.valueOf(ExifInterface.ORIENTATION_ROTATE_90));
    exif.saveAttributes();

    byte[] output = BitmapUtil.createScaledBytes(context, source, new TestMediaConstraints());
    Bitmap decoded = BitmapFactory.decodeByteArray(output, 0, output.length);

    try {
      assertThat(decoded.getWidth()).isEqualTo(20);
      assertThat(decoded.getHeight()).isEqualTo(40);
      assertThat(BitmapUtil.getExifRotation(new ByteArrayInputStream(output))).isEqualTo(0);
    } finally {
      decoded.recycle();
      source.delete();
    }
  }

  private static class TestMediaConstraints extends MediaConstraints {
    @Override public int getImageMaxWidth(Context context)  { return 100; }
    @Override public int getImageMaxHeight(Context context) { return 100; }
    @Override public int getImageMaxSize(Context context)   { return 100_000; }
    @Override public int getGifMaxSize(Context context)     { return 100_000; }
    @Override public int getVideoMaxSize(Context context)   { return 100_000; }
    @Override public int getAudioMaxSize(Context context)   { return 100_000; }
  }
}