package org.smssecure.smssecure.domain.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

public class ApplicationAccessPolicyTest {
  private final ApplicationAccessPolicy policy = new ApplicationAccessPolicy();

  @Test
  public void welcomeHasHighestPriority() {
    assertThat(policy.evaluate(true, false, false, true))
        .isEqualTo(ApplicationAccessPolicy.State.WELCOME);
  }

  @Test
  public void passphraseCreationPrecedesUnlockAndUpgrade() {
    assertThat(policy.evaluate(false, false, false, true))
        .isEqualTo(ApplicationAccessPolicy.State.CREATE_PASSPHRASE);
  }

  @Test
  public void unlockPrecedesDatabaseUpgrade() {
    assertThat(policy.evaluate(false, true, false, true))
        .isEqualTo(ApplicationAccessPolicy.State.PROMPT_PASSPHRASE);
  }

  @Test
  public void databaseUpgradeRunsOnlyAfterUnlock() {
    assertThat(policy.evaluate(false, true, true, true))
        .isEqualTo(ApplicationAccessPolicy.State.UPGRADE_DATABASE);
  }

  @Test
  public void readyRequiresEveryGateToPass() {
    assertThat(policy.evaluate(false, true, true, false))
        .isEqualTo(ApplicationAccessPolicy.State.READY);
  }
}