package org.smssecure.smssecure.mms;

import android.content.Context;
import android.net.Uri;
import androidx.annotation.NonNull;

import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.data.DataFetcher;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.model.ModelLoaderFactory;
import com.bumptech.glide.load.model.MultiModelLoaderFactory;
import com.bumptech.glide.signature.ObjectKey;

import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.mms.DecryptableStreamUriLoader.DecryptableUri;

import java.io.InputStream;

/**
 * A {@link ModelLoader} for translating decryptable Uris into {@link InputStream} data.
 */
public class DecryptableStreamUriLoader implements ModelLoader<DecryptableUri, InputStream> {
  private final Context context;

  /**
   * The default factory for {@link DecryptableStreamUriLoader}s.
   */
  public static class Factory implements ModelLoaderFactory<DecryptableUri, InputStream> {
    private final Context context;

    public Factory(Context context) {
      this.context = context.getApplicationContext();
    }

    @Override
    public ModelLoader<DecryptableUri, InputStream> build(MultiModelLoaderFactory multiFactory) {
      return new DecryptableStreamUriLoader(context);
    }

    @Override
    public void teardown() {
      // Do nothing.
    }
  }

  public DecryptableStreamUriLoader(Context context) {
    this.context = context.getApplicationContext();
  }

  @Override
  public LoadData<InputStream> buildLoadData(@NonNull DecryptableUri model, int width, int height, @NonNull Options options) {
    return new LoadData<>(new ObjectKey(model), createFetcher(model));
  }

  @Override
  public boolean handles(@NonNull DecryptableUri model) {
    return true;
  }

  private DataFetcher<InputStream> createFetcher(DecryptableUri model) {
    return new DecryptableStreamLocalUriFetcher(context, model.masterSecret, model.uri);
  }

  public static class DecryptableUri {
    public @NonNull MasterSecret masterSecret;
    public @NonNull Uri          uri;

    public DecryptableUri(@NonNull MasterSecret masterSecret, @NonNull Uri uri) {
      this.masterSecret = masterSecret;
      this.uri          = uri;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      DecryptableUri that = (DecryptableUri)o;

      return uri.equals(that.uri);

    }

    @Override
    public int hashCode() {
      return uri.hashCode();
    }
  }
}

