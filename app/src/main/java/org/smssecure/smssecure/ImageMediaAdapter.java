/**
 * Copyright (C) 2015 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.smssecure.smssecure;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;

import org.smssecure.smssecure.ImageMediaAdapter.ViewHolder;
import org.smssecure.smssecure.components.ThumbnailView;
import org.smssecure.smssecure.attachments.AttachmentId;
import org.smssecure.smssecure.database.ImageDatabase.ImageRecord;
import org.smssecure.smssecure.domain.security.UnlockSession;
import org.smssecure.smssecure.mms.Slide;
import org.smssecure.smssecure.recipients.RecipientFactory;
import org.smssecure.smssecure.recipients.Recipients;
import org.smssecure.smssecure.util.MediaUtil;

import java.util.List;

public class ImageMediaAdapter extends RecyclerView.Adapter<ViewHolder> {
  private static final String TAG = ImageMediaAdapter.class.getSimpleName();

  private final UnlockSession unlockSession;
  private final long         threadId;
  private final Context      context;
  private final List<ImageRecord> records;
  private final MediaClickListener clickListener;

  public interface MediaClickListener {
    void onMediaClick(long partRowId, long partUniqueId, long messageId, long threadId,
                      long recipientId, long date, long size);
  }

  public static class ViewHolder extends RecyclerView.ViewHolder {
    public ThumbnailView imageView;

    public ViewHolder(View v) {
      super(v);
      imageView = (ThumbnailView) v.findViewById(R.id.image);
    }
  }

  public ImageMediaAdapter(Context context, UnlockSession unlockSession, List<ImageRecord> records,
                           long threadId, MediaClickListener clickListener) {
    this.context = context;
    this.unlockSession = unlockSession;
    this.records = List.copyOf(records);
    this.threadId     = threadId;
    this.clickListener = clickListener;
  }

  @Override
  public ViewHolder onCreateViewHolder(final ViewGroup viewGroup, final int i) {
    final View view = LayoutInflater.from(context).inflate(R.layout.media_overview_item, viewGroup, false);
    return new ViewHolder(view);
  }

  @Override
  public void onBindViewHolder(final ViewHolder viewHolder, int position) {
    final ThumbnailView imageView   = viewHolder.imageView;
    final ImageRecord imageRecord = records.get(position);

    Slide slide = MediaUtil.getSlideForAttachment(context, imageRecord.getAttachment());

    if (slide != null) {
      try {
        unlockSession.use(masterSecret -> {
          imageView.setImageResource(masterSecret, slide, false);
          return null;
        });
      } catch (Exception error) {
        imageView.clear();
      }
    }

    imageView.setOnClickListener(new OnMediaClickListener(imageRecord));
  }

  @Override public int getItemCount() { return records.size(); }

  @Override public void onViewRecycled(@NonNull ViewHolder holder) {
    holder.imageView.clear();
  }

  private class OnMediaClickListener implements OnClickListener {
    private final ImageRecord imageRecord;

    private OnMediaClickListener(ImageRecord imageRecord) {
      this.imageRecord = imageRecord;
    }

    @Override
    public void onClick(View v) {
      long recipientId = -1L;
      if (!TextUtils.isEmpty(imageRecord.getAddress())) {
        Recipients recipients = RecipientFactory.getRecipientsFromString(context,
                                                                         imageRecord.getAddress(),
                                                                         true);
        if (recipients != null && recipients.getPrimaryRecipient() != null) {
          recipientId = recipients.getPrimaryRecipient().getRecipientId();
        }
      }
      AttachmentId attachmentId = imageRecord.getAttachmentId();
      clickListener.onMediaClick(attachmentId.getRowId(), attachmentId.getUniqueId(),
          imageRecord.getMmsId(), threadId, recipientId, imageRecord.getDate(), imageRecord.getSize());
    }
  }
}
