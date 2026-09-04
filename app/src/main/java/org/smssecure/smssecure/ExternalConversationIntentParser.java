package org.smssecure.smssecure;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.IntentCompat;

import org.smssecure.smssecure.database.ThreadDatabase;
import org.smssecure.smssecure.domain.conversation.ConversationPayload;
import org.smssecure.smssecure.mms.PartAuthority;

import java.util.HashSet;
import java.util.Set;

final class ExternalConversationIntentParser {
  static final int MAX_TEXT_LENGTH = 65_536;
  static final int MAX_DESTINATION_LENGTH = 2_048;
  static final int MAX_RECIPIENT_COUNT = 64;
  private static final int FORBIDDEN_GRANT_FLAGS =
      Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION |
      Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;

  private ExternalConversationIntentParser() {}

  static ShareInput parseShare(@NonNull Intent intent) throws InvalidIntentException {
    try {
      return parseShareUnchecked(intent);
    } catch (RuntimeException error) {
      throw new InvalidIntentException();
    }
  }

  private static ShareInput parseShareUnchecked(Intent intent) throws InvalidIntentException {
    if (!Intent.ACTION_SEND.equals(intent.getAction()) || intent.getData() != null ||
        intent.getType() == null || hasForbiddenGrantFlags(intent) ||
        !hasOnlyCategories(intent, Intent.CATEGORY_DEFAULT)) {
      throw new InvalidIntentException();
    }
    String mimeType = intent.getType();
    if (!isSupportedMimeType(mimeType)) throw new InvalidIntentException();
    Bundle extras = safeExtras(intent);
    requireOnlyExtras(extras, Intent.EXTRA_TEXT, Intent.EXTRA_STREAM,
        ShareActivity.EXTRA_THREAD_ID, ShareActivity.EXTRA_RECIPIENT_IDS,
        ShareActivity.EXTRA_DISTRIBUTION_TYPE);

    String text = boundedText(extras, Intent.EXTRA_TEXT);
    Uri media = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri.class);
    if (extras.containsKey(Intent.EXTRA_STREAM) && media == null) throw new InvalidIntentException();
    if (media != null && !"content".equals(media.getScheme())) throw new InvalidIntentException();
    if (mimeType.startsWith("text/") ? text == null && media == null : media == null) {
      throw new InvalidIntentException();
    }
    requireMatchingClipData(intent.getClipData(), media);

    boolean preselected = extras.containsKey(ShareActivity.EXTRA_THREAD_ID) ||
        extras.containsKey(ShareActivity.EXTRA_RECIPIENT_IDS) ||
        extras.containsKey(ShareActivity.EXTRA_DISTRIBUTION_TYPE);
    long threadId = -1L;
    long[] recipientIds = new long[0];
    int distributionType = ThreadDatabase.DistributionTypes.DEFAULT;
    if (preselected) {
      if (!extras.containsKey(ShareActivity.EXTRA_THREAD_ID) ||
          !extras.containsKey(ShareActivity.EXTRA_RECIPIENT_IDS) ||
          !extras.containsKey(ShareActivity.EXTRA_DISTRIBUTION_TYPE)) {
        throw new InvalidIntentException();
      }
      threadId = intent.getLongExtra(ShareActivity.EXTRA_THREAD_ID, -1L);
      recipientIds = intent.getLongArrayExtra(ShareActivity.EXTRA_RECIPIENT_IDS);
      distributionType = intent.getIntExtra(
          ShareActivity.EXTRA_DISTRIBUTION_TYPE, Integer.MIN_VALUE);
      if (threadId <= 0L || !validRecipientIds(recipientIds) ||
          (distributionType != ThreadDatabase.DistributionTypes.DEFAULT &&
           distributionType != ThreadDatabase.DistributionTypes.BROADCAST)) {
        throw new InvalidIntentException();
      }
    }

    boolean externalMedia = media != null && !PartAuthority.isLocalUri(media);
    if (externalMedia && (intent.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) {
      throw new InvalidIntentException();
    }
    return new ShareInput(new ConversationPayload(
        threadId, recipientIds, distributionType, text, media, mimeType), externalMedia);
  }

  static SendToInput parseSendTo(@NonNull Intent intent) throws InvalidIntentException {
    try {
      return parseSendToUnchecked(intent);
    } catch (RuntimeException error) {
      throw new InvalidIntentException();
    }
  }

  private static SendToInput parseSendToUnchecked(Intent intent) throws InvalidIntentException {
    if ((!Intent.ACTION_SENDTO.equals(intent.getAction()) &&
         !Intent.ACTION_VIEW.equals(intent.getAction())) || intent.getData() == null ||
        intent.getType() != null || intent.getClipData() != null ||
        hasForbiddenGrantFlags(intent) ||
        !hasOnlyCategories(intent, Intent.CATEGORY_DEFAULT, Intent.CATEGORY_BROWSABLE)) {
      throw new InvalidIntentException();
    }
    Uri uri = intent.getData();
    String scheme = uri.getScheme();
    if (!Set.of("sms", "smsto", "mms", "mmsto").contains(scheme) ||
        uri.getFragment() != null) {
      throw new InvalidIntentException();
    }
    Bundle extras = safeExtras(intent);
    requireOnlyExtras(extras, "sms_body");
    String extraBody = boundedText(extras, "sms_body");

    String encoded = uri.getEncodedSchemeSpecificPart();
    if (encoded == null) throw new InvalidIntentException();
    String[] parts = encoded.split("\\?", -1);
    if (parts.length > 2) throw new InvalidIntentException();
    String destination = Uri.decode(parts[0]);
    if (!validDestination(destination)) throw new InvalidIntentException();
    String queryBody = null;
    if (parts.length == 2) {
      String[] parameters = parts[1].split("&", -1);
      if (parameters.length != 1) throw new InvalidIntentException();
      String[] parameter = parameters[0].split("=", 2);
      if (!"body".equals(Uri.decode(parameter[0]))) throw new InvalidIntentException();
      queryBody = bounded(Uri.decode(parameter.length == 2 ? parameter[1] : ""));
    }
    if (extraBody != null && queryBody != null) throw new InvalidIntentException();
    return new SendToInput(destination, extraBody != null ? extraBody : queryBody);
  }

  private static Bundle safeExtras(Intent intent) throws InvalidIntentException {
    try {
      intent.setExtrasClassLoader(ExternalConversationIntentParser.class.getClassLoader());
      Bundle extras = intent.getExtras();
      return extras == null ? Bundle.EMPTY : extras;
    } catch (RuntimeException error) {
      throw new InvalidIntentException();
    }
  }

  private static String boundedText(Bundle extras, String key) throws InvalidIntentException {
    if (!extras.containsKey(key)) return null;
    CharSequence value = extras.getCharSequence(key);
    if (value == null) throw new InvalidIntentException();
    return bounded(value.toString());
  }

  private static String bounded(String value) throws InvalidIntentException {
    if (value.length() > MAX_TEXT_LENGTH || value.indexOf('\0') >= 0) {
      throw new InvalidIntentException();
    }
    return value;
  }

  private static boolean validDestination(String destination) {
    if (destination.isEmpty() || destination.length() > MAX_DESTINATION_LENGTH ||
        destination.indexOf('\0') >= 0 || destination.indexOf('?') >= 0 ||
        destination.indexOf('&') >= 0) {
      return false;
    }
    String[] recipients = destination.split("[,;]", -1);
    if (recipients.length == 0 || recipients.length > MAX_RECIPIENT_COUNT) return false;
    for (String recipient : recipients) {
      if (recipient.trim().isEmpty() || recipient.length() > 256) return false;
      for (int index = 0; index < recipient.length(); index++) {
        if (Character.isISOControl(recipient.charAt(index))) return false;
      }
    }
    return true;
  }

  private static boolean validRecipientIds(@Nullable long[] recipientIds) {
    if (recipientIds == null || recipientIds.length == 0 ||
        recipientIds.length > MAX_RECIPIENT_COUNT) return false;
    Set<Long> unique = new HashSet<>();
    for (long recipientId : recipientIds) {
      if (recipientId <= 0L || !unique.add(recipientId)) return false;
    }
    return true;
  }

  private static boolean isSupportedMimeType(String mimeType) {
    if (mimeType.length() > 127 || mimeType.indexOf(';') >= 0) return false;
    return "text/plain".equals(mimeType) || mimeType.matches("(?:image|audio|video)/[^/\\s]+");
  }

  private static boolean hasForbiddenGrantFlags(Intent intent) {
    return (intent.getFlags() & FORBIDDEN_GRANT_FLAGS) != 0;
  }

  private static boolean hasOnlyCategories(Intent intent, String... allowed) {
    if (intent.getCategories() == null) return true;
    return Set.of(allowed).containsAll(intent.getCategories());
  }

  private static void requireOnlyExtras(Bundle extras, String... allowed)
      throws InvalidIntentException {
    Set<String> unexpected = new HashSet<>(extras.keySet());
    unexpected.removeAll(Set.of(allowed));
    if (!unexpected.isEmpty()) throw new InvalidIntentException();
  }

  private static void requireMatchingClipData(@Nullable ClipData clipData, @Nullable Uri media)
      throws InvalidIntentException {
    if (clipData == null) return;
    if (media == null || clipData.getItemCount() != 1) throw new InvalidIntentException();
    ClipData.Item item = clipData.getItemAt(0);
    if (!media.equals(item.getUri()) || item.getIntent() != null || item.getText() != null ||
        item.getHtmlText() != null) {
      throw new InvalidIntentException();
    }
  }

  static final class ShareInput {
    private final ConversationPayload payload;
    private final boolean externalMedia;

    ShareInput(ConversationPayload payload, boolean externalMedia) {
      this.payload = payload;
      this.externalMedia = externalMedia;
    }

    ConversationPayload getPayload() { return payload; }
    boolean hasExternalMedia() { return externalMedia; }
  }

  static final class SendToInput {
    private final String destination;
    private final String body;

    SendToInput(String destination, @Nullable String body) {
      this.destination = destination;
      this.body = body;
    }

    String getDestination() { return destination; }
    @Nullable String getBody() { return body; }
  }

  static final class InvalidIntentException extends Exception {}
}