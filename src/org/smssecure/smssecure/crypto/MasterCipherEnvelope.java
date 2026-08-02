package org.smssecure.smssecure.crypto;

import androidx.annotation.NonNull;

import org.signal.libsignal.protocol.InvalidMessageException;

import java.nio.ByteBuffer;
import java.util.Arrays;

final class MasterCipherEnvelope {
  static final byte[] MAGIC = new byte[] {'S', 'M', 'C', 'E'};

  static final int VERSION_1 = 1;
  static final int ALGORITHM_LEGACY_CBC_HMAC_SHA1 = 1;

  private static final int FIXED_HEADER_LENGTH = MAGIC.length + 1 + 1 + 1 + Integer.BYTES;

  private final byte[] nonce;
  private final byte[] ciphertext;
  private final byte[] authenticatedContent;
  private final byte[] authenticationTag;

  private MasterCipherEnvelope(@NonNull byte[] nonce,
                               @NonNull byte[] ciphertext,
                               @NonNull byte[] authenticatedContent,
                               @NonNull byte[] authenticationTag)
  {
    this.nonce                = nonce;
    this.ciphertext           = ciphertext;
    this.authenticatedContent = authenticatedContent;
    this.authenticationTag    = authenticationTag;
  }

  static boolean hasMagic(@NonNull byte[] serialized) {
    if (serialized.length < MAGIC.length) return false;

    for (int i = 0; i < MAGIC.length; i++) {
      if (serialized[i] != MAGIC[i]) return false;
    }

    return true;
  }

  static @NonNull byte[] serialize(@NonNull byte[] nonce,
                                   @NonNull byte[] ciphertext,
                                   @NonNull byte[] authenticationTag)
  {
    ByteBuffer buffer = ByteBuffer.allocate(FIXED_HEADER_LENGTH + nonce.length +
                                            ciphertext.length + authenticationTag.length);
    buffer.put(MAGIC);
    buffer.put((byte) VERSION_1);
    buffer.put((byte) ALGORITHM_LEGACY_CBC_HMAC_SHA1);
    buffer.put((byte) nonce.length);
    buffer.put(nonce);
    buffer.putInt(ciphertext.length);
    buffer.put(ciphertext);
    buffer.put(authenticationTag);
    return buffer.array();
  }

  static @NonNull byte[] serializeAuthenticatedContent(@NonNull byte[] nonce,
                                                        @NonNull byte[] ciphertext)
  {
    return serialize(nonce, ciphertext, new byte[0]);
  }

  static @NonNull MasterCipherEnvelope parse(@NonNull byte[] serialized,
                                              int authenticationTagLength)
      throws InvalidMessageException
  {
    if (!hasMagic(serialized) || serialized.length < FIXED_HEADER_LENGTH + authenticationTagLength) {
      throw new InvalidMessageException("Invalid MasterCipher envelope header.");
    }

    ByteBuffer buffer = ByteBuffer.wrap(serialized);
    byte[] magic = new byte[MAGIC.length];
    buffer.get(magic);

    int version = Byte.toUnsignedInt(buffer.get());
    int algorithm = Byte.toUnsignedInt(buffer.get());
    int nonceLength = Byte.toUnsignedInt(buffer.get());

    if (version != VERSION_1 || algorithm != ALGORITHM_LEGACY_CBC_HMAC_SHA1 || nonceLength != 16) {
      throw new InvalidMessageException("Unsupported MasterCipher envelope parameters.");
    }

    if (buffer.remaining() < nonceLength + Integer.BYTES + authenticationTagLength) {
      throw new InvalidMessageException("Truncated MasterCipher envelope.");
    }

    byte[] nonce = new byte[nonceLength];
    buffer.get(nonce);
    int ciphertextLength = buffer.getInt();
    long expectedRemaining = (long) ciphertextLength + authenticationTagLength;

    if (ciphertextLength <= 0 || ciphertextLength % 16 != 0 || expectedRemaining != buffer.remaining()) {
      throw new InvalidMessageException("Invalid MasterCipher envelope length.");
    }

    byte[] ciphertext = new byte[ciphertextLength];
    buffer.get(ciphertext);
    byte[] authenticationTag = new byte[authenticationTagLength];
    buffer.get(authenticationTag);
    byte[] authenticatedContent = Arrays.copyOf(serialized, serialized.length - authenticationTagLength);

    return new MasterCipherEnvelope(nonce, ciphertext, authenticatedContent, authenticationTag);
  }

  @NonNull byte[] getNonce() {
    return nonce;
  }

  @NonNull byte[] getCiphertext() {
    return ciphertext;
  }

  @NonNull byte[] getAuthenticatedContent() {
    return authenticatedContent;
  }

  @NonNull byte[] getAuthenticationTag() {
    return authenticationTag;
  }
}