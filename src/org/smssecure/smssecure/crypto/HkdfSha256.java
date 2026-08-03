package org.smssecure.smssecure.crypto;

import androidx.annotation.NonNull;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class HkdfSha256 {
  private static final int HASH_LENGTH = 32;

  private HkdfSha256() {}

  static @NonNull byte[] derive(@NonNull byte[] inputKeyMaterial,
                                @NonNull String info,
                                int outputLength)
  {
    if (outputLength <= 0 || outputLength > 255 * HASH_LENGTH) {
      throw new IllegalArgumentException("Invalid HKDF output length.");
    }

    byte[] pseudoRandomKey = null;

    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(new byte[HASH_LENGTH], "HmacSHA256"));
      pseudoRandomKey = mac.doFinal(inputKeyMaterial);
      mac.init(new SecretKeySpec(pseudoRandomKey, "HmacSHA256"));

      byte[] infoBytes = info.getBytes(StandardCharsets.UTF_8);
      byte[] output = new byte[outputLength];
      byte[] previous = new byte[0];
      int offset = 0;

      for (int counter = 1; offset < outputLength; counter++) {
        mac.update(previous);
        mac.update(infoBytes);
        mac.update((byte) counter);
        byte[] block = mac.doFinal();
        int copyLength = Math.min(block.length, outputLength - offset);
        System.arraycopy(block, 0, output, offset, copyLength);
        Arrays.fill(previous, (byte) 0);
        previous = block;
        offset += copyLength;
      }

      Arrays.fill(previous, (byte) 0);
      return output;
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new AssertionError(e);
    } finally {
      if (pseudoRandomKey != null) Arrays.fill(pseudoRandomKey, (byte) 0);
    }
  }
}