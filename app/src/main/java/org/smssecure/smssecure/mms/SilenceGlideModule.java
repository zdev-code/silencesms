package org.smssecure.smssecure.mms;

import android.content.Context;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.Registry;
import com.bumptech.glide.load.engine.cache.DiskCache;
import com.bumptech.glide.load.engine.cache.DiskCacheAdapter;
import com.bumptech.glide.module.AppGlideModule;

import org.smssecure.smssecure.mms.AttachmentStreamUriLoader.AttachmentModel;
import org.smssecure.smssecure.mms.ContactPhotoUriLoader.ContactPhotoUri;
import org.smssecure.smssecure.mms.DecryptableStreamUriLoader.DecryptableUri;

import java.io.InputStream;

@GlideModule
public class SilenceGlideModule extends AppGlideModule {

  @Override
  public void applyOptions(@NonNull Context context, @NonNull GlideBuilder builder) {
    builder.setDiskCache(new NoopDiskCacheFactory());
  }

  @Override
  public void registerComponents(@NonNull Context context, @NonNull Glide glide, @NonNull Registry registry) {
    registry.append(DecryptableUri.class, InputStream.class, new DecryptableStreamUriLoader.Factory(context));
    registry.append(ContactPhotoUri.class, InputStream.class, new ContactPhotoUriLoader.Factory(context));
    registry.append(AttachmentModel.class, InputStream.class, new AttachmentStreamUriLoader.Factory(context));
  }

  @Override
  public boolean isManifestParsingEnabled() {
    return false;
  }

  public static class NoopDiskCacheFactory implements DiskCache.Factory {
    @Override
    public DiskCache build() {
      return new DiskCacheAdapter();
    }
  }
}
