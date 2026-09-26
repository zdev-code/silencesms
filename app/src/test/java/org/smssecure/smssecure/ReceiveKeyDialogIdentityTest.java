package org.smssecure.smssecure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.ecc.Curve;
import org.signal.libsignal.protocol.state.IdentityKeyStore;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.crypto.storage.SilenceIdentityKeyStore;
import org.smssecure.smssecure.database.DatabaseFactory;
import org.smssecure.smssecure.database.EncryptingSmsDatabase;
import org.smssecure.smssecure.database.IdentityDatabase;
import org.smssecure.smssecure.database.documents.IdentityKeyMismatch;
import org.smssecure.smssecure.jobs.SmsDecryptJob;
import org.smssecure.smssecure.recipients.Recipient;
import org.smssecure.smssecure.recipients.RecipientFactory;
import org.smssecure.smssecure.recipients.Recipients;
import org.whispersystems.jobqueue.Job;
import org.whispersystems.jobqueue.JobManager;

import java.lang.reflect.Field;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ReceiveKeyDialogIdentityTest extends BaseUnitTest {

  @Test
  public void trustCheckAcceptsStoredAndFirstUseButRejectsChangedKey() {
    long recipientId = 52L;
    IdentityKey storedKey = identityKey();
    IdentityKey changedKey = identityKey();
    IdentityDatabase database = new IdentityDatabase(context, null) {
      @Override
      public boolean isValidIdentity(MasterSecret ignored, long candidateRecipientId, IdentityKey candidateKey) {
        return candidateRecipientId != recipientId || storedKey.equals(candidateKey);
      }
    };

    assertThat(ReceiveKeyDialog.isTrusted(database, masterSecret, recipientId, storedKey)).isTrue();
    assertThat(ReceiveKeyDialog.isTrusted(database, masterSecret, recipientId, changedKey)).isFalse();
    assertThat(ReceiveKeyDialog.isTrusted(database, masterSecret, recipientId + 1, changedKey)).isTrue();
  }

  @Test
  public void receivingTrustUsesFirstUseDatabaseDecision() throws Exception {
    long recipientId = 53L;
    int appSubscriptionId = 4;
    IdentityKey key = identityKey();
    IdentityDatabase identityDatabase = mock(IdentityDatabase.class);
    Recipient recipient = mock(Recipient.class);
    Recipients recipients = mock(Recipients.class);

    when(recipient.getRecipientId()).thenReturn(recipientId);
    when(recipients.getPrimaryRecipient()).thenReturn(recipient);
    when(identityDatabase.isValidIdentity(masterSecret, recipientId, key)).thenReturn(true);

    try (MockedStatic<DatabaseFactory> databases = Mockito.mockStatic(DatabaseFactory.class);
         MockedStatic<RecipientFactory> recipientFactory = Mockito.mockStatic(RecipientFactory.class)) {
      databases.when(() -> DatabaseFactory.getIdentityDatabase(context)).thenReturn(identityDatabase);
      recipientFactory.when(() -> RecipientFactory.getRecipientsFromString(context, "alice", true))
                      .thenReturn(recipients);

      SilenceIdentityKeyStore store = new SilenceIdentityKeyStore(context, masterSecret, appSubscriptionId);
      assertThat(store.isTrustedIdentity(new SignalProtocolAddress("alice", 1), key,
                                         IdentityKeyStore.Direction.RECEIVING)).isTrue();
      verify(identityDatabase).isValidIdentity(masterSecret, recipientId, key);
    }
  }

  @Test
  public void acceptSavesIdentityAndRequeuesMessage() throws Exception {
    long recipientId = 54L;
    long messageId = 61L;
    IdentityKey key = identityKey();
    IdentityKeyMismatch mismatch = new IdentityKeyMismatch(recipientId, key);
    IdentityDatabase identityDatabase = mock(IdentityDatabase.class);
    EncryptingSmsDatabase smsDatabase = mock(EncryptingSmsDatabase.class);
    ApplicationContext application = mock(ApplicationContext.class);
    JobManager jobManager = mock(JobManager.class);

    when(application.getJobManager()).thenReturn(jobManager);

    try (MockedStatic<DatabaseFactory> databases = Mockito.mockStatic(DatabaseFactory.class);
         MockedStatic<ApplicationContext> applications = Mockito.mockStatic(ApplicationContext.class)) {
      databases.when(() -> DatabaseFactory.getIdentityDatabase(context)).thenReturn(identityDatabase);
      databases.when(() -> DatabaseFactory.getEncryptingSmsDatabase(context)).thenReturn(smsDatabase);
      applications.when(() -> ApplicationContext.getInstance(context)).thenReturn(application);

      ReceiveKeyDialog.acceptKey(context, masterSecret, recipientId, messageId, key, false, mismatch);

      verify(identityDatabase).saveIdentity(masterSecret, recipientId, key);
      verify(smsDatabase).removeMismatchedIdentity(messageId, recipientId, key);
      verify(smsDatabase).notifyMessageStateChanged(messageId);
      ArgumentCaptor<Job> jobCaptor = ArgumentCaptor.forClass(Job.class);
      verify(jobManager).add(jobCaptor.capture());
      assertThat(jobCaptor.getValue()).isInstanceOf(SmsDecryptJob.class);
      assertThat(field(jobCaptor.getValue(), "messageId")).isEqualTo(messageId);
      assertThat(field(jobCaptor.getValue(), "manualOverride")).isEqualTo(true);
    }
  }

  private static Object field(Object instance, String name) throws Exception {
    Field field = instance.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(instance);
  }

  private static IdentityKey identityKey() {
    return new IdentityKey(Curve.generateKeyPair().getPublicKey());
  }
}