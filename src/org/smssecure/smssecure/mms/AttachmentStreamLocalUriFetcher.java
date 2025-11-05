package org.smssecure.smssecure.mms;

import android.util.Log;

import androidx.annotation.NonNull;

import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.data.DataFetcher;

import org.smssecure.smssecure.crypto.AttachmentCipherInputStream;
import org.whispersystems.libsignal.util.guava.Optional;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

public class AttachmentStreamLocalUriFetcher implements DataFetcher<InputStream> {
  private static final String TAG = AttachmentStreamLocalUriFetcher.class.getSimpleName();
  private File        attachment;
  private byte[]      key;
  private InputStream is;

  public AttachmentStreamLocalUriFetcher(File attachment, byte[] key) {
    this.attachment = attachment;
    this.key        = key;
  }

  @Override
  public void loadData(@NonNull Priority priority, @NonNull DataCallback<? super InputStream> callback) {
    try {
      is = new AttachmentCipherInputStream(attachment, key, Optional.<byte[]>absent());
      callback.onDataReady(is);
    } catch (Exception e) {
      callback.onLoadFailed(e);
    }
  }

  @Override public void cleanup() {
    try {
      if (is != null) is.close();
      is = null;
    } catch (IOException ioe) {
      Log.w(TAG, "ioe");
    }
  }

  @Override public void cancel() {

  }

  @NonNull
  @Override
  public Class<InputStream> getDataClass() {
    return InputStream.class;
  }

  @NonNull
  @Override
  public DataSource getDataSource() {
    return DataSource.LOCAL;
  }
}
