# Authentication Surface-Qualified Continuations

Shared authentication hosts must bind opaque continuations to the exact surface as well as the host class.

## What Happened

In `silencesms/authentication-prompt-passphrase-host`, WELCOME, CREATE_PASSPHRASE, and
PROMPT_PASSPHRASE shared one Activity component. Class-only token ownership allowed a caller to change
the surface extra while retaining an otherwise valid token.

## Takeaway

Keep the public Intent shape limited to surface, destination, and opaque token, but bind the in-memory
entry to an internal `host:surface` owner. Consume with that same owner only after authentication
completion; a mismatched surface must remove/reject the token and fall back to fresh policy evaluation.

## History

- 2026-09-04 (silencesms/authentication-prompt-passphrase-host): initial