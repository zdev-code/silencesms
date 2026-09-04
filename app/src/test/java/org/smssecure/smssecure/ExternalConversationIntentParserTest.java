package org.smssecure.smssecure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.smssecure.smssecure.database.ThreadDatabase;

@RunWith(RobolectricTestRunner.class)
public class ExternalConversationIntentParserTest {
    @Test
    public void externalRouterDispatchCanBeClaimedOnlyOnceAndNeverFromRestoredState() {
        ExternalRouterDispatchGuard guard = new ExternalRouterDispatchGuard();

        assertThat(guard.claim(false)).isTrue();
        assertThat(guard.claim(false)).isFalse();
        assertThat(new ExternalRouterDispatchGuard().claim(true)).isFalse();
    }

  @Test
  public void sendToAcceptsExactSmsAndMmsProtocolContracts() throws Exception {
    for (String action : new String[] {Intent.ACTION_SENDTO, Intent.ACTION_VIEW}) {
      for (String scheme : new String[] {"sms", "smsto", "mms", "mmsto"}) {
        Intent intent = new Intent(action, Uri.parse(scheme + ":+15551234567?body=hello%20world"));

        ExternalConversationIntentParser.SendToInput parsed =
            ExternalConversationIntentParser.parseSendTo(intent);

        assertThat(parsed.getDestination()).isEqualTo("+15551234567");
        assertThat(parsed.getBody()).isEqualTo("hello world");
      }
    }
  }

  @Test
  public void sendToRejectsUnsupportedMalformedOversizedAndUnexpectedPayloads() {
    String oversizedBody = "x".repeat(ExternalConversationIntentParser.MAX_TEXT_LENGTH + 1);
    String oversizedDestination = "1".repeat(
        ExternalConversationIntentParser.MAX_DESTINATION_LENGTH + 1);
    for (Intent intent : new Intent[] {
        new Intent(Intent.ACTION_SEND, Uri.parse("sms:+15551234567")),
        new Intent(Intent.ACTION_SENDTO, Uri.parse("content://contacts/1")),
        new Intent(Intent.ACTION_SENDTO, Uri.parse("SMS:+15551234567")),
        new Intent(Intent.ACTION_SENDTO, Uri.parse("sms:" + oversizedDestination)),
        new Intent(Intent.ACTION_SENDTO, Uri.parse("sms:+15551234567"))
            .putExtra("sms_body", oversizedBody),
        new Intent(Intent.ACTION_SENDTO, Uri.parse("sms:+15551234567?body=one&body=two")),
        new Intent(Intent.ACTION_SENDTO, Uri.parse("sms:+15551234567?subject=unexpected")),
        new Intent(Intent.ACTION_SENDTO, Uri.parse("sms:+15551234567?body=one"))
            .putExtra("sms_body", "two"),
        new Intent(Intent.ACTION_SENDTO, Uri.parse("sms:+15551234567"))
            .putExtra("unexpected", new Intent())
    }) {
      assertThatThrownBy(() -> ExternalConversationIntentParser.parseSendTo(intent))
          .isInstanceOf(ExternalConversationIntentParser.InvalidIntentException.class);
    }
  }

  @Test
  public void shareAcceptsBoundedTextMediaAndDirectShareDestination() throws Exception {
    Intent text = new Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, "private text");
    assertThat(ExternalConversationIntentParser.parseShare(text).getPayload().getText())
        .isEqualTo("private text");

    Uri stream = Uri.parse("content://external.provider/item/1");
    Intent media = new Intent(Intent.ACTION_SEND).setType("image/png")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .putExtra(Intent.EXTRA_STREAM, stream)
        .putExtra(ShareActivity.EXTRA_THREAD_ID, 7L)
        .putExtra(ShareActivity.EXTRA_RECIPIENT_IDS, new long[] {3L})
        .putExtra(ShareActivity.EXTRA_DISTRIBUTION_TYPE,
                  ThreadDatabase.DistributionTypes.DEFAULT);
    media.setClipData(ClipData.newRawUri("shared", stream));

    ExternalConversationIntentParser.ShareInput parsed =
        ExternalConversationIntentParser.parseShare(media);

    assertThat(parsed.hasExternalMedia()).isTrue();
    assertThat(parsed.getPayload().getThreadId()).isEqualTo(7L);
    assertThat(parsed.getPayload().getRecipientIds()).containsExactly(3L);

    Intent wildcard = mediaShare(stream).setType("image/*");
    assertThat(ExternalConversationIntentParser.parseShare(wildcard).getPayload().getMedia())
        .isEqualTo(stream);
  }

  @Test
  public void shareRejectsUnsupportedMalformedOversizedAndNestedPayloads() {
    Uri stream = Uri.parse("content://external.provider/item/1");
    Intent mismatchedClip = mediaShare(stream);
    mismatchedClip.setClipData(
        ClipData.newRawUri("other", Uri.parse("content://external.provider/item/2")));
    for (Intent intent : new Intent[] {
        new Intent(Intent.ACTION_VIEW).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "text"),
        new Intent(Intent.ACTION_SEND).setType("application/octet-stream")
            .putExtra(Intent.EXTRA_STREAM, stream),
        new Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT,
                "x".repeat(ExternalConversationIntentParser.MAX_TEXT_LENGTH + 1)),
        new Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "text").putExtra("unexpected", new Intent()),
        new Intent(Intent.ACTION_SEND).setType("image/png")
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            .putExtra(Intent.EXTRA_STREAM, stream),
        new Intent(Intent.ACTION_SEND).setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, Uri.parse("file:///sdcard/private.jpg")),
        new Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "text")
            .putExtra(ShareActivity.EXTRA_THREAD_ID, 7L),
        new Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "text")
            .putExtra(ShareActivity.EXTRA_THREAD_ID, "wrong-type")
            .putExtra(ShareActivity.EXTRA_RECIPIENT_IDS, new long[] {3L})
            .putExtra(ShareActivity.EXTRA_DISTRIBUTION_TYPE,
                      ThreadDatabase.DistributionTypes.DEFAULT),
        mismatchedClip
    }) {
      assertThatThrownBy(() -> ExternalConversationIntentParser.parseShare(intent))
          .isInstanceOf(ExternalConversationIntentParser.InvalidIntentException.class);
    }
  }

  private static Intent mediaShare(Uri stream) {
    return new Intent(Intent.ACTION_SEND).setType("image/png")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .putExtra(Intent.EXTRA_STREAM, stream);
  }
}