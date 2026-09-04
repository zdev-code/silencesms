package org.smssecure.smssecure.domain.security;

public final class ApplicationAccessPolicy {
  public State evaluate(boolean welcomeRequired, boolean passphraseInitialized,
                        boolean unlocked, boolean databaseUpgradeRequired) {
    if (welcomeRequired) return State.WELCOME;
    if (!passphraseInitialized) return State.CREATE_PASSPHRASE;
    if (!unlocked) return State.PROMPT_PASSPHRASE;
    if (databaseUpgradeRequired) return State.UPGRADE_DATABASE;
    return State.READY;
  }

  public enum State {
    WELCOME,
    CREATE_PASSPHRASE,
    PROMPT_PASSPHRASE,
    UPGRADE_DATABASE,
    READY
  }
}