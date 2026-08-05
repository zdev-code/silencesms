package org.smssecure.smssecure.contacts.avatars;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import androidx.annotation.NonNull;

import org.smssecure.smssecure.R;

public class GeneratedContactPhoto implements ContactPhoto {

  private final String name;

  GeneratedContactPhoto(@NonNull String name) {
    this.name  = name;
  }

  @Override
  public Drawable asDrawable(Context context, int color) {
    return asDrawable(context, color, false);
  }

  @Override
  public Drawable asDrawable(Context context, int color, boolean inverted) {
    int targetSize = context.getResources().getDimensionPixelSize(R.dimen.contact_photo_target_size);

    return new ContactPhotoDrawable(getCharacter(name),
                    inverted ? Color.WHITE : color,
                    inverted ? color : Color.WHITE,
                    targetSize);
  }

  private String getCharacter(String name) {
    String cleanedName = name.replaceFirst("[^\\p{L}\\p{Nd}\\p{P}\\p{S}]+", "");

    if (cleanedName.isEmpty()) {
      return "#";
    } else {
      return String.valueOf(cleanedName.charAt(0));
    }
  }
}
