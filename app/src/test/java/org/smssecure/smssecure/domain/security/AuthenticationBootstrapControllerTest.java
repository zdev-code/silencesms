package org.smssecure.smssecure.domain.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

public class AuthenticationBootstrapControllerTest {
  private final AuthenticationBootstrapController controller =
      new AuthenticationBootstrapController();

  @Test
  public void evaluatesBootstrapGatesInSecurityOrder() {
    assertThat(evaluate(true, false, false, true))
        .isEqualTo(ApplicationAccessPolicy.State.WELCOME);
    assertThat(evaluate(false, false, false, true))
        .isEqualTo(ApplicationAccessPolicy.State.CREATE_PASSPHRASE);
    assertThat(evaluate(false, true, false, true))
        .isEqualTo(ApplicationAccessPolicy.State.PROMPT_PASSPHRASE);
    assertThat(evaluate(false, true, true, true))
        .isEqualTo(ApplicationAccessPolicy.State.UPGRADE_DATABASE);
    assertThat(evaluate(false, true, true, false))
        .isEqualTo(ApplicationAccessPolicy.State.READY);
  }

  @Test
  public void reevaluatesCurrentSnapshotInsteadOfRetainingReady() {
    assertThat(evaluate(false, true, true, false))
        .isEqualTo(ApplicationAccessPolicy.State.READY);
    assertThat(evaluate(false, true, false, false))
        .isEqualTo(ApplicationAccessPolicy.State.PROMPT_PASSPHRASE);
  }

  private ApplicationAccessPolicy.State evaluate(boolean welcomeRequired,
                                                  boolean passphraseInitialized,
                                                  boolean unlocked,
                                                  boolean databaseUpgradeRequired) {
    return controller.evaluate(new AuthenticationBootstrapController.Snapshot(
        welcomeRequired, passphraseInitialized, unlocked, databaseUpgradeRequired));
  }
}