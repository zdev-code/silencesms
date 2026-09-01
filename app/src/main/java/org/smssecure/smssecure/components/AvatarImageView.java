package org.smssecure.smssecure.components;

import android.content.Context;
import android.content.Intent;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.core.content.ContextCompat;
import android.util.AttributeSet;
import android.view.View;

import org.smssecure.smssecure.R;
import org.smssecure.smssecure.color.MaterialColor;
import org.smssecure.smssecure.contacts.avatars.ContactColors;
import org.smssecure.smssecure.contacts.avatars.ContactPhotoFactory;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.crypto.SessionUtil;
import org.smssecure.smssecure.recipients.Recipient;
import org.smssecure.smssecure.recipients.RecipientFactory;
import org.smssecure.smssecure.recipients.Recipients;
import org.smssecure.smssecure.service.KeyCachingService;
import org.smssecure.smssecure.util.dualsim.SubscriptionInfoCompat;
import org.smssecure.smssecure.util.dualsim.SubscriptionManagerCompat;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AvatarImageView extends AppCompatImageView {

  private boolean inverted;
  private boolean showBadge;

  private static final ExecutorService BADGE_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
    Thread thread = new Thread(runnable, "avatar-badge");
    thread.setDaemon(true);
    return thread;
  });
  private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

  public AvatarImageView(Context context) {
    super(context);
    setScaleType(ScaleType.CENTER_CROP);
  }

  public AvatarImageView(Context context, AttributeSet attrs) {
    super(context, attrs);
    setScaleType(ScaleType.CENTER_CROP);

    if (attrs != null) {
  TypedArray typedArray = context.getTheme().obtainStyledAttributes(attrs, R.styleable.AvatarImageView, 0, 0);
  inverted = typedArray.getBoolean(R.styleable.AvatarImageView_inverted, false);
  showBadge = typedArray.getBoolean(R.styleable.AvatarImageView_showBadge, false);
      typedArray.recycle();
    }
  }

  public void setAvatar(final @Nullable Recipients recipients, boolean quickContactEnabled) {
    if (recipients != null) {
      Context       context         = getContext();
      MasterSecret  masterSecret    = KeyCachingService.getMasterSecret(context);
      MaterialColor backgroundColor = recipients.getColor();

      setImageDrawable(recipients.getContactPhoto().asDrawable(getContext(), backgroundColor.toConversationColor(getContext()), inverted));
      setAvatarClickHandler(recipients, quickContactEnabled);
      setTag(recipients);
      if (showBadge) {
        submitBadgeResolution(context.getApplicationContext(), masterSecret, recipients);
      }
    } else {
      setImageDrawable(ContactPhotoFactory.getDefaultContactPhoto(null).asDrawable(getContext(), ContactColors.UNKNOWN_COLOR.toConversationColor(getContext()), inverted));
      setOnClickListener(null);
      setTag(null);
    }
  }

  public void setAvatar(@Nullable Recipient recipient, boolean quickContactEnabled) {
    setAvatar(RecipientFactory.getRecipientsFor(getContext(), recipient, true), quickContactEnabled);
  }

  private void setAvatarClickHandler(final Recipients recipients, boolean quickContactEnabled) {
    if (!recipients.isGroupRecipient() && quickContactEnabled) {
      setOnClickListener(new View.OnClickListener() {
        @Override
        public void onClick(View v) {
          Recipient recipient = recipients.getPrimaryRecipient();

          if (recipient != null && recipient.getContactUri() != null) {
            ContactsContract.QuickContact.showQuickContact(getContext(), AvatarImageView.this, recipient.getContactUri(), ContactsContract.QuickContact.MODE_LARGE, null);
          } else if (recipient != null) {
            final Intent intent = new Intent(Intent.ACTION_INSERT_OR_EDIT);
            intent.putExtra(ContactsContract.Intents.Insert.PHONE, recipient.getNumber());
            intent.setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE);
            getContext().startActivity(intent);
          }
        }
      });
    } else {
      setOnClickListener(null);
    }
  }

  private void submitBadgeResolution(Context context, MasterSecret masterSecret, Recipients recipients) {
    BADGE_EXECUTOR.execute(() -> {
      List<SubscriptionInfoCompat> activeSubscriptions = SubscriptionManagerCompat.from(context).getActiveSubscriptionInfoList();
      boolean hasSecureSession = masterSecret != null &&
                                 recipients.getPrimaryRecipient() != null &&
                                 SessionUtil.hasAtLeastOneSession(context, masterSecret,
                                                                  recipients.getPrimaryRecipient().getNumber(),
                                                                  activeSubscriptions);

      MAIN_HANDLER.post(() -> applyBadgeResult(recipients, hasSecureSession));
    });
  }

  private void applyBadgeResult(Recipients recipients, boolean hasSecureSession) {
    if (!hasSecureSession || getTag() != recipients) {
      return;
    }

    Drawable badgeDrawable = ContextCompat.getDrawable(getContext(), R.drawable.badge_drawable);
    Drawable current = getDrawable();

    if (badgeDrawable == null || current == null) {
      return;
    }

    Drawable badged = new LayerDrawable(new Drawable[] { current, badgeDrawable });
    setImageDrawable(badged);
  }
}
