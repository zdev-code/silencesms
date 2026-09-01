package org.smssecure.smssecure.util;

import junit.framework.AssertionFailedError;

import org.junit.Test;
import org.smssecure.smssecure.BaseUnitTest;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

public class PhoneNumberFormatterTest extends BaseUnitTest {
  private static final String LOCAL_NUMBER = "+15555555555";

  @Test public void testFormatNumberE164() throws Exception, InvalidNumberException {
    assertThat(PhoneNumberFormatter.formatNumber("(555) 555-5555", LOCAL_NUMBER)).isEqualTo(LOCAL_NUMBER);
    assertThat(PhoneNumberFormatter.formatNumber("555-5555", LOCAL_NUMBER)).isEqualTo(LOCAL_NUMBER);
    assertThat(PhoneNumberFormatter.formatNumber("(123) 555-5555", LOCAL_NUMBER)).isNotEqualTo(LOCAL_NUMBER);
  }

  @Test public void testFormatNumberEmail() throws Exception {
    try {
      PhoneNumberFormatter.formatNumber("person@domain.com", LOCAL_NUMBER);
      throw new AssertionFailedError("should have thrown on email");
    } catch (InvalidNumberException ine) {
      // success
    }
  }

  @Test public void testCanonicalizeNumberFallsBackForMalformedInput() {
    assertThat(PhoneNumberFormatter.canonicalizeNumber("not-a-number", LOCAL_NUMBER))
        .isEqualTo("not-a-number");
  }

  @Test public void testCanonicalizeNumberUsesLocalRegion() {
    assertThat(PhoneNumberFormatter.canonicalizeNumber("020 7946 0958", "+442079460000"))
        .isEqualTo("+442079460958");
  }

  @Test public void testCanonicalizeNumberPreservesDestinationWithoutConfiguredLocalNumber() {
    assertThat(PhoneNumberFormatter.canonicalizeNumber("020 7946 0958", "No Stored Number"))
        .isEqualTo("02079460958");
  }

  @Test public void testCanonicalizeNumberForRegion() {
    assertThat(PhoneNumberFormatter.canonicalizeNumberForRegion("020 7946 0958", "GB"))
        .isEqualTo("+442079460958");
    assertThat(PhoneNumberFormatter.canonicalizeNumberForRegion("12345", "GB"))
        .isEqualTo("12345");
    assertThat(PhoneNumberFormatter.canonicalizeNumberForRegion("EXAMPLE", "GB"))
        .isEqualTo("EXAMPLE");
  }

  @Test public void testFormatNumberForDisplay() {
    Locale previous = Locale.getDefault();
    try {
      Locale.setDefault(Locale.US);
      assertThat(PhoneNumberFormatter.formatNumberForDisplay("+15555555555"))
          .isEqualTo("+1 555-555-5555");
      assertThat(PhoneNumberFormatter.formatNumberForDisplay("12345")).isEqualTo("12345");
      assertThat(PhoneNumberFormatter.formatNumberForDisplay("person@domain.com"))
          .isEqualTo("person@domain.com");
    } finally {
      Locale.setDefault(previous);
    }
  }

  @Test public void testNumbersMatchAcrossRepresentations() {
    assertThat(PhoneNumberFormatter.areSameNumber("+1 555-555-5555", "(555) 555-5555")).isTrue();
    assertThat(PhoneNumberFormatter.areSameNumber("+1 555-555-5555", "555-5555")).isTrue();
    assertThat(PhoneNumberFormatter.areSameNumber("+1 555-555-5555", "+1 212-555-5555")).isFalse();
    assertThat(PhoneNumberFormatter.areSameNumber(null, "+1 555-555-5555")).isFalse();
  }
}
