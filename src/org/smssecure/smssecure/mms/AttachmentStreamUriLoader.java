package org.smssecure.smssecure.mms;

import android.content.Context;
import androidx.annotation.NonNull;

import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.data.DataFetcher;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.model.ModelLoaderFactory;
import com.bumptech.glide.load.model.MultiModelLoaderFactory;
import com.bumptech.glide.signature.ObjectKey;

import org.smssecure.smssecure.mms.AttachmentStreamUriLoader.AttachmentModel;

import java.io.File;
import java.io.InputStream;

/**
 * A {@link ModelLoader} for translating attachment models into {@link InputStream} data.
 */
public class AttachmentStreamUriLoader implements ModelLoader<AttachmentModel, InputStream> {
  private final Context context;

  /**
   * The default factory for {@link AttachmentStreamUriLoader}s.
   */
  public static class Factory implements ModelLoaderFactory<AttachmentModel, InputStream> {
    private final Context context;

    public Factory(Context context) {
      this.context = context.getApplicationContext();
    }

    @Override
    public ModelLoader<AttachmentModel, InputStream> build(MultiModelLoaderFactory multiFactory) {
      return new AttachmentStreamUriLoader(context);
    }

    @Override
    public void teardown() {
      // Do nothing.
    }
  }

  public AttachmentStreamUriLoader(Context context) {
    this.context = context.getApplicationContext();
  }

  @Override
  public LoadData<InputStream> buildLoadData(@NonNull AttachmentModel model, int width, int height, @NonNull Options options) {
  return new LoadData<>(new ObjectKey(model), createFetcher(model));
  }

  @Override
  public boolean handles(@NonNull AttachmentModel model) {
    return true;
  }

  private DataFetcher<InputStream> createFetcher(AttachmentModel model) {
    return new AttachmentStreamLocalUriFetcher(model.attachment, model.key);
  }

  public static class AttachmentModel {
    public @NonNull File   attachment;
    public @NonNull byte[] key;

    public AttachmentModel(@NonNull File attachment, @NonNull byte[] key) {
      this.attachment = attachment;
      this.key        = key;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      AttachmentModel that = (AttachmentModel)o;

      return attachment.equals(that.attachment);

    }

    @Override
    public int hashCode() {
      return attachment.hashCode();
    }
  }
}

