package org.smssecure.smssecure.domain.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

public class ScreenSecurityPolicyTest {
  @Test
  public void userPreferenceSecuresEveryDestination() {
    assertThat(ScreenSecurityPolicy.shouldSecure(true, false)).isTrue();
  }

  @Test
  public void destinationCanOnlyStrengthenSecurity() {
    assertThat(ScreenSecurityPolicy.shouldSecure(false, true)).isTrue();
  }

  @Test
  public void clearsFlagOnlyWhenNeitherPolicyRequiresIt() {
    assertThat(ScreenSecurityPolicy.shouldSecure(false, false)).isFalse();
  }
}