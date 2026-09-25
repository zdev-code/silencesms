package org.smssecure.smssecure.crypto.storage;

import static org.assertj.core.api.Assertions.assertThat;

import android.app.Application;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.smssecure.smssecure.recipients.RecipientFactory;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.io.File;
import java.nio.file.Files;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 23)
public class VendoredSessionStoreGuardTest {
  @Test
  public void conditionalDeleteRejectsReplacedSessionAndOtherSubscription() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    String number = "+15555550117";
    long recipientId = RecipientFactory.getRecipientsFromString(context, number, true)
        .getPrimaryRecipient().getRecipientId();
    File directory = VendoredSessionStore.getSessionDirectory(context);
    File selected = new File(directory, recipientId + ".1");
    File other = new File(directory, recipientId + ".2");
    VendoredSessionStore store = new VendoredSessionStore(context, null, 1);
    try {
      Files.write(selected.toPath(), new byte[]{1});
      Files.write(other.toPath(), new byte[]{9});
      String snapshot = store.snapshotSession(number);
      Files.write(selected.toPath(), new byte[]{2});
      assertThat(store.deleteSessionIfUnchanged(number, snapshot))
          .isEqualTo(VendoredSessionStore.DeleteOutcome.CHANGED);
      assertThat(Files.readAllBytes(selected.toPath())).containsExactly((byte) 2);
      assertThat(Files.readAllBytes(other.toPath())).containsExactly((byte) 9);

      String current = store.snapshotSession(number);
      assertThat(store.deleteSessionIfUnchanged(number, current))
          .isEqualTo(VendoredSessionStore.DeleteOutcome.DELETED);
      assertThat(store.deleteSessionIfUnchanged(number, current))
          .isEqualTo(VendoredSessionStore.DeleteOutcome.ALREADY_ABSENT);
      assertThat(other).exists();
    } finally {
      Files.deleteIfExists(selected.toPath());
      Files.deleteIfExists(other.toPath());
    }
  }

  @Test
  public void absentSnapshotCannotDeleteNewSession() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    String number = "+15555550118";
    long recipientId = RecipientFactory.getRecipientsFromString(context, number, true)
        .getPrimaryRecipient().getRecipientId();
    File file = new File(VendoredSessionStore.getSessionDirectory(context), recipientId + ".1");
    VendoredSessionStore store = new VendoredSessionStore(context, null, 1);
    try {
      Files.deleteIfExists(file.toPath());
      String absent = store.snapshotSession(number);
        assertThat(store.deleteSessionIfUnchanged(number, absent))
          .isEqualTo(VendoredSessionStore.DeleteOutcome.ALREADY_ABSENT);
      Files.write(file.toPath(), new byte[]{3});
      assertThat(store.deleteSessionIfUnchanged(number, absent))
          .isEqualTo(VendoredSessionStore.DeleteOutcome.CHANGED);
      assertThat(file).exists();
    } finally {
      Files.deleteIfExists(file.toPath());
    }
  }

  @Test
  public void identicalReplacementCannotDeleteNewSession() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    String number = "+15555550119";
    long recipientId = RecipientFactory.getRecipientsFromString(context, number, true)
        .getPrimaryRecipient().getRecipientId();
    File file = new File(VendoredSessionStore.getSessionDirectory(context), recipientId + ".1");
    VendoredSessionStore store = new VendoredSessionStore(context, null, 1);
    try {
      Files.write(file.toPath(), new byte[]{4});
      String snapshot = store.snapshotSession(number);
      store.deleteSession(new org.whispersystems.libsignal.SignalProtocolAddress(number, 1));
      Files.write(file.toPath(), new byte[]{4});
      assertThat(store.deleteSessionIfUnchanged(number, snapshot))
          .isEqualTo(VendoredSessionStore.DeleteOutcome.CHANGED);
      assertThat(file).exists();
    } finally {
      Files.deleteIfExists(file.toPath());
    }
  }

  @Test
  public void maintainedStoreMutationInvalidatesSnapshotEvenForIdenticalBytes() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    String number = "+15555550120";
    long recipientId = RecipientFactory.getRecipientsFromString(context, number, true)
        .getPrimaryRecipient().getRecipientId();
    File file = new File(VendoredSessionStore.getSessionDirectory(context), recipientId + ".1");
    VendoredSessionStore vendored = new VendoredSessionStore(context, null, 1);
    SilenceSessionStore maintained = new SilenceSessionStore(context, null, 1);
    try {
      Files.write(file.toPath(), new byte[]{5});
      String snapshot = vendored.snapshotSession(number);
      maintained.deleteSession(new SignalProtocolAddress(number, 1));
      Files.write(file.toPath(), new byte[]{5});
      assertThat(vendored.deleteSessionIfUnchanged(number, snapshot))
          .isEqualTo(VendoredSessionStore.DeleteOutcome.CHANGED);
      assertThat(file).exists();
    } finally {
      Files.deleteIfExists(file.toPath());
    }
  }

  @Test
  public void replayAfterDeleteAcknowledgesAbsenceButNotNewSession() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    String number = "+15555550121";
    long recipientId = RecipientFactory.getRecipientsFromString(context, number, true)
        .getPrimaryRecipient().getRecipientId();
    File file = new File(VendoredSessionStore.getSessionDirectory(context), recipientId + ".1");
    VendoredSessionStore store = new VendoredSessionStore(context, null, 1);
    try {
      Files.write(file.toPath(), new byte[]{6});
      String snapshot = store.snapshotSession(number);
      assertThat(store.deleteSessionIfUnchanged(number, snapshot))
          .isEqualTo(VendoredSessionStore.DeleteOutcome.DELETED);
      assertThat(new VendoredSessionStore(context, null, 1).deleteSessionIfUnchanged(number, snapshot))
          .isEqualTo(VendoredSessionStore.DeleteOutcome.ALREADY_ABSENT);
      Files.write(file.toPath(), new byte[]{6});
      store.deleteSession(new org.whispersystems.libsignal.SignalProtocolAddress(number, 1));
      Files.write(file.toPath(), new byte[]{6});
      assertThat(new VendoredSessionStore(context, null, 1).deleteSessionIfUnchanged(number, snapshot))
          .isEqualTo(VendoredSessionStore.DeleteOutcome.CHANGED);
      assertThat(file).exists();
    } finally {
      Files.deleteIfExists(file.toPath());
    }
  }

  @Test
  public void replayAfterDeletionCheckpointFinishesOnlyOriginalGeneration() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    String number = "+15555550122";
    long recipientId = RecipientFactory.getRecipientsFromString(context, number, true)
        .getPrimaryRecipient().getRecipientId();
    File file = new File(VendoredSessionStore.getSessionDirectory(context), recipientId + ".1");
    VendoredSessionStore store = new VendoredSessionStore(context, null, 1);
    try {
      Files.write(file.toPath(), new byte[]{7});
      String snapshot = store.snapshotSession(number);
      String generation = snapshot.split(":")[1];
      assertThat(context.getSharedPreferences("session-generations", Context.MODE_PRIVATE).edit()
          .putString("sessions-v2/" + recipientId + ".1", "deleting:" + generation).commit()).isTrue();
      assertThat(new VendoredSessionStore(context, null, 1).deleteSessionIfUnchanged(number, snapshot))
          .isEqualTo(VendoredSessionStore.DeleteOutcome.DELETED);

      Files.write(file.toPath(), new byte[]{7});
      store.deleteSession(new org.whispersystems.libsignal.SignalProtocolAddress(number, 1));
      Files.write(file.toPath(), new byte[]{7});
      assertThat(store.deleteSessionIfUnchanged(number, snapshot))
          .isEqualTo(VendoredSessionStore.DeleteOutcome.CHANGED);
      assertThat(file).exists();
    } finally {
      Files.deleteIfExists(file.toPath());
    }
  }
}