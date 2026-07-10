/**
 * Copyright (C) 2013-2016 Open Whisper Systems
 *
 * Licensed according to the LICENSE file in this repository.
 */
package org.whispersystems.libsignal.ecc;

import org.whispersystems.libsignal.InvalidKeyException;

/**
 * Curve25519 primitives for the vendored Key-Exchange ratchet.
 *
 * <p>The EC engine delegates to the maintained Rust core ({@code org.signal.libsignal.protocol.ecc})
 * instead of the archived {@code curve25519-java}. The public API and the vendored {@link ECPublicKey}/
 * {@link ECPrivateKey} byte formats are unchanged (33-byte {@code 0x05}-prefixed public point, 32-byte
 * private scalar), and the X25519 agreement / XEd25519 signatures are wire-compatible with the previous
 * implementation, so existing on-disk keys and peers on older builds interoperate. Methods still throw
 * the vendored {@link InvalidKeyException} because the vendored ratchet callers depend on it. VRF
 * signatures (only ever used by the unused {@code DeviceConsistency} feature) are not offered by the
 * maintained {@code Curve} and have been removed.
 */
public class Curve {

  public  static final int DJB_TYPE   = 0x05;

  public static boolean isNative() {
    // The maintained Rust core is always native.
    return true;
  }

  public static ECKeyPair generateKeyPair() {
    org.signal.libsignal.protocol.ecc.ECKeyPair keyPair =
        org.signal.libsignal.protocol.ecc.Curve.generateKeyPair();
    try {
      return new ECKeyPair(decodePoint(keyPair.getPublicKey().serialize(), 0),
                           decodePrivatePoint(keyPair.getPrivateKey().serialize()));
    } catch (InvalidKeyException e) {
      throw new AssertionError(e); // a freshly generated key always decodes
    }
  }

  public static ECPublicKey decodePoint(byte[] bytes, int offset)
      throws InvalidKeyException
  {
    if (bytes == null || bytes.length - offset < 1) {
      throw new InvalidKeyException("No key type identifier");
    }

    int type = bytes[offset] & 0xFF;

    switch (type) {
      case Curve.DJB_TYPE:
        if (bytes.length - offset < 33) {
          throw new InvalidKeyException("Bad key length: " + bytes.length);
        }

        byte[] keyBytes = new byte[32];
        System.arraycopy(bytes, offset+1, keyBytes, 0, keyBytes.length);
        return new DjbECPublicKey(keyBytes);
      default:
        throw new InvalidKeyException("Bad key type: " + type);
    }
  }

  public static ECPrivateKey decodePrivatePoint(byte[] bytes) {
    return new DjbECPrivateKey(bytes);
  }

  public static byte[] calculateAgreement(ECPublicKey publicKey, ECPrivateKey privateKey)
      throws InvalidKeyException
  {
    if (publicKey == null) {
      throw new InvalidKeyException("public value is null");
    }

    if (privateKey == null) {
      throw new InvalidKeyException("private value is null");
    }

    if (publicKey.getType() != privateKey.getType()) {
      throw new InvalidKeyException("Public and private keys must be of the same type!");
    }

    if (publicKey.getType() == DJB_TYPE) {
      try {
        org.signal.libsignal.protocol.ecc.ECPublicKey  theirs = org.signal.libsignal.protocol.ecc.Curve.decodePoint(publicKey.serialize(), 0);
        org.signal.libsignal.protocol.ecc.ECPrivateKey ours   = org.signal.libsignal.protocol.ecc.Curve.decodePrivatePoint(privateKey.serialize());
        return org.signal.libsignal.protocol.ecc.Curve.calculateAgreement(theirs, ours);
      } catch (org.signal.libsignal.protocol.InvalidKeyException e) {
        throw new InvalidKeyException(e);
      }
    } else {
      throw new InvalidKeyException("Unknown type: " + publicKey.getType());
    }
  }

  public static boolean verifySignature(ECPublicKey signingKey, byte[] message, byte[] signature)
      throws InvalidKeyException
  {
    if (signingKey == null || message == null || signature == null) {
      throw new InvalidKeyException("Values must not be null");
    }

    if (signingKey.getType() == DJB_TYPE) {
      try {
        org.signal.libsignal.protocol.ecc.ECPublicKey key = org.signal.libsignal.protocol.ecc.Curve.decodePoint(signingKey.serialize(), 0);
        return org.signal.libsignal.protocol.ecc.Curve.verifySignature(key, message, signature);
      } catch (org.signal.libsignal.protocol.InvalidKeyException e) {
        throw new InvalidKeyException(e);
      }
    } else {
      throw new InvalidKeyException("Unknown type: " + signingKey.getType());
    }
  }

  public static byte[] calculateSignature(ECPrivateKey signingKey, byte[] message)
      throws InvalidKeyException
  {
    if (signingKey == null || message == null) {
      throw new InvalidKeyException("Values must not be null");
    }

    if (signingKey.getType() == DJB_TYPE) {
      try {
        org.signal.libsignal.protocol.ecc.ECPrivateKey key = org.signal.libsignal.protocol.ecc.Curve.decodePrivatePoint(signingKey.serialize());
        return org.signal.libsignal.protocol.ecc.Curve.calculateSignature(key, message);
      } catch (org.signal.libsignal.protocol.InvalidKeyException e) {
        throw new InvalidKeyException(e);
      }
    } else {
      throw new InvalidKeyException("Unknown type: " + signingKey.getType());
    }
  }
}
