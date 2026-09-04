package org.smssecure.smssecure.domain.security;

import androidx.annotation.NonNull;

import java.util.Arrays;

public final class WipeablePassphrase implements AutoCloseable {
  private char[] value;

  public static WipeablePassphrase copyOf(@NonNull CharSequence source) {
    char[] copy = new char[source.length()];
    for (int index = 0; index < source.length(); index++) copy[index] = source.charAt(index);
    return new WipeablePassphrase(copy);
  }

  public static WipeablePassphrase takeOwnership(@NonNull char[] value) {
    return new WipeablePassphrase(value);
  }

  private WipeablePassphrase(char[] value) {
    this.value = value;
  }

  public boolean isEmpty() {
    return value == null || value.length == 0;
  }

  public boolean contentEquals(@NonNull WipeablePassphrase other) {
    return value != null && other.value != null && Arrays.equals(value, other.value);
  }

  public char[] copy() {
    if (value == null) throw new IllegalStateException("Passphrase buffer is closed");
    return Arrays.copyOf(value, value.length);
  }

  @Override
  public void close() {
    if (value != null) {
      Arrays.fill(value, '\0');
      value = null;
    }
  }
}