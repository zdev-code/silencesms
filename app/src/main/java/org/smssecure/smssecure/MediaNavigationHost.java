package org.smssecure.smssecure;

import android.net.Uri;

interface MediaNavigationHost {
  void openMediaOverview(long threadId, long recipientId);
  void openMediaPreview(long partRowId, long partUniqueId, long messageId, long threadId,
                        long recipientId, long date, long size);
  void openDraftMediaPreview(Uri uri, String contentType, long size);
  void closeMediaPreview();
}