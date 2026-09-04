/**
 * Copyright (C) 2011 Whisper Systems
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
package org.smssecure.smssecure.providers;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import androidx.annotation.NonNull;
import android.util.Log;

import org.smssecure.smssecure.attachments.AttachmentId;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.database.DatabaseFactory;
import org.smssecure.smssecure.domain.security.UnlockSession;
import org.smssecure.smssecure.mms.PartUriParser;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

public class PartProvider extends ContentProvider {
  private static final String TAG = PartProvider.class.getSimpleName();

  private static final String CONTENT_URI_STRING = "content://org.smssecure.provider.smssecure/part";
  private static final Uri    CONTENT_URI        = Uri.parse(CONTENT_URI_STRING);
  private static final int    SINGLE_ROW         = 1;

  private static final UriMatcher uriMatcher;

  static {
    uriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
    uriMatcher.addURI("org.smssecure.provider.smssecure", "part/*/#", SINGLE_ROW);
  }

  @Override
  public boolean onCreate() {
    Log.w(TAG, "onCreate()");
    return true;
  }

  public static Uri getContentUri(AttachmentId attachmentId) {
    Uri uri = Uri.withAppendedPath(CONTENT_URI, String.valueOf(attachmentId.getUniqueId()));
    return ContentUris.withAppendedId(uri, attachmentId.getRowId());
  }

  @Override
  public ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode) throws FileNotFoundException {
    if (!"r".equals(mode)) throw new FileNotFoundException("Part provider is read-only");

    switch (uriMatcher.match(uri)) {
    case SINGLE_ROW:
      try {
        AttachmentId attachmentId = new PartUriParser(uri).getPartId();
        UnlockSession session = UnlockSession.capture();
        session.use(ignored -> null);
        return openPipeHelper(uri, null, null, attachmentId,
            (output, ignoredUri, ignoredType, ignoredOptions, id) ->
                streamAttachment(session, id, output));
      } catch (Exception error) {
        Log.w(TAG, "Unable to open attachment for the current unlock generation", error);
        throw new FileNotFoundException("Attachment is unavailable");
      }
    }

    throw new FileNotFoundException("Request for bad part.");
  }

  @SuppressWarnings("ConstantConditions")
  private void streamAttachment(UnlockSession session, AttachmentId attachmentId,
                                ParcelFileDescriptor outputDescriptor) {
    try (ParcelFileDescriptor.AutoCloseOutputStream output =
             new ParcelFileDescriptor.AutoCloseOutputStream(outputDescriptor);
         InputStream input = session.use(secret -> DatabaseFactory.getAttachmentDatabase(getContext())
             .getAttachmentStream(secret, attachmentId))) {
      byte[] buffer = new byte[8192];
      while (session.use(secret -> {
        int read = input.read(buffer);
        if (read > 0) output.write(buffer, 0, read);
        return read;
      }) != -1) {}
    } catch (Exception error) {
      Log.w(TAG, "Attachment stream closed", error);
    }
  }

  @Override
  public int delete(@NonNull Uri arg0, String arg1, String[] arg2) {
    return 0;
  }

  @Override
  public String getType(@NonNull Uri arg0) {
    return null;
  }

  @Override
  public Uri insert(@NonNull Uri arg0, ContentValues arg1) {
    return null;
  }

  @Override
  public Cursor query(@NonNull Uri arg0, String[] arg1, String arg2, String[] arg3, String arg4) {
    return null;
  }

  @Override
  public int update(@NonNull Uri arg0, ContentValues arg1, String arg2, String[] arg3) {
    return 0;
  }
}
