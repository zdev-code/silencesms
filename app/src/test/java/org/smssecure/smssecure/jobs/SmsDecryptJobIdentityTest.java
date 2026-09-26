package org.smssecure.smssecure.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.ecc.Curve;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.smssecure.smssecure.BaseUnitTest;
import org.smssecure.smssecure.crypto.SecurityEvent;
import org.smssecure.smssecure.crypto.SmsCipher;
import org.smssecure.smssecure.database.DatabaseFactory;
import org.smssecure.smssecure.database.EncryptingSmsDatabase;
import org.smssecure.smssecure.database.IdentityDatabase;
import org.smssecure.smssecure.recipients.Recipient;
import org.smssecure.smssecure.recipients.RecipientFactory;
import org.smssecure.smssecure.recipients.Recipients;
import org.smssecure.smssecure.sms.IncomingIdentityUpdateMessage;
import org.smssecure.smssecure.sms.IncomingKeyExchangeMessage;
import org.smssecure.smssecure.sms.IncomingPreKeyBundleMessage;
import org.smssecure.smssecure.sms.IncomingTextMessage;
import org.smssecure.smssecure.sms.MessageSender;
import org.smssecure.smssecure.sms.OutgoingKeyExchangeMessage;
import org.smssecure.smssecure.util.SilencePreferences;
import org.whispersystems.libsignal.StaleKeyExchangeException;
import org.whispersystems.libsignal.UntrustedIdentityException;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class SmsDecryptJobIdentityTest extends BaseUnitTest {

  @Test
  public void changedKeyExchangeStaysPendingEvenWithManualOverride() throws Exception {
    long messageId = 41L;
    long recipientId = 12L;
    IdentityKey incomingKey = identityKey();
    org.whispersystems.libsignal.IdentityKey vendoredKey = toVendored(incomingKey);
    UntrustedIdentityException mismatch = new UntrustedIdentityException("alice", vendoredKey);
    EncryptingSmsDatabase database = mock(EncryptingSmsDatabase.class);
    Recipient recipient = mock(Recipient.class);
    Recipients recipients = mock(Recipients.class);
    IncomingKeyExchangeMessage message = keyExchangeMessage(true);

    when(recipient.getRecipientId()).thenReturn(recipientId);
    when(recipients.getPrimaryRecipient()).thenReturn(recipient);

    try (MockedStatic<DatabaseFactory> databases = Mockito.mockStatic(DatabaseFactory.class);
         MockedStatic<RecipientFactory> recipientFactory = Mockito.mockStatic(RecipientFactory.class);
         MockedStatic<SecurityEvent> securityEvents = Mockito.mockStatic(SecurityEvent.class);
         MockedConstruction<SmsCipher> ciphers = Mockito.mockConstruction(SmsCipher.class,
             (cipher, construction) -> when(cipher.process(any(Context.class), any(IncomingKeyExchangeMessage.class)))
                 .thenThrow(mismatch))) {
      databases.when(() -> DatabaseFactory.getEncryptingSmsDatabase(context)).thenReturn(database);
      recipientFactory.when(() -> RecipientFactory.getRecipientsFromString(context, "alice", true))
                      .thenReturn(recipients);

      new SmsDecryptJob(context, messageId, true, false)
          .handleKeyExchangeMessage(masterSecret, messageId, 9L, message);

      verify(database).addMismatchedIdentity(messageId, recipientId, incomingKey);
      verify(database).notifyMessageStateChanged(messageId);
      verify(database, never()).markAsProcessedKeyExchange(messageId);
      securityEvents.verifyNoInteractions();
      assertThat(ciphers.constructed()).hasSize(1);
    }
  }

  @Test
  public void changedPreKeyStaysPendingAndRecordsMismatch() throws Exception {
    long messageId = 42L;
    long recipientId = 13L;
    IdentityKey incomingKey = identityKey();
    UntrustedIdentityException mismatch = new UntrustedIdentityException("alice", toVendored(incomingKey));
    EncryptingSmsDatabase database = mock(EncryptingSmsDatabase.class);
    Recipient recipient = mock(Recipient.class);
    Recipients recipients = mock(Recipients.class);
    IncomingPreKeyBundleMessage message = preKeyMessage();

    when(recipient.getRecipientId()).thenReturn(recipientId);
    when(recipients.getPrimaryRecipient()).thenReturn(recipient);

    try (MockedStatic<DatabaseFactory> databases = Mockito.mockStatic(DatabaseFactory.class);
         MockedStatic<RecipientFactory> recipientFactory = Mockito.mockStatic(RecipientFactory.class);
         MockedStatic<SecurityEvent> securityEvents = Mockito.mockStatic(SecurityEvent.class);
         MockedConstruction<SmsCipher> ciphers = Mockito.mockConstruction(SmsCipher.class,
             (cipher, construction) -> when(cipher.decrypt(any(Context.class), any(IncomingPreKeyBundleMessage.class)))
                 .thenThrow(mismatch))) {
      databases.when(() -> DatabaseFactory.getEncryptingSmsDatabase(context)).thenReturn(database);
      recipientFactory.when(() -> RecipientFactory.getRecipientsFromString(context, "alice", true))
                      .thenReturn(recipients);

      new SmsDecryptJob(context, messageId)
          .handlePreKeySignalMessage(masterSecret, messageId, 9L, message);

      verify(database).addMismatchedIdentity(messageId, recipientId, incomingKey);
      verify(database).notifyMessageStateChanged(messageId);
      verify(database, never()).updateBundleMessageBody(any(), org.mockito.ArgumentMatchers.eq(messageId), any());
      securityEvents.verifyNoInteractions();
      assertThat(ciphers.constructed()).hasSize(1);
    }
  }

  @Test
  public void changedIdentityUpdateStaysPending() throws Exception {
    long messageId = 43L;
    long recipientId = 14L;
    IdentityKey incomingKey = identityKey();
    EncryptingSmsDatabase database = mock(EncryptingSmsDatabase.class);
    IdentityDatabase identityDatabase = mock(IdentityDatabase.class);
    Recipient recipient = mock(Recipient.class);
    Recipients recipients = mock(Recipients.class);
    IncomingIdentityUpdateMessage message = IncomingIdentityUpdateMessage.createFor("alice", incomingKey);

    when(recipient.getRecipientId()).thenReturn(recipientId);
    when(recipients.getPrimaryRecipient()).thenReturn(recipient);
    when(identityDatabase.isValidIdentity(masterSecret, recipientId, incomingKey)).thenReturn(false);

    try (MockedStatic<DatabaseFactory> databases = Mockito.mockStatic(DatabaseFactory.class);
         MockedStatic<RecipientFactory> recipientFactory = Mockito.mockStatic(RecipientFactory.class);
         MockedStatic<SecurityEvent> securityEvents = Mockito.mockStatic(SecurityEvent.class);
         MockedConstruction<SmsCipher> ciphers = Mockito.mockConstruction(SmsCipher.class)) {
      databases.when(() -> DatabaseFactory.getEncryptingSmsDatabase(context)).thenReturn(database);
      databases.when(() -> DatabaseFactory.getIdentityDatabase(context)).thenReturn(identityDatabase);
      recipientFactory.when(() -> RecipientFactory.getRecipientsFromString(context, "alice", true))
                      .thenReturn(recipients);

      new SmsDecryptJob(context, messageId)
          .handleKeyExchangeMessage(masterSecret, messageId, 9L, message);

      verify(database).addMismatchedIdentity(messageId, recipientId, incomingKey);
      verify(database).notifyMessageStateChanged(messageId);
      verify(database, never()).markAsProcessedKeyExchange(messageId);
      securityEvents.verifyNoInteractions();
      assertThat(ciphers.constructed()).isEmpty();
    }
  }

  @Test
  public void firstUseKeyExchangeCanCompleteNormally() throws Exception {
    long messageId = 44L;
    EncryptingSmsDatabase database = mock(EncryptingSmsDatabase.class);
    IncomingKeyExchangeMessage message = keyExchangeMessage(true);

    try (MockedStatic<DatabaseFactory> databases = Mockito.mockStatic(DatabaseFactory.class);
         MockedStatic<SilencePreferences> preferences = Mockito.mockStatic(SilencePreferences.class);
         MockedStatic<SecurityEvent> securityEvents = Mockito.mockStatic(SecurityEvent.class);
         MockedConstruction<SmsCipher> ciphers = Mockito.mockConstruction(SmsCipher.class,
             (cipher, construction) -> when(cipher.process(any(Context.class), any(IncomingKeyExchangeMessage.class)))
                 .thenReturn(null))) {
      databases.when(() -> DatabaseFactory.getEncryptingSmsDatabase(context)).thenReturn(database);
      preferences.when(() -> SilencePreferences.isAutoRespondKeyExchangeEnabled(context)).thenReturn(true);

      new SmsDecryptJob(context, messageId)
          .handleKeyExchangeMessage(masterSecret, messageId, 9L, message);

      verify(database).markAsProcessedKeyExchange(messageId);
      verify(database, never()).addMismatchedIdentity(any(Long.class), any(Long.class), any());
      securityEvents.verify(() -> SecurityEvent.broadcastSecurityUpdateEvent(context, 9L));
      assertThat(ciphers.constructed()).hasSize(1);
    }
  }

  @Test
  public void initiateWaitsWhenOff() throws Exception {
    long messageId = 45L;
    long recipientId = 15L;
    EncryptingSmsDatabase database = mock(EncryptingSmsDatabase.class);
    IdentityDatabase identityDatabase = mock(IdentityDatabase.class);
    Recipient recipient = mock(Recipient.class);
    Recipients recipients = mock(Recipients.class);
    IncomingKeyExchangeMessage message = keyExchangeMessage(true);

    when(recipient.getRecipientId()).thenReturn(recipientId);
    when(recipients.getPrimaryRecipient()).thenReturn(recipient);
    when(identityDatabase.isValidIdentity(eq(masterSecret), eq(recipientId), any(IdentityKey.class)))
        .thenReturn(true);

    try (MockedStatic<DatabaseFactory> databases = Mockito.mockStatic(DatabaseFactory.class);
         MockedStatic<RecipientFactory> recipientFactory = Mockito.mockStatic(RecipientFactory.class);
         MockedStatic<SilencePreferences> preferences = Mockito.mockStatic(SilencePreferences.class);
         MockedStatic<MessageSender> sender = Mockito.mockStatic(MessageSender.class);
         MockedStatic<SecurityEvent> securityEvents = Mockito.mockStatic(SecurityEvent.class);
         MockedConstruction<SmsCipher> ciphers = Mockito.mockConstruction(SmsCipher.class)) {
      databases.when(() -> DatabaseFactory.getEncryptingSmsDatabase(context)).thenReturn(database);
      databases.when(() -> DatabaseFactory.getIdentityDatabase(context)).thenReturn(identityDatabase);
      recipientFactory.when(() -> RecipientFactory.getRecipientsFromString(context, "alice", true))
                      .thenReturn(recipients);
      preferences.when(() -> SilencePreferences.isAutoRespondKeyExchangeEnabled(context)).thenReturn(false);

      new SmsDecryptJob(context, messageId)
          .handleKeyExchangeMessage(masterSecret, messageId, 9L, message);

      verify(database, never()).markAsProcessedKeyExchange(messageId);
      verify(identityDatabase, never()).saveIdentity(any(), anyLong(), any(IdentityKey.class));
      sender.verifyNoInteractions();
      securityEvents.verifyNoInteractions();
      assertThat(ciphers.constructed()).isEmpty();
    }
  }

  @Test
  public void changedInitiateRecordsMismatch() throws Exception {
    long messageId = 46L;
    long recipientId = 16L;
    EncryptingSmsDatabase database = mock(EncryptingSmsDatabase.class);
    IdentityDatabase identityDatabase = mock(IdentityDatabase.class);
    Recipient recipient = mock(Recipient.class);
    Recipients recipients = mock(Recipients.class);
    IncomingKeyExchangeMessage message = keyExchangeMessage(true);

    when(recipient.getRecipientId()).thenReturn(recipientId);
    when(recipients.getPrimaryRecipient()).thenReturn(recipient);
    when(identityDatabase.isValidIdentity(eq(masterSecret), eq(recipientId), any(IdentityKey.class)))
        .thenReturn(false);

    try (MockedStatic<DatabaseFactory> databases = Mockito.mockStatic(DatabaseFactory.class);
         MockedStatic<RecipientFactory> recipientFactory = Mockito.mockStatic(RecipientFactory.class);
         MockedStatic<SilencePreferences> preferences = Mockito.mockStatic(SilencePreferences.class);
         MockedStatic<MessageSender> sender = Mockito.mockStatic(MessageSender.class);
         MockedStatic<SecurityEvent> securityEvents = Mockito.mockStatic(SecurityEvent.class);
         MockedConstruction<SmsCipher> ciphers = Mockito.mockConstruction(SmsCipher.class)) {
      databases.when(() -> DatabaseFactory.getEncryptingSmsDatabase(context)).thenReturn(database);
      databases.when(() -> DatabaseFactory.getIdentityDatabase(context)).thenReturn(identityDatabase);
      recipientFactory.when(() -> RecipientFactory.getRecipientsFromString(context, "alice", true))
                      .thenReturn(recipients);
      preferences.when(() -> SilencePreferences.isAutoRespondKeyExchangeEnabled(context)).thenReturn(false);

      new SmsDecryptJob(context, messageId)
          .handleKeyExchangeMessage(masterSecret, messageId, 9L, message);

      verify(database).addMismatchedIdentity(eq(messageId), eq(recipientId), any(IdentityKey.class));
      verify(database).notifyMessageStateChanged(messageId);
      verify(database, never()).markAsProcessedKeyExchange(messageId);
      verify(identityDatabase, never()).saveIdentity(any(), anyLong(), any(IdentityKey.class));
      sender.verifyNoInteractions();
      securityEvents.verifyNoInteractions();
      assertThat(ciphers.constructed()).isEmpty();
    }
  }

  @Test
  public void manualAcceptCompletesInitiate() throws Exception {
    long messageId = 47L;
    EncryptingSmsDatabase database = mock(EncryptingSmsDatabase.class);
    IncomingKeyExchangeMessage message = keyExchangeMessage(true);
    OutgoingKeyExchangeMessage response = mock(OutgoingKeyExchangeMessage.class);

    try (MockedStatic<DatabaseFactory> databases = Mockito.mockStatic(DatabaseFactory.class);
         MockedStatic<SilencePreferences> preferences = Mockito.mockStatic(SilencePreferences.class);
         MockedStatic<MessageSender> sender = Mockito.mockStatic(MessageSender.class);
         MockedStatic<SecurityEvent> securityEvents = Mockito.mockStatic(SecurityEvent.class);
         MockedConstruction<SmsCipher> ciphers = Mockito.mockConstruction(SmsCipher.class,
             (cipher, construction) -> when(cipher.process(any(Context.class), any(IncomingKeyExchangeMessage.class)))
                 .thenReturn(response))) {
      databases.when(() -> DatabaseFactory.getEncryptingSmsDatabase(context)).thenReturn(database);
      preferences.when(() -> SilencePreferences.isAutoRespondKeyExchangeEnabled(context)).thenReturn(false);

      new SmsDecryptJob(context, messageId, true, false)
          .handleKeyExchangeMessage(masterSecret, messageId, 9L, message);

      verify(database).markAsProcessedKeyExchange(messageId);
      securityEvents.verify(() -> SecurityEvent.broadcastSecurityUpdateEvent(context, 9L));
      sender.verify(() -> MessageSender.send(context, masterSecret, response, 9L, true));
      assertThat(ciphers.constructed()).hasSize(1);
    }
  }

  @Test
  public void responseCompletesWhenOff() throws Exception {
    long messageId = 48L;
    EncryptingSmsDatabase database = mock(EncryptingSmsDatabase.class);
    IncomingKeyExchangeMessage message = keyExchangeMessage(false);

    try (MockedStatic<DatabaseFactory> databases = Mockito.mockStatic(DatabaseFactory.class);
         MockedStatic<SilencePreferences> preferences = Mockito.mockStatic(SilencePreferences.class);
         MockedStatic<MessageSender> sender = Mockito.mockStatic(MessageSender.class);
         MockedStatic<SecurityEvent> securityEvents = Mockito.mockStatic(SecurityEvent.class);
         MockedConstruction<SmsCipher> ciphers = Mockito.mockConstruction(SmsCipher.class)) {
      databases.when(() -> DatabaseFactory.getEncryptingSmsDatabase(context)).thenReturn(database);
      preferences.when(() -> SilencePreferences.isAutoRespondKeyExchangeEnabled(context)).thenReturn(false);

      new SmsDecryptJob(context, messageId)
          .handleKeyExchangeMessage(masterSecret, messageId, 9L, message);

      verify(database).markAsProcessedKeyExchange(messageId);
      securityEvents.verify(() -> SecurityEvent.broadcastSecurityUpdateEvent(context, 9L));
      assertThat(ciphers.constructed()).hasSize(1);
      sender.verifyNoInteractions();
    }
  }

  @Test
  public void staleResponseStaysStaleWhenOff() throws Exception {
    long messageId = 49L;
    EncryptingSmsDatabase database = mock(EncryptingSmsDatabase.class);
    IncomingKeyExchangeMessage message = keyExchangeMessage(false);

    try (MockedStatic<DatabaseFactory> databases = Mockito.mockStatic(DatabaseFactory.class);
         MockedStatic<SilencePreferences> preferences = Mockito.mockStatic(SilencePreferences.class);
         MockedStatic<SecurityEvent> securityEvents = Mockito.mockStatic(SecurityEvent.class);
         MockedConstruction<SmsCipher> ciphers = Mockito.mockConstruction(SmsCipher.class,
             (cipher, construction) -> when(cipher.process(any(Context.class), any(IncomingKeyExchangeMessage.class)))
                 .thenThrow(new StaleKeyExchangeException()))) {
      databases.when(() -> DatabaseFactory.getEncryptingSmsDatabase(context)).thenReturn(database);
      preferences.when(() -> SilencePreferences.isAutoRespondKeyExchangeEnabled(context)).thenReturn(false);

      new SmsDecryptJob(context, messageId)
          .handleKeyExchangeMessage(masterSecret, messageId, 9L, message);

      verify(database).markAsStaleKeyExchange(messageId);
      verify(database, never()).markAsProcessedKeyExchange(messageId);
      securityEvents.verifyNoInteractions();
      assertThat(ciphers.constructed()).hasSize(1);
    }
  }

  private static IncomingKeyExchangeMessage keyExchangeMessage(boolean initiate) throws Exception {
    org.whispersystems.libsignal.ecc.ECKeyPair identityPair =
        org.whispersystems.libsignal.ecc.Curve.generateKeyPair();
    org.whispersystems.libsignal.ecc.ECKeyPair baseKey =
        org.whispersystems.libsignal.ecc.Curve.generateKeyPair();
    org.whispersystems.libsignal.ecc.ECKeyPair ratchetKey =
        org.whispersystems.libsignal.ecc.Curve.generateKeyPair();
    org.whispersystems.libsignal.IdentityKey identityKey =
        new org.whispersystems.libsignal.IdentityKey(identityPair.getPublicKey());
    int flags = initiate ? org.smssecure.smssecure.protocol.KeyExchangeMessage.INITIATE_FLAG
                         : org.smssecure.smssecure.protocol.KeyExchangeMessage.RESPONSE_FLAG;
    org.smssecure.smssecure.protocol.KeyExchangeMessage exchange =
        new org.smssecure.smssecure.protocol.KeyExchangeMessage(
            org.whispersystems.libsignal.protocol.CiphertextMessage.CURRENT_VERSION,
            1, flags, baseKey.getPublicKey(), new byte[0], ratchetKey.getPublicKey(), identityKey);
    String payload = org.smssecure.smssecure.util.Base64.encodeBytesWithoutPadding(exchange.serialize());
    IncomingTextMessage base = new IncomingTextMessage("alice", 1, 1L, payload, 3);
    return new IncomingKeyExchangeMessage(base, payload);
  }

  private static IncomingPreKeyBundleMessage preKeyMessage() {
    IncomingTextMessage base = new IncomingTextMessage("alice", 1, 1L, "payload", 3);
    return new IncomingPreKeyBundleMessage(base, "payload");
  }

  private static IdentityKey identityKey() {
    return new IdentityKey(Curve.generateKeyPair().getPublicKey());
  }

  private static org.whispersystems.libsignal.IdentityKey toVendored(IdentityKey identityKey)
      throws org.whispersystems.libsignal.InvalidKeyException {
    return new org.whispersystems.libsignal.IdentityKey(identityKey.serialize(), 0);
  }
}