package org.smssecure.smssecure.components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.Nullable;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Pair;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.davemorrissey.labs.subscaleview.ImageSource;
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView;

import org.smssecure.smssecure.R;
import org.smssecure.smssecure.components.subsampling.AttachmentBitmapDecoder;
import org.smssecure.smssecure.components.subsampling.AttachmentRegionDecoder;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.mms.DecryptableStreamUriLoader.DecryptableUri;
import org.smssecure.smssecure.mms.PartAuthority;
import org.smssecure.smssecure.util.BitmapDecodingException;
import org.smssecure.smssecure.util.BitmapUtil;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import uk.co.senab.photoview.PhotoViewAttacher;

public class ZoomingImageView extends FrameLayout {

  private static final String TAG = ZoomingImageView.class.getName();

  private static final ExecutorService DIMENSION_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
    Thread thread = new Thread(r, "zooming-image-dimensions");
    thread.setDaemon(true);
    return thread;
  });
  private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

  private final ImageView                 imageView;
  private final PhotoViewAttacher         imageViewAttacher;
  private final SubsamplingScaleImageView subsamplingImageView;

  public ZoomingImageView(Context context) {
    this(context, null);
  }

  public ZoomingImageView(Context context, AttributeSet attrs) {
    this(context, attrs, 0);
  }

  public ZoomingImageView(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);

    inflate(context, R.layout.zooming_image_view, this);

    this.imageView            = (ImageView) findViewById(R.id.image_view);
    this.subsamplingImageView = (SubsamplingScaleImageView) findViewById(R.id.subsampling_image_view);
    this.imageViewAttacher     = new PhotoViewAttacher(imageView);

    this.subsamplingImageView.setBitmapDecoderClass(AttachmentBitmapDecoder.class);
    this.subsamplingImageView.setRegionDecoderClass(AttachmentRegionDecoder.class);
    this.subsamplingImageView.setOrientation(SubsamplingScaleImageView.ORIENTATION_USE_EXIF);
  }

  public void setImageUri(final MasterSecret masterSecret, final Uri uri, final String contentType) {
    final Context context        = getContext();
    final int     maxTextureSize = BitmapUtil.getMaxTextureSize();

    Log.w(TAG, "Max texture size: " + maxTextureSize);

    DIMENSION_EXECUTOR.execute(() -> {
      Pair<Integer, Integer> dimensions = null;

      if (!"image/gif".equals(contentType)) {
        try {
          InputStream inputStream = PartAuthority.getAttachmentStream(context, masterSecret, uri);
          dimensions = BitmapUtil.getDimensions(inputStream);
        } catch (IOException | BitmapDecodingException e) {
          Log.w(TAG, e);
        }
      }

      Pair<Integer, Integer> finalDimensions = dimensions;
      MAIN_HANDLER.post(() -> {
        Log.w(TAG, "Dimensions: " + (finalDimensions == null ? "(null)" : finalDimensions.first + ", " + finalDimensions.second));

        if (finalDimensions == null || (finalDimensions.first <= maxTextureSize && finalDimensions.second <= maxTextureSize)) {
          Log.w(TAG, "Loading in standard image view...");
          setImageViewUri(masterSecret, uri);
        } else {
          Log.w(TAG, "Loading in subsampling image view...");
          setSubsamplingImageViewUri(uri);
        }
      });
    });
  }

  private void setImageViewUri(MasterSecret masterSecret, Uri uri) {
    subsamplingImageView.setVisibility(View.GONE);
    imageView.setVisibility(View.VISIBLE);

    Glide.with(getContext())
         .load(new DecryptableUri(masterSecret, uri))
         .diskCacheStrategy(DiskCacheStrategy.NONE)
         .dontTransform()
         .dontAnimate()
         .listener(new RequestListener<Drawable>() {
           @Override
           public boolean onLoadFailed(@Nullable GlideException e,
                                       Object model,
                                       Target<Drawable> target,
                                       boolean isFirstResource) {
             return false;
           }

           @Override
           public boolean onResourceReady(Drawable resource,
                                          Object model,
                                          Target<Drawable> target,
                                          DataSource dataSource,
                                          boolean isFirstResource) {
             imageViewAttacher.update();
             return false;
           }
         })
         .into(imageView);
  }

  private void setSubsamplingImageViewUri(Uri uri) {
    subsamplingImageView.setVisibility(View.VISIBLE);
    imageView.setVisibility(View.GONE);

    subsamplingImageView.setImage(ImageSource.uri(uri));
  }


  public void cleanup() {
    imageView.setImageDrawable(null);
    subsamplingImageView.recycle();
  }
}
