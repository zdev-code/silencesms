package org.smssecure.smssecure.crypto.storage;

import android.content.Context;

import org.smssecure.smssecure.crypto.MasterSecret;

/**
 * An isolated {@link VendoredSessionStore} that persists the <em>transitional</em> Key-Exchange
 * handshake state (the half-open {@code pendingKeyExchange} record produced when this app
 * initiates a key exchange) in its own on-disk directory.
 *
 * <p>Both the new libsignal cipher ({@code SilenceSessionStore}) and the vendored store share the
 * same {@code sessions-v2} files. When the app initiates a key exchange, the vendored builder
 * writes a half-open record whose {@code pendingKeyExchange} field only exists in the vendored
 * schema. If the libsignal cipher rewrites that shared file before the peer's reply arrives, the
 * pending state is erased and {@code SessionBuilder.processResponse} later throws
 * {@code StaleKeyExchangeException}.
 *
 * <p>By keeping the pending handshake in {@code sessions-v2-kex} — a directory the libsignal cipher
 * never touches — the half-open state survives the SMS round-trip. Once the handshake completes and
 * a fully established session exists, callers publish it into the shared {@code sessions-v2}
 * directory (via a normal {@link VendoredSessionStore}) so the cipher can use it.
 */
public class KeyExchangeSessionStore extends VendoredSessionStore {

  private static final String SESSIONS_DIRECTORY_KEX = "sessions-v2-kex";

  public KeyExchangeSessionStore(Context context, MasterSecret masterSecret, int subscriptionId) {
    super(context, masterSecret, subscriptionId);
  }

  @Override
  protected String getSessionsDirectoryName() {
    return SESSIONS_DIRECTORY_KEX;
  }
}
