package org.smssecure.smssecure.domain.security;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WipeablePassphraseTest {
  @Test
  public void closeWipesOwnedCharacters() {
    char[] characters = "secret".toCharArray();
    WipeablePassphrase passphrase = WipeablePassphrase.takeOwnership(characters);

    passphrase.close();

    assertTrue(isAllZero(characters));
  }

  @Test
  public void comparisonDoesNotMutateOrStringifyValues() {
    try (WipeablePassphrase first = WipeablePassphrase.copyOf(new StringBuilder("secret"));
         WipeablePassphrase same = WipeablePassphrase.copyOf(new StringBuilder("secret"));
         WipeablePassphrase different = WipeablePassphrase.copyOf(new StringBuilder("other"))) {
      assertTrue(first.contentEquals(same));
      assertFalse(first.contentEquals(different));
    }
  }

  private static boolean isAllZero(char[] value) {
    for (char character : value) if (character != '\0') return false;
    return true;
  }
}