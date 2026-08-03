package org.smssecure.smssecure.crypto;

/**
 * Thrown when an attachment's MAC or digest fails verification. This is a local replacement for the
 * vendored {@code org.whispersystems.libsignal.InvalidMacException}: MAC verification of local
 * attachment streams is unrelated to the Signal Protocol session layer, so it does not need a
 * libsignal type. It is used internally by {@link AttachmentCipherInputStream}, which rethrows it as
 * an {@code InvalidMessageException} at its constructor boundary.
 */
public class InvalidMacException extends Exception {

  public InvalidMacException(String detailMessage) {
    super(detailMessage);
  }

  public InvalidMacException(Throwable throwable) {
    super(throwable);
  }
}
