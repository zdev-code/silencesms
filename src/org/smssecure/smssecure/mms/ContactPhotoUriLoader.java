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

import org.smssecure.smssecure.mms.ContactPhotoUriLoader.ContactPhotoUri;

import java.io.InputStream;

public class ContactPhotoUriLoader implements ModelLoader<ContactPhotoUri, InputStream> {
  private final Context context;

  /**
   * The default factory for {@link ContactPhotoUriLoader}s.
   */
  public static class Factory implements ModelLoaderFactory<ContactPhotoUri, InputStream> {
    private final Context context;

    public Factory(Context context) {
      this.context = context.getApplicationContext();
    }

    @Override
    public ModelLoader<ContactPhotoUri, InputStream> build(MultiModelLoaderFactory multiFactory) {
      return new ContactPhotoUriLoader(context);
    }

    @Override
    public void teardown() {
      // Do nothing.
    }
  }

  public ContactPhotoUriLoader(Context context) {
    this.context = context.getApplicationContext();
  }

  @Override
  public LoadData<InputStream> buildLoadData(@NonNull ContactPhotoUri model, int width, int height, @NonNull Options options) {
    return new LoadData<>(new ObjectKey(model), createFetcher(model));
  }

  @Override
  public boolean handles(@NonNull ContactPhotoUri model) {
    return true;
  }

  private DataFetcher<InputStream> createFetcher(ContactPhotoUri model) {
    return new ContactPhotoLocalUriFetcher(context, model.uri);
  }

  public static class ContactPhotoUri {
    public @NonNull Uri uri;

    public ContactPhotoUri(@NonNull Uri uri) {
      this.uri = uri;
    }
  }
}

