package org.smssecure.smssecure.domain.security;

import androidx.annotation.NonNull;

import java.util.Objects;

public final class AuthenticationBootstrapController {
  private final ApplicationAccessPolicy accessPolicy;

  public AuthenticationBootstrapController() {
    this(new ApplicationAccessPolicy());
  }

  AuthenticationBootstrapController(@NonNull ApplicationAccessPolicy accessPolicy) {
    this.accessPolicy = Objects.requireNonNull(accessPolicy);
  }

  public ApplicationAccessPolicy.State evaluate(@NonNull Snapshot snapshot) {
    Objects.requireNonNull(snapshot);
    return accessPolicy.evaluate(snapshot.isWelcomeRequired(), snapshot.isPassphraseInitialized(),
                                 snapshot.isUnlocked(), snapshot.isDatabaseUpgradeRequired());
  }

  public static final class Snapshot {
    private final boolean welcomeRequired;
    private final boolean passphraseInitialized;
    private final boolean unlocked;
    private final boolean databaseUpgradeRequired;

    public Snapshot(boolean welcomeRequired, boolean passphraseInitialized, boolean unlocked,
                    boolean databaseUpgradeRequired) {
      this.welcomeRequired = welcomeRequired;
      this.passphraseInitialized = passphraseInitialized;
      this.unlocked = unlocked;
      this.databaseUpgradeRequired = databaseUpgradeRequired;
    }

    public boolean isWelcomeRequired() { return welcomeRequired; }
    public boolean isPassphraseInitialized() { return passphraseInitialized; }
    public boolean isUnlocked() { return unlocked; }
    public boolean isDatabaseUpgradeRequired() { return databaseUpgradeRequired; }
  }
}